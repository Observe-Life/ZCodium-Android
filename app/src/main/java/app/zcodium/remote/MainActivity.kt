package app.zcodium.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class MainActivity : Activity() {
    private lateinit var rootFrame: FrameLayout
    private lateinit var browser: WebView
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var connectivityManager: ConnectivityManager
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val backCallback = object : OnBackInvokedCallback {
        override fun onBackInvoked() {
            Log.i(TAG, "OnBackInvoked fired: isOnChatPage=$isOnChatPage")
            runOnUiThread {
                /* 统一交给补丁分级处理：侧栏开→关侧栏；会话页→回列表；列表页→退后台保进程 */
                browser.evaluateJavascript(
                    "(window.__zcodePatch&&window.__zcodePatch.onSystemBack)?window.__zcodePatch.onSystemBack():'exit'"
                ) { result ->
                    if (result == null || result == "\"exit\"" || result == "null") {
                        moveTaskToBack(true)
                    }
                }
            }
        }
    }
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pageLoaded = false

    /** 最近一次主文档加载是否失败；只有它才允许触发自动重载 */
    private var loadFailed = false
    private var networkAvailable = true
    private var reloadWhenOnline = false
    private var currentPage = "home"
    private var cachedUserAgent = ""

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                val wasOffline = !networkAvailable
                networkAvailable = true
                /* 仅当页面此前确实加载失败时才补一次重载；页面正常时断网恢复不打断它 */
                if (wasOffline && reloadWhenOnline && loadFailed && currentUrl().isNotBlank()) {
                    reloadWhenOnline = false
                    browser.reload()
                }
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                networkAvailable = false
                reloadWhenOnline = loadFailed
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /* 固定任务描述名：文件选择器打开期间最近任务不显示第三方文件管理器名 */
        setTaskDescription(android.app.ActivityManager.TaskDescription(getString(R.string.app_name)))
        rootFrame = FrameLayout(this).apply {
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                /* 软键盘弹出时也把 IME 高度算进底部 padding，输入框才缩在键盘上方并可内滚（新13） */
                val ime = insets.getInsets(WindowInsets.Type.ime())
                val bottom = maxOf(bars.bottom, ime.bottom)
                v.setPadding(bars.left, bars.top, bars.right, bottom)
                insets
            }
            setBackgroundColor(Color.WHITE)
        }
        setContentView(rootFrame)
        buildEmptyState()
        buildWebView()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkAvailable = connectivityManager.activeNetwork != null
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback
            )
            Log.i(TAG, "Registered backCallback with onBackInvokedDispatcher successfully")
        }
        val prefs = getPreferences(MODE_PRIVATE)
        val currentVersion = packageManager.getPackageInfo(packageName, 0).longVersionCode
        val savedVersion = prefs.getLong(KEY_RESTORE_VERSION, -1L)
        prefs.edit().putLong(KEY_RESTORE_VERSION, currentVersion).apply()
        /* 同一 APK 版本才恢复 WebView：修外链回来白屏；安装新版后必须重新加载，
           否则旧 savedInstanceState 会把上一版 DOM/补丁整页还原，新改动不生效。 */
        val restored = savedVersion == currentVersion &&
            savedInstanceState != null &&
            browser.restoreState(savedInstanceState) != null
        if (restored) {
            Log.i(TAG, "restored WebView state from savedInstanceState")
            emptyState.visibility = View.GONE
            browser.visibility = View.VISIBLE
            pageLoaded = true
        } else {
            /* 仅调试包：CI 遍历测试钩子——adb 以 --es zc_test_url <网址> 启动即预填并连接，
               绕开 input text/坐标点击的不确定性；正式包不带此入口 */
            val testUrl = if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                intent?.getStringExtra("zc_test_url")
            } else null
            if (!testUrl.isNullOrBlank()) {
                Log.i(TAG, "test-hook connect:$testUrl")
                emptyInput.setText(testUrl)
                connect(testUrl)
            } else {
                val saved = prefs.getString(KEY_REMOTE_URL, "").orEmpty()
                if (saved.isNotBlank()) connect(saved) else showEmptyState()
            }
        }
        /* 推送通知点击冷启动：把目标会话记下来，等页面就绪后再打开 */
        handleDeepLink(intent)
    }

    /* ── Deep Link（推送通知点击进入指定会话） ──
       调度只由一个入口负责：scheduleDeepLink()。每次尝试最多安排一次重试，
       避免出现“一次失败排两个任务、次数被双倍消耗”的重复排队。 */

    private data class DeepLinkTarget(val sessionId: String, val event: String, val interactionId: String)

    private var pendingDeepLink: DeepLinkTarget? = null
    private var deepLinkTries = 0
    private var deepLinkScheduled = false

    private val deepLinkWatch = object : Runnable {
        override fun run() {
            deepLinkScheduled = false
            if (isFinishing || isDestroyed) return
            val target = pendingDeepLink ?: return
            if (deepLinkTries >= DEEPLINK_MAX_TRIES) {
                giveUpDeepLink(target, "重试 $deepLinkTries 次仍未成功")
                return
            }
            deepLinkTries++
            attemptDeepLink(target)
        }
    }

    /** 唯一的重试调度入口：已在队列中或没有待打开目标时不重复安排。 */
    private fun scheduleDeepLink() {
        if (deepLinkScheduled) return
        val target = pendingDeepLink ?: return
        if (deepLinkTries >= DEEPLINK_MAX_TRIES) {
            giveUpDeepLink(target, "重试 $deepLinkTries 次仍未成功")
            return
        }
        deepLinkScheduled = true
        rootFrame.postDelayed(deepLinkWatch, DEEPLINK_RETRY_MS)
    }

    private fun giveUpDeepLink(target: DeepLinkTarget, why: String) {
        Log.w(TAG, "deep link 放弃：$why（sess=${target.sessionId}）")
        pendingDeepLink = null
        deepLinkScheduled = false
        rootFrame.removeCallbacks(deepLinkWatch)
        Toast.makeText(this@MainActivity, R.string.deeplink_session_not_found, Toast.LENGTH_SHORT).show()
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent == null) return
        val uri = intent.data
        val isZCodeUri = "zcode".equals(uri?.scheme, true) &&
            "session".equals(uri?.host, true)

        /*
         * 两种入口共用一套后续逻辑：
         * 1. zcode://session?sess=...（普通 Android Deep Link）；
         * 2. Server酱 scopen://app.zcodium.remote?sess=...（按包名启动后作为 String extras 传入）。
         */
        val sessionId = if (isZCodeUri) {
            uri?.getQueryParameter("sess").orEmpty()
        } else {
            intent.getStringExtra("sess").orEmpty()
        }
        if (sessionId.isBlank()) return

        val event = if (isZCodeUri) {
            uri?.getQueryParameter("event").orEmpty()
        } else {
            intent.getStringExtra("event").orEmpty()
        }
        val interactionId = if (isZCodeUri) {
            uri?.getQueryParameter("interaction").orEmpty()
        } else {
            intent.getStringExtra("interaction").orEmpty()
        }
        Log.i(TAG, "会话跳转: source=${if (isZCodeUri) "uri" else "serverchan"} " +
            "sess=$sessionId event=$event interaction=$interactionId")
        /* 新链接取代旧目标：先清掉旧的重试任务，避免多个任务并发 */
        rootFrame.removeCallbacks(deepLinkWatch)
        deepLinkScheduled = false
        deepLinkTries = 0
        pendingDeepLink = DeepLinkTarget(sessionId, event, interactionId)
        /* 无论页面是否已就绪，统一交给调度器驱动——冷启动也必须能重试 */
        scheduleDeepLink()
    }

    /** 尝试一次：页面未就绪只记日志并等下一次；返回结果由回调决定是否继续重试。 */
    private fun attemptDeepLink(target: DeepLinkTarget) {
        val notReady = when {
            currentUrl().isBlank() -> "页面未加载"
            !pageLoaded -> "页面加载中"
            !networkAvailable -> "网络不可用"
            else -> null
        }
        if (notReady != null) {
            Log.i(TAG, "deep link 等待页面就绪（$notReady）第 $deepLinkTries 次 sess=${target.sessionId}")
            scheduleDeepLink()
            return
        }
        val js = "(window.__zcodePatch&&window.__zcodePatch.openSessionById)" +
            "?window.__zcodePatch.openSessionById(${jsonStr(target.sessionId)}," +
            "${jsonStr(target.event)},${jsonStr(target.interactionId)}):false"
        browser.evaluateJavascript(js) { result ->
            if (result == "true") {
                Log.i(TAG, "deep link 已打开 ${target.sessionId}（第 $deepLinkTries 次）")
                pendingDeepLink = null
                deepLinkScheduled = false
                return@evaluateJavascript
            }
            Log.i(TAG, "deep link 目标节点尚未就绪（第 $deepLinkTries 次）sess=${target.sessionId}")
            scheduleDeepLink()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        /* singleTop：App 已在运行时，通知点击走这里而不是重建 */
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (currentUrl().isNotBlank()) {
            browser.saveState(outState)
            Log.i(TAG, "saved WebView state")
        }
    }

    private fun buildEmptyState() {
        emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            setTextColor(Color.parseColor("#18181b"))
        }
        emptyInput = EditText(this).apply {
            hint = getString(R.string.remote_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        val connect = Button(this).apply {
            text = getString(R.string.connect)
            setOnClickListener { connect(emptyInput.text.toString()) }
        }
        emptyState.addView(
            title,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(24) }
        )
        emptyState.addView(emptyInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        emptyState.addView(
            connect,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(16) }
        )
        rootFrame.addView(
            emptyState,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        errorText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.DKGRAY)
            visibility = View.GONE
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        browser = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        rootFrame.addView(
            browser,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        rootFrame.addView(
            progress,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { gravity = Gravity.TOP }
        )
        rootFrame.addView(
            errorText,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
        )
        configureWebView()
    }

    override fun finish() {
        Log.e(TAG, "!!! finish() called !!!", Exception("finish trace"))
        super.finish()
    }

    override fun finishAfterTransition() {
        if (isOnChatPage) {
            Log.i(TAG, "BLOCKED finishAfterTransition (isOnChatPage=true)")
            return
        }
        super.finishAfterTransition()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && currentUrl().isNotBlank()) {
            Log.i(TAG, "dispatchKeyEvent BACK action=${event.action}: isOnChatPage=$isOnChatPage")
            if (isOnChatPage) {
                if (event.action == android.view.KeyEvent.ACTION_UP) {
                    Log.i(TAG, "BLOCKED back UP, delegating to onSystemBack")
                    runJs("(window.__zcodePatch&&window.__zcodePatch.onSystemBack)?window.__zcodePatch.onSystemBack():'home'")
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showEmptyState() {
        emptyState.visibility = View.VISIBLE
        browser.visibility = View.GONE
    }

    private fun connect(rawUrl: String) {
        val normalized = rawUrl.trim()
        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
        if (uri == null || uri.scheme?.equals("https", true) != true || uri.host.isNullOrBlank()) {
            val msg = getString(R.string.invalid_url)
            Log.i(TAG, "toast:$msg") // toast 不上 logcat；遍历断言靠这行判定提示是否出现
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }
        getPreferences(MODE_PRIVATE).edit().putString(KEY_REMOTE_URL, uri.toString()).apply()
        emptyInput.setText(uri.toString())
        emptyState.visibility = View.GONE
        browser.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        pageLoaded = false
        mainFrameRetryCount = 0
        loadWithDiscovery(uri.toString())
    }

    private fun showRemoteDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.remote_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setSelectAllOnFocus(true)
            setText(currentUrl().ifBlank {
                getPreferences(MODE_PRIVATE).getString(KEY_REMOTE_URL, "").orEmpty()
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_control)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.connect) { _, _ -> connect(input.text.toString()) }
            .show()
    }

    private fun showForkDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.fork_confirm_title)
            .setMessage(R.string.fork_confirm_message)
            .setNegativeButton(R.string.cancel) { _, _ ->
                runJs("window.__zcodePatch && window.__zcodePatch.forkCancelled && window.__zcodePatch.forkCancelled()")
            }
            .setPositiveButton(R.string.confirm) { _, _ ->
                runJs("window.__zcodePatch && window.__zcodePatch.forkConfirmed && window.__zcodePatch.forkConfirmed()")
            }
            .show()
    }

    private fun runJs(script: String) {
        runOnUiThread { browser.evaluateJavascript(script, null) }
    }

    @Volatile private var isOnChatPage = false

    @Deprecated("Deprecated in Java; used as reliable back-handling fallback")
    override fun onBackPressed() {
        Log.i(TAG, "onBackPressed: isOnChatPage=$isOnChatPage url=${currentUrl()}")
        if (currentUrl().isBlank()) {
            finish()
            return
        }
        if (isOnChatPage) {
            runJs("window.__zcodePatch.goHome && window.__zcodePatch.goHome()")
        } else {
            /* 退到后台但保留进程，下次打开秒回当前状态 */
            moveTaskToBack(true)
        }
    }

    private fun handleSystemBack() {
        if (currentUrl().isBlank()) {
            finish()
            return
        }
        browser.evaluateJavascript(
            "(window.__zcodePatch&&window.__zcodePatch.onSystemBack)?window.__zcodePatch.onSystemBack():'exit'"
        ) { result ->
            if (result == null || result == "\"exit\"" || result == "null") {
                runOnUiThread { finish() }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(browser, false)
        }
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            /* 附件上传需要：文件选择器返回的 file:// 缓存副本要能被 WebView 读取；网页导航仍被 shouldOverrideUrlLoading 限定为 https */
            allowFileAccess = true
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
        }
        browser.addJavascriptInterface(ZCodeNativeBridge(), "ZCodeNative")
        // 仅调试包开放 DevTools 套接字（CI 的 CDP 探针靠它进网页内部断言）；正式包不留此通道
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        cachedUserAgent = WebSettings.getDefaultUserAgent(this)
        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            /** 页面内部报错默认看不到，这里把警告与错误转发到 logcat，便于定位白屏/卡启动 */
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                if (msg.messageLevel() >= ConsoleMessage.MessageLevel.WARNING) {
                    Log.w(
                        TAG,
                        "[console] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}"
                    )
                }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                Log.i(TAG, "FileChooser show mode=${fileChooserParams?.mode}")
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    true
                } catch (_: Exception) {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    Toast.makeText(this@MainActivity, R.string.file_picker_failed, Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return true
                val host = url.host ?: ""
                /* 回答里的外链一律跳系统浏览器；Remote 主站（官方或本机配置的主机）仍在 App 内打开 */
                val configuredHost = runCatching { Uri.parse(configuredRemoteUrl()).host }.getOrNull().orEmpty()
                val isRemote = host.equals("zcode.z.ai", true) ||
                    (configuredHost.isNotEmpty() && host.equals(configuredHost, true))
                val isHttp = scheme.equals("https", true) || scheme.equals("http", true)
                if (isHttp && !isRemote) {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                return !(scheme.equals("https", true) || scheme.equals("about", true))
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (request.isForMainFrame) Log.i(TAG, "main frame: $url")
                if (url.contains("__zp_patch__")) Log.i(TAG, "patch asset requested: $url")
                return try {
                    when {
                        request.isForMainFrame && MAIN_FRAME_PATTERN.matcher(url).find() -> {
                            Log.i(TAG, "pattern matched, fetching HTML for patch injection")
                            fetchPatchedHtml(url)
                        }
                        url.endsWith(PATCH_JS_PATH) -> {
                            Log.i(TAG, "serving patch js from assets")
                            assetResponse("zcode-patch.js", "application/javascript")
                        }
                        url.endsWith(PATCH_CSS_PATH) -> {
                            Log.i(TAG, "serving patch css from assets")
                            assetResponse("zcode-patch.css", "text/css")
                        }
                        else -> {
                            /* 官方页面资源：优先用随包携带的本地快照，缺失的资源才回源到线上 */
                            val m = ASSET_PATH_PATTERN.matcher(url)
                            if (m.matches()) serveSnapshotAsset(m.group(1)) else {
                                if (url.contains("/remote/v4/")) Log.i(TAG, "asset not intercepted: $url")
                                null
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "intercept failed: ${e.message}", e)
                    null
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pageLoaded = false
                loadFailed = false
                errorText.visibility = View.GONE
                browser.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                loadFailed = false
                reloadWhenOnline = false
                if (!url.isNullOrBlank() && url.contains("/remote/v4")) {
                    getPreferences(MODE_PRIVATE).edit().putString(KEY_LAST_EFFECTIVE, url).apply()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    pageLoaded = false
                    loadFailed = true
                    reloadWhenOnline = !networkAvailable
                    errorText.text = getString(
                        if (networkAvailable) R.string.connection_failed else R.string.status_offline
                    )
                    errorText.visibility = View.VISIBLE
                    /* 静默自愈：隧道换址/首次解析失手时，自动重查一次 DoH 并重载 */
                    if (networkAvailable && mainFrameRetryCount < MAX_MAIN_FRAME_RETRY) {
                        mainFrameRetryCount++
                        browser.postDelayed({
                            loadWithDiscovery(configuredRemoteUrl(), mainFrameRetryCount)
                        }, 1500L)
                    }
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                pageLoaded = false
                recreate()
                return true
            }
        }
    }

    /** 拉取官方 HTML 入口并在 </head> 前注入补丁引用；失败时返回 null 走原版加载。 */
    private fun fetchPatchedHtml(url: String): WebResourceResponse? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept-Encoding", "identity")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            conn.setRequestProperty("User-Agent", cachedUserAgent)
            val code = conn.responseCode
            Log.i(TAG, "fetch HTML response code: $code, final URL: ${conn.url}")
            if (code !in 200..299) return null
            val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            Log.i(TAG, "HTML length: ${body.length}, has </head>: ${body.contains("</head>", true)}")
            if (!body.contains("</head>", ignoreCase = true)) {
                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
            }
            val patched = body.replaceFirst(
                Regex("</head>", RegexOption.IGNORE_CASE),
                """<link rel="stylesheet" href="$PATCH_CSS_PATH"/><script src="$PATCH_JS_PATH"></script></head>"""
            )
            /*
             * 线上页面已改用新版接口（model-selection / provider-settings），旧版电脑端不支持，
             * 会把入口包名换成随 App 打包的本地快照包（使用 model-provider 旧接口），
             * 从而在不升级电脑端的前提下恢复模型配置加载。
             */
            val pinned = if (PIN_OLD_PAGE) patched.replace(
                Regex("assets/index-[A-Za-z0-9_-]+\\.js"),
                "assets/$SNAPSHOT_INDEX$SNAPSHOT_INDEX_QUERY"
            ) else patched
            Log.i(
                TAG,
                "patch injected, patched length: ${patched.length}, " +
                    "pinned index: ${pinned != patched} -> $SNAPSHOT_INDEX"
            )
            return WebResourceResponse(
                "text/html", "utf-8", ByteArrayInputStream(pinned.toByteArray(Charsets.UTF_8))
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun assetResponse(name: String, mime: String): WebResourceResponse {
        val stream = assets.open(name)
        return WebResourceResponse(mime, "utf-8", stream)
    }

    /** 从随包携带的页面快照里取资源；没有这个文件就返回 null，由系统回源到线上。 */
    private fun serveSnapshotAsset(name: String): WebResourceResponse? {
        return try {
            val mime = mimeOf(name)
            val charset = if (mime.startsWith("text/") || mime.contains("javascript") ||
                mime == "application/json"
            ) "utf-8" else null
            Log.i(TAG, "serving snapshot asset: $name")
            WebResourceResponse(mime, charset, assets.open("$SNAPSHOT_DIR/$name"))
        } catch (e: Exception) {
            null
        }
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".mp3") -> "audio/mpeg"
        name.endsWith(".woff2") -> "font/woff2"
        name.endsWith(".woff") -> "font/woff"
        name.endsWith(".json") -> "application/json"
        else -> "application/octet-stream"
    }

    private fun currentUrl(): String = browser.url?.trim().orEmpty()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            Log.i(TAG, "FileChooser result=$resultCode uri=${data?.data}")
            val picked = resultCode == RESULT_OK && data?.data != null
            if (picked) {
                /* 先注入网页 File，再关闭官方 file chooser，避免 input 在等待中被取消失效 */
                handlePickedFile(data.data!!)
            } else {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = null
            }
        }
    }

    /* 第三方文件管理器的 content:// Uri chromium 无法物化进网页，复制进缓存后由补丁经桥读取注入 */
    private var currentAttachment: java.io.File? = null

    private fun handlePickedFile(src: Uri) {
        val name = queryDisplayName(src) ?: "attachment.bin"
        val safeName = name.replace(Regex("[/\\\\:?*\"<>|]"), "_")
        val mime = try {
            contentResolver.getType(src) ?: "application/octet-stream"
        } catch (e: Exception) {
            "application/octet-stream"
        }
        try {
            val dir = java.io.File(cacheDir, "uploads").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val dst = java.io.File(dir, safeName)
            contentResolver.openInputStream(src)?.use { input ->
                dst.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return
            currentAttachment = dst
            Log.i(TAG, "Attachment cached: ${dst.name} ${dst.length()}B mime=$mime")
            val total = dst.length()
            runOnUiThread {
                browser.evaluateJavascript(
                    "window.__zcodePatch && window.__zcodePatch.onAttachmentPicked ? window.__zcodePatch.onAttachmentPicked(${jsonStr(dst.name)},${jsonStr(mime)},$total) : 'no-patch';"
                ) { result ->
                    Log.i(TAG, "Attachment inject result=$result")
                    /* 官方 change 已同步取走 File；延迟释放 chromium 选择器状态，
                       否则下一次点"添加附件"不会触发 onShowFileChooser（只清状态，不碰已注入附件） */
                    val cb = fileChooserCallback
                    browser.postDelayed({
                        if (fileChooserCallback === cb && cb != null) {
                            fileChooserCallback?.onReceiveValue(null)
                            fileChooserCallback = null
                        }
                    }, 1200)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handlePickedFile failed: ${e.message}")
            runOnUiThread {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = null
            }
        }
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        browser.onResume()
        browser.resumeTimers()
        /* 只有主文档确实加载失败（断网等）才重新加载。
           页面本身正常时不再刷新——否则每次从桌面/通知回到前台都会白白重载一次。 */
        if (loadFailed && networkAvailable && currentUrl().isNotBlank()) browser.reload()
    }

    override fun onPause() {
        /* 文件选择期间保持 WebView 定时器运行：官方会话心跳一旦暂停，重连后附件会被快照化丢弃 file 内容 */
        if (fileChooserCallback == null) browser.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (::connectivityManager.isInitialized) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        }
        browser.apply {
            stopLoading()
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            removeJavascriptInterface("ZCodeNative")
            destroy()
        }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    inner class ZCodeNativeBridge {
        @JavascriptInterface
        fun postState(page: String, title: String) {
            Log.i(TAG, "postState: page=$page title=$title")
            currentPage = page
            isOnChatPage = page == "chat"
        }

        @JavascriptInterface
        fun postLog(msg: String) {
            Log.i(TAG, msg)
        }

        @JavascriptInterface
        fun openRemoteDialog() {
            runOnUiThread { showRemoteDialog() }
        }

        /* 首页标题栏齿轮：进入「推送通知设置」；顺带把当前会话 ID 带过去，测试推送才能带上跳转链接 */
        @JavascriptInterface
        fun openPushSettings() {
            runOnUiThread {
                browser.evaluateJavascript(
                    "(window.__zcodePatch&&window.__zcodePatch.currentSessionId)" +
                        "?window.__zcodePatch.currentSessionId():''"
                ) { result ->
                    val sess = result.orEmpty().trim('"').takeIf { it != "null" }.orEmpty()
                    runOnUiThread {
                        try {
                            startActivity(
                                Intent(this@MainActivity, PushSettingsActivity::class.java)
                                    .putExtra(PushSettingsActivity.EXTRA_SESSION_ID, sess)
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "open push settings failed: ${e.message}")
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun confirmFork(label: String) {
            Log.i(TAG, "fork intercepted: $label")
            runOnUiThread { showForkDialog() }
        }

        @JavascriptInterface
        fun toast(msg: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "无法打开链接", Toast.LENGTH_SHORT).show()
                }
            }
        }

        /* 附件注入：网页按块读取 native 侧缓存文件（chromium 对 content:// 选择结果物化失败，改走此通道） */
        @JavascriptInterface
        fun readAttachmentChunk(offset: Int, length: Int): String? {
            val f = currentAttachment ?: return null
            return try {
                java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.seek(offset.toLong())
                    val buf = ByteArray(length)
                    val n = raf.read(buf)
                    if (n <= 0) return null
                    android.util.Base64.encodeToString(if (n == length) buf else buf.copyOf(n), android.util.Base64.NO_WRAP)
                }
            } catch (e: Exception) {
                Log.w(TAG, "readAttachmentChunk failed: ${e.message}")
                null
            }
        }

        /* 状态栏外观跟随官方主题（深色=亮图标+深底色，浅色=暗图标+白底色） */
        @JavascriptInterface
        fun setStatusBarDark(dark: Int) {
            runOnUiThread {
                try {
                    rootFrame.setBackgroundColor(if (dark == 1) Color.parseColor("#161616") else Color.WHITE)
                    val insetsController = window.insetsController ?: return@runOnUiThread
                    if (dark == 1) {
                        insetsController.setSystemBarsAppearance(
                            0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        )
                    } else {
                        insetsController.setSystemBarsAppearance(
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "setStatusBarDark failed: ${e.message}")
                }
            }
        }

        /* 图片预览长按保存：网页分块传 base64，落盘后插入系统下载目录 */
        @JavascriptInterface
        fun saveImageChunk(idx: Int, b64: String) {
            try {
                val dir = java.io.File(cacheDir, "saveimg").apply { mkdirs() }
                val f = java.io.File(dir, "part.bin")
                if (idx == 0) f.delete()
                java.io.FileOutputStream(f, true).use {
                    it.write(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP))
                }
            } catch (e: Exception) {
                Log.w(TAG, "saveImageChunk failed: ${e.message}")
            }
        }

        @JavascriptInterface
        fun saveImageCommit(name: String) {
            try {
                val src = java.io.File(cacheDir, "saveimg/part.bin")
                if (!src.exists() || src.length() == 0L) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "保存失败：没有图片数据", Toast.LENGTH_SHORT).show() }
                    return
                }
                val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val ext = safe.substringAfterLast('.', "png").lowercase()
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/png"
                val uniq = if (safe.contains('.')) {
                    val stem = safe.substringBeforeLast('.')
                    "$stem-${System.currentTimeMillis()}.$ext"
                } else {
                    "$safe-${System.currentTimeMillis()}.$ext"
                }
                val vals = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, uniq)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, vals)
                    ?: throw IllegalStateException("无法创建下载项")
                contentResolver.openOutputStream(uri)?.use { os -> src.inputStream().use { it.copyTo(os) } }
                    ?: throw IllegalStateException("无法写入文件")
                vals.clear()
                vals.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, vals, null, null)
                src.delete()
                runOnUiThread { Toast.makeText(this@MainActivity, "已保存到下载/$uniq", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                Log.w(TAG, "saveImageCommit failed: ${e.message}")
                runOnUiThread { Toast.makeText(this@MainActivity, "保存失败：${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /* ── 静默寻址（真机换址无需手工改址，也不发通知）─────────────────────────
     * 固定域名只是"路标"：连接前先用阿里云 DoH（国内直达）解析 CNAME/TXT，
     * 命中 *.trycloudflare.com 就改用该当前隧道地址加载；解析不到时回退
     * 上次成功地址，再不行才用原地址。换址、重连全程自动。
     */
    private var mainFrameRetryCount = 0

    private fun configuredRemoteUrl(): String =
        getPreferences(MODE_PRIVATE).getString(KEY_REMOTE_URL, "").orEmpty()

    private fun loadWithDiscovery(configuredUrl: String, retryCount: Int = 0) {
        val uri = runCatching { Uri.parse(configuredUrl) }.getOrNull()
        val host = uri?.host
        if (uri == null || host.isNullOrBlank() || !isDiscoveryCandidate(host)) {
            browser.loadUrl(configuredUrl)
            return
        }
        Thread {
            val target = resolveTunnelHost(host)
            val fallback = if (target == null) {
                getPreferences(MODE_PRIVATE).getString(KEY_LAST_EFFECTIVE, "").orEmpty()
            } else ""
            runOnUiThread {
                val effective = when {
                    // 解析到当前隧道：主机与 relayOrigin 一并改写，页面后续请求才指向同一地址（r6）。
                    target != null -> rebuildWithResolvedHost(uri, target)
                    retryCount == 0 && fallback.isNotBlank() -> fallback
                    else -> configuredUrl
                }
                Log.i(TAG, "discovery: host=$host tunnel=$target retry=$retryCount -> $effective")
                browser.loadUrl(effective)
            }
        }.start()
    }

    /** 主机改写 + relayOrigin 同步改写：隧道换址后页面仍自洽（先清空 query 再按原参数重放）。 */
    private fun rebuildWithResolvedHost(uri: Uri, host: String): String {
        val origin = "https://$host"
        val rebuilt = uri.buildUpon().authority(host).clearQuery()
        for (key in uri.queryParameterNames) {
            if (key == "relayOrigin") continue
            for (value in uri.getQueryParameters(key)) rebuilt.appendQueryParameter(key, value)
        }
        rebuilt.appendQueryParameter("relayOrigin", origin)
        return rebuilt.build().toString()
    }

    private fun isDiscoveryCandidate(host: String): Boolean {
        if (host.equals("zcode.z.ai", true)) return true
        if (host.equals("localhost", true)) return false
        if (host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return false
        return true
    }

    /** AliDNS DoH 查询：返回记录数据里出现的 trycloudflare 主机名。 */
    private fun resolveTunnelHost(host: String): String? {
        for (type in listOf("CNAME", "TXT")) {
            try {
                val conn = URL("https://dns.alidns.com/resolve?name=$host&type=$type")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/dns-json")
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                conn.disconnect()
                val answer = org.json.JSONObject(body).optJSONArray("Answer") ?: continue
                for (i in 0 until answer.length()) {
                    val data = answer.optJSONObject(i)?.optString("data").orEmpty()
                    if (data.contains("trycloudflare.com")) {
                        return data.trim().replace("\"", "").split(" ")
                            .lastOrNull { it.contains("trycloudflare.com") }
                            ?.trimEnd('.')
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "doh $type failed: ${e.message}")
            }
        }
        return null
    }

    private companion object {
        const val TAG = "ZCodeRemote"
        const val KEY_REMOTE_URL = "remote_url"
        const val KEY_RESTORE_VERSION = "restore_version"
        const val KEY_LAST_EFFECTIVE = "last_effective_url"
        const val MAX_MAIN_FRAME_RETRY = 2
        const val FILE_CHOOSER_REQUEST = 4101
        /* Deep Link 打开会话：每次重试间隔 / 最大重试次数（约 18 秒内等页面就绪） */
        const val DEEPLINK_RETRY_MS = 700L
        const val DEEPLINK_MAX_TRIES = 26
        const val PATCH_JS_PATH = "/remote/v4/assets/__zp_patch__.js"
        const val PATCH_CSS_PATH = "/remote/v4/assets/__zp_patch__.css"
        /* 宿主无关（custom）：自有域名/隧道地址/LAN 与官方域名走同一条补丁+本地快照通路 */
        val MAIN_FRAME_PATTERN: Pattern =
            Pattern.compile("^https://[^/]+/(?:remote/v4|web-remote)(\\?|$)")
        /* 随 App 打包的官方页面快照（2026-09-26 当前线上版，与桥托管页面同代际） */
        const val SNAPSHOT_DIR = "remote_v4"
        const val SNAPSHOT_INDEX = "index-B-ilXaCQ.js"
        /* 钉住随包快照入口：true = 页面入口固定用本地快照；补丁另行注入 */
        const val PIN_OLD_PAGE = true
        /* 给入口加区分参数：避开 WebView 里可能缓存的旧 404，强制重新走拦截 */
        const val SNAPSHOT_INDEX_QUERY = "?zp=1"
        /*
         * 官方资源路径中间会插入一段（如 /remote/v4/latest/assets/… 或 /remote/v4/3.10.2/assets/…），
         * 这里通配该段，兼容无版本段的老路径。
         */
        val ASSET_PATH_PATTERN: Pattern =
            Pattern.compile("^https://[^/]+/remote/v4/(?:[^/]+/)?assets/([A-Za-z0-9_.-]+)(?:\\?.*)?$")
    }
}

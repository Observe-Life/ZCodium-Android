package app.zcodium.remote

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 首页齿轮进入的 Server酱³ 推送设置。 */
class PushSettingsActivity : Activity() {
    private lateinit var swEnabled: Switch
    private lateinit var etUid: EditText
    private lateinit var etSendKey: EditText
    private lateinit var swDone: Switch
    private lateinit var swError: Switch
    private lateinit var swAsk: Switch
    private lateinit var swOpenSession: Switch
    private lateinit var tvCurrent: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnTest: Button
    private var currentSessionId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        setContentView(buildContent())
        loadIntoUi()
    }

    private fun buildContent(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        page.addView(buildTopBar())
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#F4F4F5"))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        page.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            page.setPadding(bars.left, bars.top, bars.right, 0)
            body.setPadding(dp(14), dp(14), dp(14), dp(24) + bars.bottom)
            insets
        }

        body.addView(card("使用说明").addBody(
            "电脑端补丁负责发送正式通知，本页只用于手机端测试。\n" +
                "Server酱³收到通知后，可通过 scopen:// 按包名直接打开 ZCode。\n" +
                "请在 Server酱 App 的“应用拉起白名单”中加入：$ZCODE_PACKAGE"
        ))
        body.addView(buildSwitchCard())
        body.addView(buildServerChanCard())
        body.addView(buildEventCard())
        body.addView(buildTestCard())
        return page
    }

    private fun buildTopBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(6), dp(6), dp(14), dp(6))
        addView(ImageView(this@PushSettingsActivity).apply {
            setImageResource(R.drawable.ic_zp_back)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(TextView(this@PushSettingsActivity).apply {
            text = "推送通知设置"
            textSize = 17f
            setTextColor(Color.parseColor("#18181B"))
            setPadding(dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildSwitchCard(): LinearLayout {
        val c = card("推送总开关").addBody("关闭后本页测试不会发送通知；电脑端使用自己的配置。")
        swEnabled = rowSwitch(c, "启用 Server酱³ 推送")
        return c
    }

    private fun buildServerChanCard(): LinearLayout {
        val c = card("Server酱³ 配置").addBody(
            "在 Server酱³ 的 SendKey 页面获取 UID 和 SendKey。两项只保存在本机，不会显示在通知中。"
        )
        etUid = editField(c, "UID", "例如 uid.push.ft07.com 前面的 uid")
        etSendKey = editField(c, "SendKey", "填写你的 SendKey")
        return c
    }

    private fun buildEventCard(): LinearLayout {
        val c = card("事件与跳转")
        swDone = rowSwitch(c, "任务完成（【智能体】已完成）")
        swError = rowSwitch(c, "任务中断（【智能体】已中断）")
        swAsk = rowSwitch(c, "等待决策（【智能体】请决策）")
        swOpenSession = rowSwitch(c, "点击通知打开对应会话")
        c.addBody("手动停止属于正常操作，不发送通知。")
        return c
    }

    private fun buildTestCard(): LinearLayout {
        val c = card("测试与状态")
        tvCurrent = TextView(this).apply { textSize = 12.5f; setTextColor(MUTED_COLOR); setPadding(0, dp(8), 0, 0) }
        c.addView(tvCurrent)
        tvStatus = TextView(this).apply { textSize = 12.5f; setTextColor(MUTED_COLOR); setPadding(0, dp(6), 0, 0) }
        c.addView(tvStatus)
        btnTest = actionButton("保存并发送测试通知") { saveThenTest() }
        c.addView(actionRow(btnTest))
        c.addView(actionRow(actionButton("仅保存配置") { saveOnly() }, actionButton("清除本地凭据") { clearCreds() }))
        tvResult = TextView(this).apply {
            textSize = 12.5f; setTextColor(MUTED_COLOR); setPadding(0, dp(8), 0, 0)
            text = "最近一次发送结果：（尚未发送）"
        }
        c.addView(tvResult)
        return c
    }

    private fun loadIntoUi() {
        val cfg = PushConfig.load(this)
        swEnabled.isChecked = cfg.enabled
        etUid.setText(cfg.uid)
        etSendKey.setText(cfg.sendKey)
        swDone.isChecked = cfg.done
        swError.isChecked = cfg.error
        swAsk.isChecked = cfg.ask
        swOpenSession.isChecked = cfg.openSession
        listOf(swEnabled, swDone, swError, swAsk, swOpenSession).forEach {
            it.setOnCheckedChangeListener { _, _ -> refreshStatus() }
        }
        listOf(etUid, etSendKey).forEach { it.setOnFocusChangeListener { _, _ -> refreshStatus() } }
        refreshStatus()
    }

    private fun persist() {
        PushConfig.save(this, swEnabled.isChecked, etUid.text.toString(), etSendKey.text.toString(),
            swDone.isChecked, swError.isChecked, swAsk.isChecked, swOpenSession.isChecked)
    }

    private fun refreshStatus() {
        val cfg = PushConfig.load(this).copy(
            enabled = swEnabled.isChecked,
            uid = etUid.text.toString().trim(),
            sendKey = etSendKey.text.toString().trim()
        )
        tvCurrent.text = if (currentSessionId.isBlank()) {
            "当前会话：未在会话页，本次测试只测通知。"
        } else {
            "当前会话：$currentSessionId（测试通知会带上打开链接）"
        }
        val problem = PushConfig.validate(cfg)
        tvStatus.text = "UID：${PushConfig.mask(cfg.uid)}\nSendKey：${PushConfig.mask(cfg.sendKey)}\n" +
            if (problem == null) "状态：可以发送" else "状态：$problem"
        tvStatus.setTextColor(if (problem == null) OK_COLOR else MUTED_COLOR)
    }

    private fun saveOnly() { persist(); refreshStatus(); toast("配置已保存") }

    private fun clearCreds() {
        PushConfig.clear(this)
        loadIntoUi()
        tvResult.text = "最近一次发送结果：（凭据已清除）"
        toast("本地凭据已清除")
    }

    private fun saveThenTest() {
        persist()
        refreshStatus()
        val cfg = PushConfig.load(this)
        val problem = PushConfig.validate(cfg)
        if (problem != null) {
            tvResult.text = "最近一次发送结果：未发送 —— $problem"
            tvResult.setTextColor(WARN_COLOR)
            toast(problem)
            return
        }
        btnTest.isEnabled = false
        tvResult.setTextColor(MUTED_COLOR)
        tvResult.text = "最近一次发送结果：正在发送…"
        Thread {
            val r = PushConfig.sendTest(this, currentSessionId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnTest.isEnabled = true
                val link = currentSessionId.isNotBlank() && cfg.openSession
                tvResult.text = "最近一次发送结果：" + if (r.ok) "发送成功" else "发送失败" +
                    " —— ${r.detail}\n" + if (link) {
                        "已附带 Server酱 scopen:// 打开链接；请在手机上点通知验证。"
                    } else {
                        "本次未附带会话打开链接。"
                    }
                tvResult.setTextColor(if (r.ok) OK_COLOR else WARN_COLOR)
            }
        }.start()
    }

    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#E4E4E7"))
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        addView(TextView(this@PushSettingsActivity).apply { text = title; textSize = 15f; setTextColor(Color.parseColor("#18181B")) })
    }

    private fun LinearLayout.addBody(text: String): LinearLayout {
        addView(TextView(this@PushSettingsActivity).apply { this.text = text; textSize = 12.5f; setTextColor(MUTED_COLOR); setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(6), 0, 0) })
        return this
    }

    private fun rowSwitch(card: LinearLayout, label: String): Switch {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        row.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(Color.parseColor("#18181B")) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return Switch(this).also { row.addView(it); card.addView(row) }
    }

    private fun editField(card: LinearLayout, label: String, hint: String): EditText {
        card.addView(TextView(this).apply { text = label; textSize = 13.5f; setTextColor(Color.parseColor("#18181B")); setPadding(0, dp(10), 0, 0) })
        return EditText(this).apply {
            this.hint = hint; textSize = 13.5f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
            card.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
    }

    private fun actionRow(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0)
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) leftMargin = dp(8) }) }
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; textSize = 13.5f; isAllCaps = false; stateListAnimator = null; setTextColor(Color.parseColor("#18181B"))
        background = GradientDrawable().apply { setColor(Color.parseColor("#F4F4F5")); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.parseColor("#E4E4E7")) }
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val ZCODE_PACKAGE = "app.zcodium.remote"
        private const val TAG = "ZCodeRemote"
        private val OK_COLOR = Color.parseColor("#16A34A")
        private val WARN_COLOR = Color.parseColor("#DC2626")
        private val MUTED_COLOR = Color.parseColor("#52525B")
    }
}

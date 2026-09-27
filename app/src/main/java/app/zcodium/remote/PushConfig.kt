package app.zcodium.remote

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Server酱³ 推送配置与发送。
 *
 * 电脑端才是正式通知发送方；手机端配置只用于设置页发送测试消息。
 * SendKey 只存本应用私有 SharedPreferences，不写日志、不进入 URL 之外的展示文本。
 */
object PushConfig {
    private const val TAG = "ZCodeRemote"
    private const val PREFS = "serverchan_config"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_UID = "uid"
    private const val KEY_SEND_KEY = "send_key"
    private const val KEY_EV_DONE = "event_done"
    private const val KEY_EV_ERROR = "event_error"
    private const val KEY_EV_ASK = "event_ask"
    private const val KEY_OPEN_SESSION = "open_session_on_click"

    private const val PACKAGE_NAME = "app.zcodium.remote"

    data class Cfg(
        val enabled: Boolean,
        val uid: String,
        val sendKey: String,
        val done: Boolean,
        val error: Boolean,
        val ask: Boolean,
        val openSession: Boolean
    )

    data class Result(val ok: Boolean, val detail: String)

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): Cfg {
        val p = prefs(ctx)
        return Cfg(
            enabled = p.getBoolean(KEY_ENABLED, false),
            uid = p.getString(KEY_UID, "").orEmpty(),
            sendKey = p.getString(KEY_SEND_KEY, "").orEmpty(),
            done = p.getBoolean(KEY_EV_DONE, true),
            error = p.getBoolean(KEY_EV_ERROR, true),
            ask = p.getBoolean(KEY_EV_ASK, true),
            openSession = p.getBoolean(KEY_OPEN_SESSION, true)
        )
    }

    fun save(
        ctx: Context,
        enabled: Boolean,
        uid: String,
        sendKey: String,
        done: Boolean,
        error: Boolean,
        ask: Boolean,
        openSession: Boolean
    ) {
        prefs(ctx).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_UID, uid.trim())
            .putString(KEY_SEND_KEY, sendKey.trim())
            .putBoolean(KEY_EV_DONE, done)
            .putBoolean(KEY_EV_ERROR, error)
            .putBoolean(KEY_EV_ASK, ask)
            .putBoolean(KEY_OPEN_SESSION, openSession)
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    fun mask(value: String): String = when {
        value.isBlank() -> "（未填写）"
        value.length <= 8 -> "已填写（${value.length} 位）"
        else -> value.take(4) + "…" + value.takeLast(2)
    }

    fun validate(cfg: Cfg): String? = when {
        !cfg.enabled -> "推送总开关未打开"
        cfg.uid.isBlank() -> "请填写 Server酱 UID"
        cfg.sendKey.isBlank() -> "请填写 Server酱 SendKey"
        else -> null
    }

    /**
     * 字段分工（据客户端通知栏实测）：title=通知标题行，short=通知正文行，
     * desp 只在详情页显示，承担可点击的 scopen:// 链接。
     */
    fun buildPayload(cfg: Cfg, title: String, short: String, desp: String): Pair<String, JSONObject> {
        val endpoint = "https://${cfg.uid.trim()}.push.ft07.com/send/${cfg.sendKey.trim()}.send"
        val body = JSONObject()
            .put("title", title)
            .put("short", short)
            .put("desp", desp)
        return endpoint to body
    }

    /** 同步发送，必须在子线程调用。 */
    fun send(cfg: Cfg, title: String, short: String, desp: String): Result {
        val problem = validate(cfg)
        if (problem != null) return Result(false, problem)
        val (endpoint, body) = buildPayload(cfg, title, short, desp)
        return try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val httpCode = conn.responseCode
                val text = (if (httpCode in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                judge(httpCode, text)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "serverchan send failed: ${e.javaClass.simpleName}")
            Result(false, "请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun judge(httpCode: Int, text: String): Result {
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (httpCode !in 200..299) {
            return Result(false, "HTTP 失败 HTTP $httpCode")
        }
        if (obj == null) return Result(true, "Server酱已接受请求（HTTP $httpCode）")
        val code = obj.optInt("code", 0)
        val message = obj.optString("message", obj.optString("msg", ""))
        return if (code == 0) {
            Result(true, "Server酱发送成功${if (message.isBlank()) "" else "：$message"}")
        } else {
            Result(false, "Server酱返回失败 code=$code${if (message.isBlank()) "" else "：$message"}")
        }
    }

    fun sendTest(ctx: Context, sessionId: String): Result {
        val cfg = load(ctx)
        val title = "【智能体】测试通知"
        /* 与电脑端保持一致：标题只放状态，正文为“会话：会话名”且本身是可点击链接。 */
        val short = if (sessionId.isBlank()) "会话：测试会话" else "会话：$sessionId"
        val desp = if (sessionId.isBlank() || !cfg.openSession) {
            short
        } else {
            val sess = java.net.URLEncoder.encode(sessionId, "UTF-8").replace("+", "%20")
            "[$short](scopen://$PACKAGE_NAME?sess=$sess&event=test&sc.confirm=0)"
        }
        return send(cfg, title, short, desp)
    }
}

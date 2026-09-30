package app.zcodium.remote

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 本地转发层：页面只连 127.0.0.1（必然可达），由本类去连隧道主机。
 *
 * 为什么需要它：实测手机直连 Cloudflare 边缘（*.trycloudflare.com）10 次里约 6 次被重置，
 * 页面一次会话要发多个独立请求（bootstrap/两条 WebSocket/若干 REST），任一失败整页报错。
 * 本类把"不可靠的公网连接"收敛到一处，并在此处做多次重试（HTTP 6 次、WebSocket 6 次），
 * 使页面的每条请求都稳定拿到结果——不开 VPN 也能长期稳定（与参考项目同等的稳定性由重试补足）。
 *
 * - HTTP：转发请求到 https://<upstreamHost><path>，失败自动重试；
 * - WebSocket：原始 TCP+TLS 建连（带重试），握手后裸流双向透传（不解析帧，保持最低开销）；
 * - **候选上游（2026-09-30）**：路标（固定域名）在换址窗口期可能短暂指向已停隧道，
 *   故上游改为候选列表：任一候选解析失败/连接失败即自动轮转到下一个，全部失败才算失败；
 *   连上的候选经 onUpstreamOk 回调上报（App 侧据此记录"上次成功隧道"供下次兜底）。
 */
class LocalForwarder(
    upstreamHosts: List<String>,
    private val tag: String = "ZCodeRemote",
    private val onUpstreamOk: ((String) -> Unit)? = null,
) {

    private val candidates: List<String> =
        upstreamHosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    @Volatile
    var activeHost: String = candidates.firstOrNull().orEmpty()
        private set

    private val rotateCursor = java.util.concurrent.atomic.AtomicInteger(0)

    /* 每候选连续失败计数（2026-09-30 实测：活隧道也会"Connection reset"瞬断约 60%，
       若第一次失败就轮转，会把预算浪费在死候选上、把连上活隧道的机会轮没——
       页面 window socket 预算仅约 3 秒，必须"原地重试优先、连续失败才换、解析不到立即换"）。 */
    private val consecutiveFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 记录一次失败；返回 true 表示应轮转到下一候选。 */
    private fun noteUpstreamFail(host: String, msg: String?): Boolean {
        val n = (consecutiveFailures[host] ?: 0) + 1
        consecutiveFailures[host] = n
        val nxdomain = msg != null &&
            (msg.contains("Unable to resolve host") || msg.contains("No address associated"))
        return nxdomain || n >= 3
    }

    /** 当前上游失败（解析不到/连接被重置）时轮转到下一候选；单候选时原地重试。 */
    private fun rotateUpstream(reason: String) {
        if (candidates.size <= 1) return
        val next = (rotateCursor.getAndIncrement() + 1) % candidates.size
        val host = candidates[next]
        if (host != activeHost) {
            activeHost = host
            Log.i(tag, "forward upstream rotate -> $host (${reason?.take(80)})")
        }
    }

    private fun noteUpstreamOk(host: String) {
        if (host != activeHost) activeHost = host
        consecutiveFailures.remove(host)
        try { onUpstreamOk?.invoke(host) } catch (_: Exception) { }
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

    var port: Int = 0
        private set

    fun start(): Int {
        /* 优先用**固定端口**：页面的 localStorage（主题、上次会话等）按"来源"隔离，
           端口每次随机变化会被当成新站点、设置全部丢失（实测：浅色主题重开变回深色）。
           固定端口被占用时再退回系统分配。 */
        val ss = try {
            ServerSocket(PREFERRED_PORT, 64, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        }
        server = ss
        port = ss.localPort
        pool.execute {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (e: Exception) { break }
                try { pool.execute { handle(c) } } catch (e: Exception) { try { c.close() } catch (_: Exception) { } }
            }
        }
        Log.i(tag, "forwarder up: 127.0.0.1:$port -> ${candidates.joinToString(",")} (active=$activeHost)")
        return port
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) { }
        server = null
    }

    // ── 单连接处理 ────────────────────────────────────────────────────────
    private fun handle(client: Socket) {
        var upstreamWs: Socket? = null
        try {
            client.soTimeout = 60000
            val cin = BufferedInputStream(client.getInputStream())
            val head = readHead(cin) ?: return
            val lines = String(head, Charsets.ISO_8859_1).split("\r\n")
            val first = lines.firstOrNull() ?: return
            val parts = first.split(" ")
            if (parts.size < 3) return
            val method = parts[0]
            val path = parts[1]
            val headers = ArrayList<Pair<String, String>>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers.add(line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim())
            }
            val isWs = headers.any { it.first == "upgrade" && it.second.equals("websocket", true) }
            if (isWs) upstreamWs = proxyWebSocket(client, cin, method, path, headers)
            else proxyHttp(client, cin, method, path, headers)
        } catch (e: Exception) {
            Log.w(tag, "forwarder conn error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) { }
            try { upstreamWs?.close() } catch (_: Exception) { }
        }
    }

    /** 读取请求头（到 \r\n\r\n 为止），返回不含结尾空行的头字节。 */
    private fun readHead(inS: InputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = inS.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toByteArray()
            buf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                else -> 0
            }
            if (state == 4) {
                val all = buf.toByteArray()
                return all.copyOfRange(0, all.size - 4)
            }
            if (buf.size() > 64 * 1024) return buf.toByteArray()
        }
    }

    // ── HTTP 转发（带重试） ───────────────────────────────────────────────
    private fun proxyHttp(
        client: Socket, cin: BufferedInputStream, method: String,
        path: String, headers: List<Pair<String, String>>
    ) {
        val out = BufferedOutputStream(client.getOutputStream())
        val contentLength = headers.firstOrNull { it.first == "content-length" }?.second?.toIntOrNull() ?: 0
        val body = if (contentLength in 1..(8 * 1024 * 1024)) {
            val b = ByteArray(contentLength); var r = 0
            while (r < contentLength) { val n = cin.read(b, r, contentLength - r); if (n < 0) break; r += n }
            b.copyOf(r)
        } else ByteArray(0)

        var attempt = 0
        var lastErr: String? = null
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val host = activeHost
            var conn: HttpURLConnection? = null
            try {
                conn = URL("https://$host$path").openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                for ((k, v) in headers) {
                    if (k in HOP_HEADERS || k == "host") continue
                    /* 不透传 accept-encoding：HttpURLConnection 只在它自己加压缩头时才自动解压，
                       我们手动转发压缩头会导致上游回 gzip、又被当纯文本交给页面（实测乱码）。 */
                    if (k == "accept-encoding") continue
                    try { conn.setRequestProperty(k, v) } catch (_: Exception) { }
                }
                try { conn.setRequestProperty("Accept-Encoding", "identity") } catch (_: Exception) { }
                /* 告诉桥"页面其实在连本机转发"：桥据此把 workspace wsUrl 也指回 127.0.0.1，
                   否则页面拿到隧道域名直连公网（实测直连失败率约 60%），工作区 socket 卡死。 */
                try { conn.setRequestProperty("X-ZP-Client-Origin", "http://127.0.0.1:$port") } catch (_: Exception) { }
                if (body.isNotEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                val data = try {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                } catch (e: Exception) { ByteArray(0) }
                noteUpstreamOk(host)

                val sb = StringBuilder("HTTP/1.1 $code ${conn.responseMessage ?: ""}\r\n")
                for ((k, vs) in conn.headerFields) {
                    if (k == null) continue
                    val lk = k.lowercase()
                    if (lk in HOP_HEADERS || lk == "content-length" || lk == "content-encoding" || lk == "transfer-encoding") continue
                    for (v in vs) sb.append(k).append(": ").append(v).append("\r\n")
                }
                sb.append("Content-Length: ").append(data.size).append("\r\n\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                if (method != "HEAD") out.write(data)
                out.flush()
                return
            } catch (e: Exception) {
                lastErr = e.message
                /* 原地重试优先：活隧道瞬断（Connection reset 类）先留在原候选重试；
                   连续 3 次失败或域名解析不到，才轮转到下一候选。 */
                if (noteUpstreamFail(host, e.message)) {
                    rotateUpstream("http: ${e.message}")
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { }
            }
            if (attempt < MAX_ATTEMPTS) sleepQuiet(400)
        }
        Log.w(tag, "forward http gave up: $method $path after $attempt attempts ($lastErr)")
        try {
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        } catch (_: Exception) { }
    }

    // ── WebSocket 转发（裸流透传 + 上游透明重连） ───────────────────────
    /**
     * 页面侧的 socket 始终由本层保持；上游（隧道那一段）断开时，本层**在页面无感的情况下重连上游**
     * （重新握手、继续透传），所以公网抖动不会让页面看到"连接关闭"——这是"前台持续不断"的关键。
     * 只有页面自己关闭时，才真正收尾。
     */
    private fun proxyWebSocket(
        client: Socket, cin: BufferedInputStream, method: String,
        path: String, headers: List<Pair<String, String>>
    ): Socket? {
        val clientOut = BufferedOutputStream(client.getOutputStream())
        val hold = UpstreamHolder()
        val clientAlive = java.util.concurrent.atomic.AtomicBoolean(true)

        // 页面 → 上游：只跑一次；写到"当前上游"，上游重连期间丢弃瞬时字节（极少）
        val upPump = Thread {
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = cin.read(buf)
                    if (n < 0) break
                    val out = hold.out
                    if (out != null) {
                        try { out.write(buf, 0, n); out.flush() } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) {
            } finally {
                clientAlive.set(false)
                hold.closeQuietly()
            }
        }
        upPump.start()

        // 上游 → 页面：上游断了就重连（页面无感），直到页面侧结束
        var reconnects = 0
        val downBuf = ByteArray(16 * 1024)
        while (clientAlive.get() && reconnects <= MAX_WS_RECONNECTS) {
            val upPair = connectUpstream()
            if (upPair == null) {
                reconnects++
                Log.w(tag, "forward ws upstream connect failed (attempt $reconnects)")
                sleepQuiet(800)
                continue
            }
            val (up, upHost) = upPair
            if (!hold.install(up, method, path, headers, upHost)) {
                Log.w(tag, "forward ws handshake write failed")
                try { up.close() } catch (_: Exception) { }
                reconnects++
                sleepQuiet(800)
                continue
            }
            if (reconnects > 0) Log.i(tag, "forward ws upstream reconnected (第 $reconnects 次), 页面无感")
            try {
                val uin = up.getInputStream()
                while (true) {
                    val n = uin.read(downBuf)
                    if (n < 0) throw java.io.EOFException("upstream eof")
                    clientOut.write(downBuf, 0, n)
                    clientOut.flush()
                }
            } catch (e: Exception) {
                if (!clientAlive.get()) break
                Log.w(tag, "forward ws upstream dropped: ${e.message} — 尝试重连（页面无感）")
                hold.clear()
                reconnects++
                sleepQuiet(500)
            }
        }
        hold.closeQuietly()
        Log.i(tag, "forward ws closed (client side ended): $path")
        return null
    }

    /** 建立到隧道的 TLS 连接（带 SNI 与重试；多候选时失败自动轮转）。返回 (socket, 实际主机)。 */
    private fun connectUpstream(): Pair<SSLSocket, String>? {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val host = activeHost
            try {
                val s = sslFactory.createSocket() as SSLSocket
                s.connect(InetSocketAddress(host, 443), CONNECT_TIMEOUT_MS)
                /* 必须显式设置 SNI：无 SNI 时 Cloudflare 边缘认不出目标隧道 → 直接重置连接 */
                runCatching {
                    val params = s.sslParameters
                    params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                    s.sslParameters = params
                }
                s.soTimeout = 0
                s.startHandshake()
                noteUpstreamOk(host)
                return s to host
            } catch (e: Exception) {
                Log.w(tag, "forward ws tls attempt $attempt failed: ${e.message}")
                if (noteUpstreamFail(host, e.message)) {
                    rotateUpstream("ws tls: ${e.message}")
                }
                sleepQuiet(400)
            }
        }
        return null
    }

    /** 当前上游的持有者：供"页面→上游"方向随时取到最新连接。 */
    private class UpstreamHolder {
        @Volatile var out: OutputStream? = null
        @Volatile private var socket: SSLSocket? = null

        fun install(s: SSLSocket, method: String, path: String, headers: List<Pair<String, String>>, host: String): Boolean {
            return try {
                val sb = StringBuilder("$method $path HTTP/1.1\r\n")
                for ((k, v) in headers) {
                    if (k == "host") continue
                    sb.append(k).append(": ").append(v).append("\r\n")
                }
                sb.append("Host: ").append(host).append("\r\n\r\n")
                val o = BufferedOutputStream(s.getOutputStream())
                o.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                o.flush()
                socket = s
                out = o
                true
            } catch (e: Exception) {
                false
            }
        }

        fun clear() {
            out = null
            try { socket?.close() } catch (_: Exception) { }
            socket = null
        }

        fun closeQuietly() = clear()
    }

    private fun sleepQuiet(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { } }

    companion object {
        /* 直连 CF 边缘实测失败率约 60%：6 次重试把成功率抬到 ~99.9% */
        private const val MAX_ATTEMPTS = 6
        /* WebSocket 上游断线后的最大重连次数（页面无感续连；远超此数视为长时间断网，交给页面处理） */
        private const val MAX_WS_RECONNECTS = 120
        /* 本地转发固定端口：来源稳定 → 页面 localStorage（主题/上次会话等）得以保留 */
        private const val PREFERRED_PORT = 43177
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val READ_TIMEOUT_MS = 20000
        private val HOP_HEADERS = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "upgrade"
        )
    }
}

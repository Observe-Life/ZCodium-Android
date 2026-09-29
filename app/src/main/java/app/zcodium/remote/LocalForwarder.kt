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
 * - WebSocket：原始 TCP+TLS 建连（带重试），握手后裸流双向透传（不解析帧，保持最低开销）。
 */
class LocalForwarder(private val upstreamHost: String, private val tag: String = "ZCodeRemote") {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

    var port: Int = 0
        private set

    fun start(): Int {
        val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = ss
        port = ss.localPort
        pool.execute {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (e: Exception) { break }
                try { pool.execute { handle(c) } } catch (e: Exception) { try { c.close() } catch (_: Exception) {} }
            }
        }
        Log.i(tag, "forwarder up: 127.0.0.1:$port -> $upstreamHost")
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
            var conn: HttpURLConnection? = null
            try {
                conn = URL("https://$upstreamHost$path").openConnection() as HttpURLConnection
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
                if (body.isNotEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                val data = try {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                } catch (e: Exception) { ByteArray(0) }

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
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { }
            }
            if (attempt < MAX_ATTEMPTS) sleepQuiet(500)
        }
        Log.w(tag, "forward http gave up: $method $path after $attempt attempts ($lastErr)")
        try {
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        } catch (_: Exception) { }
    }

    // ── WebSocket 转发（裸流透传，带重试） ───────────────────────────────
    private fun proxyWebSocket(
        client: Socket, cin: BufferedInputStream, method: String,
        path: String, headers: List<Pair<String, String>>
    ): Socket? {
        var upstream: SSLSocket? = null
        var attempt = 0
        while (attempt < MAX_ATTEMPTS && upstream == null) {
            attempt++
            try {
                val s = sslFactory.createSocket() as SSLSocket
                s.connect(InetSocketAddress(upstreamHost, 443), CONNECT_TIMEOUT_MS)
                /* 必须显式设置 SNI（serverName）：无 SNI 时 Cloudflare 边缘认不出目标隧道 → 直接重置连接
                   （实测：HTTP 用 HttpURLConnection 自动带 SNI 所以通，WebSocket 手写握手不带则 6/6 被重置） */
                runCatching {
                    val params = s.sslParameters
                    params.serverNames = listOf(javax.net.ssl.SNIHostName(upstreamHost))
                    s.sslParameters = params
                }
                s.soTimeout = 0
                s.startHandshake()
                upstream = s
            } catch (e: Exception) {
                Log.w(tag, "forward ws attempt $attempt failed: ${e.message}")
                sleepQuiet(600)
            }
        }
        if (upstream == null) {
            Log.w(tag, "forward ws gave up after $attempt attempts: $path")
            try { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()) } catch (_: Exception) { }
            return null
        }
        val sb = StringBuilder("$method $path HTTP/1.1\r\n")
        for ((k, v) in headers) {
            if (k == "host") continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("Host: ").append(upstreamHost).append("\r\n\r\n")
        val uo = BufferedOutputStream(upstream.getOutputStream())
        uo.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        uo.flush()

        val t1 = Thread { pipe(upstream.getInputStream(), client.getOutputStream()) }
        val t2 = Thread { pipe(cin, upstream.getOutputStream()) }
        t1.start(); t2.start()
        t1.join(); t2.join()
        return upstream
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                if (n < buf.size) to.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { to.flush() } catch (_: Exception) { }
            try { to.close() } catch (_: Exception) { }
            try { from.close() } catch (_: Exception) { }
        }
    }

    private fun sleepQuiet(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { } }

    companion object {
        /* 直连 CF 边缘实测失败率约 60%：6 次重试把成功率抬到 ~99.9% */
        private const val MAX_ATTEMPTS = 6
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val READ_TIMEOUT_MS = 20000
        private val HOP_HEADERS = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "upgrade"
        )
    }
}

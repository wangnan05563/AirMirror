package io.github.wangnan05563.airmirror.web

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * 访客浏览器投屏服务（PRD §5.9 / §6.6：接收端内置网页服务，上传文件、投送浏览器标签页）。
 *
 * 零安装的代价是「谁都能访问」，所以入口不是裸路径，而是一次性会话 token：
 * 大屏上显示带 token 的地址，拿到地址的人才能上传；token 每次进程启动重新生成，不落盘，
 * 最多使用 5 分钟后失效并自动换新（REQ-010）。
 *
 * 本轮只做「文件上传 + 大屏播放」这条降级路径。浏览器标签页投送需要 WebRTC，
 * 在 MediaProjection + 自定义信令之外还要一套 SDP/ICE 协商，不是这一片能闭环的量；
 * 网页会明确提示「当前浏览器不支持时请上传文件」，与 PRD 543 的降级口径一致。
 *
 * 上传文件只落临时缓存，服务停止即删除；不解析内容、不外发。大小上限由接收端等级决定，默认 200MB。
 */
class BrowserCastServer(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val onUpload: (UploadedFile) -> Unit,
    /** 测试可注入时钟；生产用系统时间。 */
    private val now: () -> Long = { System.currentTimeMillis() },
    private val token: String = newToken(),
    /** 访客 token 的最长可用时间：REQ-010 要求 5 分钟失效，到期后 URL 自动换新。 */
    private val tokenTtlMillis: Long = DEFAULT_TOKEN_TTL_MS,
    /** 页面标题行显示的大屏名；用取值函数，改名后不必重建服务也能刷新。 */
    private val deviceName: () -> String = { DEFAULT_DEVICE_NAME },
    /** T06114：访问控制非「每次确认」时，浏览器 token 不能成为绕过 PIN/白名单的第二条路。 */
    private val allowed: () -> Boolean = { true },
    /**
     * 读请求头与读文件体的超时。这个服务对局域网敞开，谁都能连上；
     * 没有超时的话，一个连上就不发字节的客户端会把处理线程永久挂住——
     * 每个连接一条线程，几千个空连接就足以把接收端拖垮。
     */
    private val headTimeoutMs: Int = DEFAULT_HEAD_TIMEOUT_MS,
    private val bodyTimeoutMs: Int = DEFAULT_BODY_TIMEOUT_MS,
) {
    data class UploadedFile(val file: File, val name: String, val mime: String, val size: Long)

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var activeToken: String = token
    @Volatile private var tokenIssuedAt: Long = now()

    /** 活动连接：stop 时要当场关掉，否则上传线程能继续把文件写回已清空的目录 */
    private val clients: MutableSet<Socket> = java.util.Collections.synchronizedSet(HashSet())

    val port: Int get() = server?.localPort ?: 0

    fun url(hostAddress: String): String = "http://$hostAddress:$port/w/${currentToken()}/"

    @Synchronized
    fun start(): BrowserCastServer {
        if (server != null) return this
        tokenIssuedAt = now()
        activeToken = token
        dir.mkdirs()
        // 进程重启后旧文件没有会话可归属，先清掉；上传中点停止同样走这里
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val socket = ServerSocket(0)
        server = socket
        acceptThread = thread(name = "airmirror-web-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    // 一次 accept 失败不该结束线程：地址还挂在待连接页上，线程一退，
                    // 之后每次上传都只会「连不上」，而界面看不出任何异常。
                    if (socket.isClosed) break
                    // 连续失败时不空转，给系统一点时间回收句柄
                    runCatching { Thread.sleep(ACCEPT_RETRY_MS) }
                    continue
                }
                // 每个连接一条线程：只加读超时挡不住「开几千个连接、每个慢慢发」，
                // 线程与栈内存会先被占满。与接收端私有通道同一口径，超出的当场断开、连线程都不起。
                // 浏览器投屏同时只会有几台设备，8 条足够。
                if (clients.size >= MAX_CLIENTS) {
                    runCatching { client.close() }
                    continue
                }
                clients.add(client)
                thread(name = "airmirror-web-client", isDaemon = true) {
                    runCatching { serve(client) }
                    runCatching { client.close() }
                    clients.remove(client)
                }
            }
        }
        return this
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        // 先断活动连接再清文件：否则正在上传的线程会把文件重新写回目录，
        // 或者白占一条线程到 body 超时才退出。synchronizedSet 的迭代必须自己持锁，
        // 否则上传线程同时 remove 会抛 ConcurrentModificationException。
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
        synchronized(clients) { clients.clear() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun serve(client: Socket) {
        // 先定读超时再读头：空连接（连上不发任何字节）是最省事的占线程方式，必须自己到期释放
        client.soTimeout = headTimeoutMs
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        if (!allowed()) return respond(client, 404, MIME_TEXT_PLAIN, "not found")
        if (!tokenMatches(path)) return respond(client, 404, MIME_TEXT_PLAIN, "not found")
        when {
            method == "GET" && path == pagePath -> respond(client, 200, "text/html; charset=utf-8", page())
            method == "POST" && path == uploadPath -> handleUpload(client, input, lines)
            else -> respond(client, 404, MIME_TEXT_PLAIN, "not found")
        }
    }

    private fun handleUpload(client: Socket, input: InputStream, lines: List<String>) {
        // 文件体按需放宽到 bodyTimeoutMs：200MB 的大文件要慢慢传，但也不能让慢速客户端无限占着线程
        client.soTimeout = bodyTimeoutMs
        val length = lines.drop(1)
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toLongOrNull()
        if (length == null || length <= 0) {
            return respond(client, 411, MIME_TEXT_PLAIN, "missing content length")
        }
        if (length > maxBytes) {
            return respond(client, 413, MIME_TEXT_PLAIN, "file too large")
        }
        val rawName = lines.drop(1)
            .firstOrNull { it.startsWith("X-File-Name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim().orEmpty()
        val name = safeName(runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName))
        val mime = lines.drop(1)
            .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
            ?.substringAfter(':')?.trim().orEmpty()
            .ifBlank { "application/octet-stream" }
        val file = File(dir, "${System.currentTimeMillis()}-$name")
        var copied = 0L
        try {
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (copied < length) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - copied).toInt())
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                }
            }
            if (copied != length) throw IOException("truncated upload")
            onUpload(UploadedFile(file, name, mime, copied))
            // 只保留刚上传的这一份：播放器已拿到上一份的句柄，unlink 不影响正在播的画面，
            // 但能避免「每次上传都留一份」把接收端缓存撑爆（PRD 1266 的容量上限本意）。
            dir.listFiles()?.filter { it != file }?.forEach { runCatching { it.delete() } }
            respond(client, 200, "application/json; charset=utf-8", "{\"ok\":true}")
        } catch (e: Exception) {
            runCatching { file.delete() }
            respond(client, 400, MIME_TEXT_PLAIN, "upload failed")
        }
    }

    private fun respond(client: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            client.getOutputStream().use { out ->
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val builder = StringBuilder()
        var matched = 0
        while (matched < 4) {
            val value = input.read()
            if (value < 0) return null
            builder.append(value.toChar())
            if (builder.length > MAX_HEAD_BYTES) return null
            matched = when {
                value == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                value == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }
        }
        return builder.toString()
    }

    private fun tokenMatches(path: String): Boolean {
        val segment = path.removePrefix("/w/").substringBefore('/')
        return MessageDigest.isEqual(segment.toByteArray(Charsets.US_ASCII), currentToken().toByteArray(Charsets.US_ASCII))
    }

    /**
     * 到期就换一枚新 token。旧 URL 从到期那一刻起直接 404，不设宽限；
     * 待连接页每 500ms 重绘，[url] 会拿到新地址并重画二维码。
     */
    private fun currentToken(): String {
        val t = now()
        if (t - tokenIssuedAt < tokenTtlMillis) return activeToken
        synchronized(this) {
            if (t - tokenIssuedAt >= tokenTtlMillis) {
                activeToken = newToken()
                tokenIssuedAt = t
            }
            return activeToken
        }
    }

    private fun page(): String = GuestPage.html(deviceName(), uploadPath)

    private val pagePath: String get() = "/w/${currentToken()}/"
    private val uploadPath: String get() = "/w/${currentToken()}/upload"

    companion object {
        /** 纯文本应答的 Content-Type：错误路径与 404 都走同一份，避免编码声明写错。 */
        private const val MIME_TEXT_PLAIN = "text/plain; charset=utf-8"

        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
        private const val MAX_HEAD_BYTES = 16 * 1024

        /** REQ-010：访客 token 5 分钟失效，到期由 [url] 自动换新。 */
        const val DEFAULT_TOKEN_TTL_MS = 5 * 60_000L

        /** 没有设备名时的页面标题，避免标题行空白。 */
        const val DEFAULT_DEVICE_NAME = "大屏"

        /** accept 连续失败时的退避：既不空转，也不至于让下一次上传等太久 */
        private const val ACCEPT_RETRY_MS = 50L

        /** 同时在处理的连接上限，与 `ReceiverSession.MAX_CLIENTS` 同一防护目的（见 accept 循环里的注释） */
        const val MAX_CLIENTS = 8

        /** 请求头必须在这个时间内读完：正常浏览器几百毫秒就发完了 */
        const val DEFAULT_HEAD_TIMEOUT_MS = 10_000

        /**
         * 文件体的读超时。它约束的是「连续多久收不到字节」，不是整个上传的总时长：
         * 大文件在正常网速下每次 read 都能拿到数据，只有卡死或恶意慢速连接才会撞上这个上限。
         */
        const val DEFAULT_BODY_TIMEOUT_MS = 60_000

        fun newToken(): String {
            val bytes = ByteArray(4)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** 只保留文件名本身；路径分隔符与点开头一律清掉，避免写到缓存目录之外。 */
        fun safeName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            val cleaned = base.filter { it.isLetterOrDigit() || it in "._-" }.take(80)
            val name = cleaned.trimStart('.', '-', '_')
            if (name.isEmpty()) return "upload.bin"
            return if ('.' in name) name else name + ".bin"
        }

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            else -> "Error"
        }
    }
}

package io.github.wangnan05563.airmirror.web

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 访客浏览器投屏服务（PRD §5.9 / §6.6：接收端内置网页服务，上传文件、共享标签页）。
 *
 * 零安装的代价是「谁都能访问」，所以入口不是裸路径，而是一次性会话 token：
 * 大屏上显示带 token 的地址，拿到地址的人才能上传；token 每次进程启动重新生成，不落盘。
 *
 * 本轮只做「文件上传 + 大屏播放」这条降级路径。浏览器标签页共享需要 WebRTC，
 * 在 MediaProjection + 自定义信令之外还要一套 SDP/ICE 协商，不是这一片能闭环的量；
 * 网页会明确提示「当前浏览器不支持时请上传文件」，与 PRD 543 的降级口径一致。
 *
 * 上传文件只落临时缓存，服务停止即删除；不解析内容、不外发。大小上限由接收端等级决定，默认 200MB。
 */
class BrowserCastServer(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val onUpload: (UploadedFile) -> Unit,
    private val token: String = newToken(),
) {
    data class UploadedFile(val file: File, val name: String, val mime: String, val size: Long)

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    val port: Int get() = server?.localPort ?: 0

    fun url(hostAddress: String): String = "http://$hostAddress:$port/w/$token/"

    @Synchronized
    fun start(): BrowserCastServer {
        if (server != null) return this
        dir.mkdirs()
        // 进程重启后旧文件没有会话可归属，先清掉；上传中点停止同样走这里
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val socket = ServerSocket(0)
        server = socket
        acceptThread = thread(name = "airmirror-web-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "airmirror-web-client", isDaemon = true) {
                    runCatching { serve(client) }
                    runCatching { client.close() }
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
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        if (!tokenMatches(path)) return respond(client, 404, "text/plain; charset=utf-8", "not found")
        when {
            method == "GET" && path == pagePath -> respond(client, 200, "text/html; charset=utf-8", page())
            method == "POST" && path == uploadPath -> handleUpload(client, input, lines)
            else -> respond(client, 404, "text/plain; charset=utf-8", "not found")
        }
    }

    private fun handleUpload(client: Socket, input: InputStream, lines: List<String>) {
        val length = lines.drop(1)
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toLongOrNull()
        if (length == null || length <= 0) {
            return respond(client, 411, "text/plain; charset=utf-8", "missing content length")
        }
        if (length > maxBytes) {
            return respond(client, 413, "text/plain; charset=utf-8", "file too large")
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
            respond(client, 400, "text/plain; charset=utf-8", "upload failed")
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
        return MessageDigest.isEqual(segment.toByteArray(Charsets.US_ASCII), token.toByteArray(Charsets.US_ASCII))
    }

    private fun page(): String = """
        <!doctype html>
        <html lang="zh-CN"><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>投到大屏</title>
        <style>
          body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;margin:0;background:#0b1f3a;color:#f2f6fb}
          main{max-width:560px;margin:0 auto;padding:32px 20px}
          h1{font-size:24px;margin:0 0 8px}
          p{color:#a9b8cc;line-height:1.6}
          .card{background:#12314f;border-radius:14px;padding:20px;margin-top:20px}
          input[type=file]{width:100%;padding:12px;background:#0b1f3a;color:#f2f6fb;border:1px solid #2a4a6b;border-radius:10px}
          button{margin-top:16px;width:100%;padding:14px;border:0;border-radius:10px;background:#4da3ff;color:#04101f;font-size:16px;font-weight:600}
          button:disabled{opacity:.55}
          #state{margin-top:14px;color:#a9b8cc;font-size:14px}
          .hint{font-size:13px;color:#8fa2ba;margin-top:18px}
        </style></head>
        <body><main>
          <h1>投到大屏</h1>
          <p>选择手机或电脑里的视频、音频或图片，上传后会直接在电视上播放。不需要安装任何 App。</p>
          <div class="card">
            <input id="file" type="file" accept="video/*,audio/*,image/*">
            <button id="send" type="button">发送到大屏</button>
            <div id="state"></div>
          </div>
          <p class="hint">屏幕共享/标签页共享需要浏览器支持 WebRTC；当前版本请使用文件上传。</p>
        </main>
        <script>
          const input=document.getElementById('file');
          const button=document.getElementById('send');
          const state=document.getElementById('state');
          button.onclick=function(){
            const file=input.files[0];
            if(!file){state.textContent='请先选择一个文件';return;}
            button.disabled=true;state.textContent='正在上传 0%';
            const xhr=new XMLHttpRequest();
            xhr.open('POST','${uploadPath}');
            xhr.setRequestHeader('X-File-Name',encodeURIComponent(file.name));
            xhr.setRequestHeader('Content-Type',file.type||'application/octet-stream');
            xhr.upload.onprogress=function(e){if(e.lengthComputable){state.textContent='正在上传 '+Math.round(e.loaded*100/e.total)+'%';}};
            xhr.onload=function(){button.disabled=false;state.textContent=xhr.status===200?'已发送，电视正在播放':'上传失败（'+xhr.status+'）';};
            xhr.onerror=function(){button.disabled=false;state.textContent='上传失败，请检查是否还在同一网络';};
            xhr.send(file);
          };
        </script>
        </body></html>
    """.trimIndent()

    private val pagePath: String get() = "/w/$token/"
    private val uploadPath: String get() = "/w/$token/upload"

    companion object {
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
        private const val MAX_HEAD_BYTES = 16 * 1024

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

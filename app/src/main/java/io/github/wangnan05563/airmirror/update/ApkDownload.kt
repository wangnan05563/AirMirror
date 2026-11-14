package io.github.wangnan05563.airmirror.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * APK 下载（PRD 通用需求「在线更新」的第二步）。
 *
 * 先写临时文件再改名：安装器只看最终文件，半截下载必须不可能被误装。
 * Content-Length 与实际字节数不一致即判失败——比装上去闪退更容易解释。
 */
object ApkDownload {

    /** 只保留 APK 文件名本身：服务端返回的 name 不可信，带路径分隔符会逃出缓存目录。 */
    fun safeName(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = name.filter { it.isLetterOrDigit() || it in "._-" }
        return if (cleaned.endsWith(".apk", ignoreCase = true) && cleaned.length > 4) cleaned else "airmirror-update.apk"
    }

    fun download(
        url: String,
        target: File,
        timeoutMs: Int = 15_000,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "AirMirror-Android")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("http $code")
            // minSdk 19：getContentLengthLong 是 API 24，用 getContentLength 并自行放宽到 Long
            val total = connection.contentLength.toLong()
            var copied = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, total)
                    }
                }
            }
            if (copied <= 0) throw IOException("empty body")
            if (total > 0 && copied != total) throw IOException("truncated: $copied/$total")
            // 旧包删不掉就当场失败：renameTo 也会失败，早一步给出同样可解释的错误
            if (target.exists() && !target.delete()) throw IOException("cannot replace existing download")
            if (!temp.renameTo(target)) throw IOException("cannot rename download")
            return target
        } finally {
            runCatching { connection.disconnect() }
            if (temp.exists()) runCatching { temp.delete() }
        }
    }
}

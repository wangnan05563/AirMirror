package io.github.wangnan05563.airmirror.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.wangnan05563.airmirror.privacy.Redactor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * 图片推送用的取图器（PRD FS-02 媒体类型 image）。
 *
 * 不引第三方图片库：项目没有 AndroidX，Glide/Coil 都带进来一大坨。
 * 也不需要磁盘或内存缓存——图片只在当前会话显示，换源就要立刻换画面。
 */
object ImageFetcher {

    /** 大屏多是 1080p；按长边缩到 1920 以内，既够清晰又不会因 4000×3000 的原图解到 OOM。 */
    private const val MAX_EDGE = 1920

    /** 单张图上限：超过这个体积的「图片」多半是地址给错了，与其慢慢下完不如当场报失败。 */
    private const val MAX_BYTES = 16 * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 返回 null 表示这张图取不回来（地址失效、不是图片、超限）；日志里不写地址。 */
    fun fetch(uri: String, maxEdge: Int = MAX_EDGE): Bitmap? = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> fetchHttp(uri, maxEdge)
        uri.startsWith("file://") -> fetchFile(uri.removePrefix("file://"), maxEdge)
        else -> null
    }

    private fun fetchHttp(uri: String, maxEdge: Int): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URI(uri).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) return null
            readLimited(connection.inputStream)?.let { decode(it, maxEdge) }
        } catch (e: Exception) {
            Log.w(TAG, "image fetch failed: ${Redactor.throwable(e)}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchFile(path: String, maxEdge: Int): Bitmap? = try {
        val file = File(path)
        if (!file.isFile || file.length() > MAX_BYTES) null
        else FileInputStream(file).use { decode(readLimited(it) ?: return null, maxEdge) }
    } catch (e: Exception) {
        Log.w(TAG, "image file failed: ${Redactor.throwable(e)}")
        null
    }

    /** 先读进内存再按 bounds 算采样率：HTTP 流只能顺序读一次，避免为了量尺寸再发一次请求。 */
    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

    private const val TAG = "AirMirrorImageFetcher"
}

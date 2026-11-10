package io.github.wangnan05563.airmirror.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/** 待连接页二维码：本地生成，不走任何网络。 */
object Qr {
    private const val MAX_CACHE = 4

    private val cache = object : LinkedHashMap<String, Bitmap>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > MAX_CACHE
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "qr-gen").apply { isDaemon = true }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun bitmap(text: String, sizePx: Int, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        var index = 0
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[index++] = if (matrix.get(x, y)) dark else light
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    fun cacheKey(text: String, sizePx: Int, dark: Int, light: Int): String = "$text|$sizePx|$dark|$light"

    fun cached(text: String, sizePx: Int, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap {
        val key = cacheKey(text, sizePx, dark, light)
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val bmp = bitmap(text, sizePx, dark, light)
        synchronized(cache) { cache[key] = bmp }
        return bmp
    }

    /** 后台生成 + 缓存，完成后回主线程设图；调用方不阻塞。 */
    fun into(view: ImageView, text: String, sizePx: Int, dark: Int = Color.BLACK, light: Int = Color.WHITE) {
        val key = cacheKey(text, sizePx, dark, light)
        synchronized(cache) {
            cache[key]?.let {
                view.setImageBitmap(it)
                return
            }
        }
        val tag = key
        view.tag = tag
        executor.execute {
            val bmp = bitmap(text, sizePx, dark, light)
            synchronized(cache) { cache[key] = bmp }
            mainHandler.post {
                if (view.tag == tag) view.setImageBitmap(bmp)
            }
        }
    }

    fun evict() {
        synchronized(cache) { cache.clear() }
    }
}

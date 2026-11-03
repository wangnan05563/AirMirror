package io.github.wangnan05563.airmirror.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 待连接页二维码：本地生成，不走任何网络。 */
object Qr {
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
}

package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File

/**
 * T06105 扫码入口：不申请 CAMERA 权限，调用系统相机拍一张，再在本地用 ZXing core 解码。
 *
 * 这样做有两个现实原因：
 * 1. 本仓库只有 ZXing 编码端（待连接页出码），没有相机预览/帧分析依赖；系统相机是各机型都有的稳定入口；
 * 2. 不拿 CAMERA 权限就不必在隐私说明里新增一项“相机”，与 PRD 的最小权限口径一致。
 *
 * 拍到的照片只落本应用 cache，解码完立即删除；失败/取消不保留任何图像。
 */
object QrPhotoScan {

    const val MAX_SIDE = 1600
    const val PROVIDER_SUFFIX = ".qrfile"
    private const val CAPTURE_DIR = "qr-capture"

    /** 是否值得显示“扫码连接”：没有相机硬件或没有系统相机应用都不算。 */
    fun canCapture(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return false
        return captureIntent("").resolveActivity(context.packageManager) != null
    }

    fun newCaptureFile(context: Context): File {
        val dir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File.createTempFile("scan-", ".jpg", dir)
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + PROVIDER_SUFFIX, file)

    fun captureIntent(output: String): Intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
        if (output.isNotEmpty()) {
            val uri = Uri.parse(output)
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // 部分 ROM 的相机只认 ClipData 里的授权，单给 flag 会返回空文件
            clipData = android.content.ClipData.newRawUri("qr-output", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun startIntent(context: Context, file: File): Intent = captureIntent(uriFor(context, file).toString())

    /** 解码成功返回原始二维码文本；文件损坏/无码/异常一律 null，由界面回落投屏码。 */
    fun decodeFile(file: File): String? {
        if (!file.isFile || file.length() <= 0L) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while ((options.outWidth / sample) > MAX_SIDE || (options.outHeight / sample) > MAX_SIDE) sample *= 2
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
        return try {
            decode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** 相机拍出来的方向不固定：四向各试一次，避免用户为了“码是横的”反复重拍。 */
    fun decode(bitmap: Bitmap): String? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        for (degrees in intArrayOf(0, 90, 180, 270)) {
            val candidate = if (degrees == 0) bitmap else rotate(bitmap, degrees)
            val text = decodeOne(candidate)
            if (degrees != 0) candidate.recycle()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun decodeOne(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return runCatching {
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true,
                        DecodeHintType.CHARACTER_SET to "UTF-8",
                    ),
                )
            }
            reader.decode(binary).text
        }.getOrNull()
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** 供 JVM 单测直接解码 YUV 帧；相机拍照路径不走这里，但保留同一套识别口径。 */
    fun decodeYuv(data: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || data.size < width * height) return null
        return runCatching {
            val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            val binary = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
            }.decode(binary).text
        }.getOrNull()
    }
}

package io.github.wangnan05563.airmirror.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.pairing.PairingLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** T06105 扫码解码回归：生成真实二维码 → 模拟“拍照背景” → 本地解码，覆盖四个旋转方向。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class QrPhotoScanTest {

    private val payload = PairingLink.build(
        PairingLink.Link("am-test", "客厅电视", "token-123", "192.168.1.8", 41523),
    )

    @Test
    fun `从拍照图解码出投屏链接`() {
        val file = writePhoto(0)
        val text = QrPhotoScan.decodeFile(file)
        assertNotNull("拍照图必须能识别出二维码", text)
        assertEquals("am-test", PairingLink.parse(text!!)?.deviceId)
        file.delete()
    }

    @Test
    fun `照片横拍竖拍都能解`() {
        listOf(90, 180, 270).forEach { degrees ->
            val file = writePhoto(degrees)
            val text = QrPhotoScan.decodeFile(file)
            assertNotNull("旋转 $degrees 度后二维码仍要能识别", text)
            file.delete()
        }
    }

    @Test
    fun `空图和普通照片返回 null 不抛异常`() {
        assertNull(QrPhotoScan.decodeFile(File("/definitely/missing.jpg")))
        val white = File.createTempFile("qr-white-", ".jpg")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        FileOutputStream(white).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        assertNull("纯白图不该识别出任何码", QrPhotoScan.decodeFile(white))
        bitmap.recycle()
        white.delete()
    }

    @Test
    fun `拍照 Intent 只带临时输出且不申请相机权限`() {
        val intent = QrPhotoScan.captureIntent("content://example.qrfile/qr_capture/scan-1.jpg")
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
        assertTrue("必须把临时输出交给系统相机", intent.hasExtra(MediaStore.EXTRA_OUTPUT))
        assertTrue(
            "临时 URI 只授予相机写权限",
            intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(
            "T06105 不申请 CAMERA 权限，避免新增隐私权限面",
            app.packageManager.checkPermission(android.Manifest.permission.CAMERA, app.packageName) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }

    /** 模拟电视二维码在照片里的位置：留边、带背景噪声，再按指定角度旋转整张照片。 */
    private fun writePhoto(degrees: Int): File {
        val width = 1200
        val height = 900
        val photo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = Canvas(photo)
        canvas.drawColor(Color.rgb(238, 238, 238))
        val qr = Qr.bitmap(payload, 620)
        canvas.drawBitmap(qr, (width - qr.width) / 2f, (height - qr.height) / 2f, null)

        val rotated = if (degrees == 0) photo else {
            Bitmap.createBitmap(photo, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        }
        val file = File.createTempFile("qr-$degrees-", ".jpg")
        FileOutputStream(file).use { rotated.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (rotated !== photo) rotated.recycle()
        photo.recycle()
        qr.recycle()
        return file
    }
}

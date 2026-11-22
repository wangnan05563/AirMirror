package io.github.wangnan05563.airmirror.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** T06101 插画本身：三套都能真实绘制、装饰图不入无障碍树、源码不夹带位图。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-320dpi")
class ErrorIllustrationViewTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun render(kind: ErrorIllustrationView.Kind): Bitmap {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = ErrorIllustrationView(context).apply { this.kind = kind }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 400, 240)
        val bitmap = Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun assertDrawn(kind: ErrorIllustrationView.Kind) {
        val bitmap = render(kind)
        var inked = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) inked++
            }
        }
        assertTrue("$kind 必须画出可见线条/面，当前 inked=$inked", inked > 500)
    }

    @Test
    fun `无设备插画真实绘制`() = assertDrawn(ErrorIllustrationView.Kind.NO_DEVICE)

    @Test
    fun `网络异常插画真实绘制`() = assertDrawn(ErrorIllustrationView.Kind.NETWORK)

    @Test
    fun `接收端离线插画真实绘制`() = assertDrawn(ErrorIllustrationView.Kind.OFFLINE)

    @Test
    fun `装饰图从无障碍树移除`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = ErrorIllustrationView(context)
        assertTrue(view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    }

    @Test
    fun `插画与卡片源码不引入位图`() {
        val ui = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui")
        val illustration = File(ui, "ErrorIllustrationView.kt").readText()
        val card = File(ui, "ErrorStateCard.kt").readText()
        for (source in listOf(illustration, card)) {
            assertFalse(source.contains("Bitmap"))
            assertFalse(source.contains("R.drawable"))
            assertFalse(source.contains("setImageBitmap"))
            assertFalse(source.contains("BitmapFactory"))
        }
        assertTrue("装饰图必须走 A11y 统一出口", illustration.contains("A11y.decorative(this)"))
        assertTrue("线宽必须取焦点环同源令牌", illustration.contains("FocusPolicy.OUTLINE_DP"))
    }
}

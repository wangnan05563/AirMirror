package io.github.wangnan05563.airmirror.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * T06102 / AC-08 证据：
 * 1. debug 主题预览页在 360dp 与 1080p 真实渲染并导出 PNG；
 * 2. 旧色值与新令牌逐像素对比，差异必须为 0（≤1% 的更强口径）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-320dpi")
class ThemeRenderEvidenceTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun renderPreview(width: Int, label: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = ContextThemeWrapper(app, R.style.Theme_AirMirror)
        val view = ThemePreviewView(context)
        val content = view.getChildAt(0)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = content.measuredHeight.coerceAtLeast(1)
        content.layout(0, 0, width, height)

        assertTrue("$label 预览页必须有标题", findText(content, "Midnight Theme Tokens") != null)
        assertTrue("$label 预览页必须有内容", height > 0)
        assertTrue("5% 安全区必须出现在预览中", findText(content, "5% safe area") != null)
        assertTrue("组件层必须出现在预览中", findText(content, "主操作") != null)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Theme.component.pageBackground)
        content.draw(canvas)
        val outDir = File(projectRoot(), "docs/assets/theme-tokens-20261123").apply { mkdirs() }
        val file = File(outDir, "theme-preview-$label-full-20261123.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("截图必须落盘：${file.name}", file.length() > 1_000L)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-320dpi")
    fun `360dp 主题预览可渲染并导出全长截图`() = renderPreview(720, "360dp")

    @Test
    @Config(qualifiers = "w960dp-h540dp-320dpi")
    fun `1080p 主题预览可渲染并导出全长截图`() = renderPreview(1920, "1080p")

    @Test
    fun `旧色值与新令牌逐像素等价`() {
        val legacy = listOf(
            0xFF0B1F3A.toInt(), // background
            0xFF12314F.toInt(), // surface
            0xFF1A3D61.toInt(), // surfaceElevated
            0xFF17C3B2.toInt(), // accent
            0xFFFFFFFF.toInt(), // textPrimary
            0xB3FFFFFF.toInt(), // textSecondary
            0x4DFFFFFF.toInt(), // offline
            0xFFFF6B6B.toInt(), // danger
            0x660B1F3A.toInt(), // focusFill
            0xCC0B1F3A.toInt(), // scrim
            0xE60B1F3A.toInt(), // scrimStrong
            0xE612314F.toInt(), // panel
            0x3312314F.toInt(), // divider
            0x3317C3B2.toInt(), // surfaceStroke
            0x1F17C3B2.toInt(), // tag protocol
            0x1AFFFFFF.toInt(), // tag offline
            0x14FFFFFF.toInt(), // tag unsupported
        )
        val tokens = listOf(
            Theme.BACKGROUND, Theme.SURFACE, Theme.SURFACE_ELEVATED, Theme.ACCENT,
            Theme.TEXT_PRIMARY, Theme.TEXT_SECONDARY, Theme.OFFLINE, Theme.DANGER,
            Theme.FOCUS_FILL, Theme.SCRIM, Theme.SCRIM_STRONG, Theme.PANEL,
            Theme.DIVIDER, Theme.SURFACE_STROKE,
            Theme.component.tagProtocolBg, Theme.component.tagOfflineBg, Theme.component.tagUnsupportedBg,
        )

        val cell = 64
        val width = cell * legacy.size
        val height = cell * 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        for (i in legacy.indices) {
            val paintLegacy = android.graphics.Paint().apply { color = legacy[i] }
            canvas.drawRect((i * cell).toFloat(), 0f, ((i + 1) * cell).toFloat(), cell.toFloat(), paintLegacy)
            val paintToken = android.graphics.Paint().apply { color = tokens[i] }
            canvas.drawRect((i * cell).toFloat(), cell.toFloat(), ((i + 1) * cell).toFloat(), (cell * 2).toFloat(), paintToken)
        }
        for (y in 0 until cell) {
            for (x in 0 until width) {
                assertEquals("x=$x y=$y 旧色与令牌不一致", bitmap.getPixel(x, y), bitmap.getPixel(x, y + cell))
            }
        }

        val outDir = File(projectRoot(), "docs/assets/theme-tokens-20261123").apply { mkdirs() }
        val file = File(outDir, "theme-legacy-vs-tokens-20261123.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("对比图必须落盘", file.length() > 1_000L)
    }

    private fun findText(root: View, needle: String): String? {
        if (root is android.widget.TextView && root.text.toString().contains(needle)) return root.text.toString()
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findText(root.getChildAt(i), needle)?.let { return it }
            }
        }
        return null
    }
}

package io.github.wangnan05563.airmirror.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.R
import io.github.wangnan05563.airmirror.error.ErrorNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * T06101 视觉稿矩阵：三套卡在 360dp / 720p / 1080p 真实渲染并导出 PNG。
 * 断言覆盖无截断、错误码、主/次级按钮、无异常堆栈、插画不读。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-320dpi")
class ErrorStateCardUiTest {

    private data class Case(
        val slug: String,
        val cause: ErrorNotice.Cause,
        val code: String,
    )

    private val cases = listOf(
        Case("no-device", ErrorNotice.Cause.NO_DISCOVERY, "AM-ND-01"),
        Case("network", ErrorNotice.Cause.DISCOVERY_AP_ISOLATED, "AM-NET-01"),
        Case("offline", ErrorNotice.Cause.DEVICE_OFFLINE, "AM-OFF-01"),
    )

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-320dpi")
    fun `360dp 三套卡无截断并导出截图`() = capture(720, 1280, "360dp")

    @Test
    @Config(qualifiers = "w640dp-h360dp-320dpi")
    fun `720p 三套卡无截断并导出截图`() = capture(1280, 720, "720p")

    @Test
    @Config(qualifiers = "w960dp-h540dp-320dpi")
    fun `1080p 三套卡无截断并导出截图`() = capture(1920, 1080, "1080p")

    private fun capture(width: Int, height: Int, label: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = ContextThemeWrapper(app, R.style.Theme_AirMirror)
        val safe = TvLayoutSpec.safeInsets(width, height)
        val outDir = File(projectRoot(), "docs/assets/error-states-20261123").apply { mkdirs() }

        for (case in cases) {
            val root = FrameLayout(context).apply {
                setBackgroundColor(Theme.BACKGROUND)
                setPadding(safe.horizontal, safe.vertical, safe.horizontal, safe.vertical)
            }
            val card = ErrorStateCard(context)
            card.bind(case.cause)
            root.addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)

            assertTrue("${case.slug}@$label 必须有高度", card.height > 0)
            assertTrue(
                "${case.slug}@$label 顶部被安全区裁切：card.top=${card.top} safe=${safe.vertical}",
                card.top >= safe.vertical,
            )
            assertTrue(
                "${case.slug}@$label 底部被安全区裁切：card.bottom=${card.bottom} safeBottom=${height - safe.vertical}",
                card.bottom <= height - safe.vertical,
            )
            assertEquals("错误码可见", "错误码 ${case.code}", card.errorCode.text.toString())
            assertTrue("原因不能为空", card.headline.text.isNotBlank())
            assertTrue("下一步不能为空", card.nextStep.text.isNotBlank())
            assertEquals("主/次/兜底三动作", 3, card.actionsBar.childCount)

            val primary = card.actionsBar.getChildAt(0) as MaterialButton
            val secondary = card.actionsBar.getChildAt(1) as MaterialButton
            val ghost = card.actionsBar.getChildAt(2) as MaterialButton
            assertEquals("主按钮实底", Theme.ACCENT, primary.backgroundTintList?.defaultColor)
            assertEquals("次按钮面底", Theme.SURFACE, secondary.backgroundTintList?.defaultColor)
            assertEquals("兜底按钮透明", Color.TRANSPARENT, ghost.backgroundTintList?.defaultColor)
            assertTrue(
                "触控高度不得低于 48dp",
                listOf(primary, secondary, ghost).all { it.minimumHeight >= Theme.dp(context, 48) },
            )
            assertTrue(
                "动作区越过卡片内边界，720p 会在卡片底缘裁掉按钮圆角",
                card.actionsBar.bottom <= card.height - card.paddingBottom,
            )
            assertTrue(
                "插画必须从无障碍树移除",
                card.illustration.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            )

            val visibleCopy = listOf(card.errorCode, card.headline, card.nextStep)
                .joinToString(" ") { it.text.toString() }
            assertFalse("不得出现异常类名", visibleCopy.contains("Exception"))
            assertFalse("不得出现 Java 包名", visibleCopy.contains("java."))
            assertFalse("不得出现堆栈帧", visibleCopy.contains("at io."))

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(outDir, "error-${case.slug}-$label-20261123.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("截图必须落盘：${file.name}", file.length() > 1_000L)
        }
    }
}

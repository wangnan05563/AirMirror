package io.github.wangnan05563.airmirror.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.R
import io.github.wangnan05563.airmirror.error.ErrorNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * T06104 空态/加载态：组件级渲染 + 发送端真实页面两档截图。
 *
 * 断言重点：三套空态仍由 ErrorStateCard 渲染，加载卡 10 秒前无取消、10 秒整有取消，
 * 文案不出现异常/堆栈，骨架不读位图，动作按钮都在首屏内。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-320dpi")
class SenderEmptyStateUiTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun themed() = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.Theme_AirMirror,
    )

    private fun uiDir() = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui")

    private fun save(bitmap: Bitmap, name: String) {
        val outDir = File(projectRoot(), "docs/assets/empty-states-20261123").apply { mkdirs() }
        val file = File(outDir, "$name-20261123.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("截图必须落盘：${file.name}", file.length() > 1_000L)
    }

    @Test
    fun `加载卡 10 秒前只有进度无取消`() {
        val context = themed()
        val root = FrameLayout(context).apply { setBackgroundColor(Theme.BACKGROUND) }
        val card = LoadingStateCard(context)
        card.bind("客厅电视", 0)
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 720, 1280)
        assertEquals("10 秒前文案", "正在连接客厅电视…", card.title.text.toString())
        assertEquals("10 秒前不露出取消", View.GONE, card.cancelButton.visibility)
        assertEquals("骨架从无障碍树移除", View.IMPORTANT_FOR_ACCESSIBILITY_NO, card.skeleton.importantForAccessibility)
        assertTrue("进度条可见", card.progress.visibility == View.VISIBLE)
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        save(bitmap, "loading-360dp-early")
    }

    @Test
    fun `加载卡 10 秒整切仍在连接并露出取消`() {
        val context = themed()
        val root = FrameLayout(context).apply { setBackgroundColor(Theme.BACKGROUND) }
        val card = LoadingStateCard(context)
        card.bind("客厅电视", 10_000L)
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 720, 1280)
        assertEquals("10 秒整文案", "仍在连接客厅电视…", card.title.text.toString())
        assertEquals("10 秒整露出取消", View.VISIBLE, card.cancelButton.visibility)
        assertTrue("取消按钮触控高度 ≥48dp", card.cancelButton.minimumHeight >= Theme.dp(context, 48))
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        save(bitmap, "loading-360dp-cancel")
    }

    @Test
    fun `加载卡 1080p 不裁切`() {
        val context = themed()
        val root = FrameLayout(context).apply { setBackgroundColor(Theme.BACKGROUND) }
        val card = LoadingStateCard(context)
        card.bind("客厅里那台很长的电视名字", 12_000L)
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        assertTrue("长设备名必须截断", card.title.text.length < 40)
        assertTrue("取消按钮在卡内", card.cancelButton.bottom <= card.height - card.paddingBottom)
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        save(bitmap, "loading-1080p-cancel")
    }

    @Test
    fun `骨架纯代码绘制不读位图`() {
        val src = File(uiDir(), "LoadingSkeletonView.kt").readText()
        assertFalse("骨架不得读位图", src.contains("BitmapFactory") || src.contains("R.drawable"))
        assertTrue(
            "骨架必须按时间推进扫光",
            src.contains("SystemClock.uptimeMillis()") && src.contains("postInvalidateOnAnimation()"),
        )
        assertFalse("骨架不引入属性动画框架", src.contains("ValueAnimator") || src.contains("ObjectAnimator"))
        assertTrue("骨架必须从无障碍树移除", src.contains("A11y.decorative(this)"))
    }

    @Test
    fun `发送端 360dp 空态动作在首屏`() {
        val a = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        val card = field(a, "diagnosisCard") as ErrorStateCard
        card.bind(ErrorNotice.Cause.NO_DISCOVERY, EmptyDiagnosis.ACTIONS)
        card.visibility = View.VISIBLE
        (field(a, "hint") as TextView).visibility = View.GONE
        layout(a, 720, 1280)
        val entry = findText(a.window.decorView, "用投屏码连接")!!
        assertInsideScreen("发送端-空态投屏码", entry, 1280)
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        a.window.decorView.draw(Canvas(bitmap))
        save(bitmap, "sender-empty-360dp")
    }

    @Test
    fun `发送端 360dp 加载取消在首屏`() {
        val a = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        val begin = a.javaClass.getDeclaredMethod("beginLoading", String::class.java).apply { isAccessible = true }
        begin.invoke(a, "客厅电视")
        val loading = field(a, "loadingCard") as LoadingStateCard
        loading.bind("客厅电视", 10_000L)
        loading.visibility = View.VISIBLE
        (field(a, "status") as TextView).text = ErrorVisualSpec.loading("客厅电视", 10_000L).line
        layout(a, 720, 1280)
        val entry = findText(a.window.decorView, "取消")!!
        assertInsideScreen("发送端-360dp 加载取消", entry, 1280)
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        a.window.decorView.draw(Canvas(bitmap))
        save(bitmap, "sender-loading-360dp-cancel")
    }

    private fun layout(activity: android.app.Activity, width: Int, height: Int) {
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, width, height)
    }

    private fun field(activity: android.app.Activity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun findText(root: View, target: String): TextView? {
        if (root is TextView && root.text?.toString() == target) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findText(root.getChildAt(i), target)?.let { return it }
        return null
    }

    private fun assertInsideScreen(name: String, v: View, screenHeight: Int) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val bottom = loc[1] + v.height
        assertTrue("$name 超出首屏: bottom=$bottom screen=$screenHeight", bottom <= screenHeight)
        val visible = Rect()
        assertTrue("$name 不在可视区", v.getGlobalVisibleRect(visible))
        assertTrue(
            "$name 被滚动容器裁切: visible=${visible.height()} h=${v.height}",
            visible.height() >= v.height,
        )
    }
}

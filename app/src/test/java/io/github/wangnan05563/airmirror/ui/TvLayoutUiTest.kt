package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.receiver.ReceiverHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * T06100：TV 关键页在 1080p / 720p / 4:3 下的安全区、字号、二维码收敛与宽屏并排证据。
 * 每组用真实像素布局截图，不用设计稿占位图冒充。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TvLayoutUiTest {

    @Before
    fun resetHostBefore() = resetReceiverHost()

    @After
    fun resetHostAfter() = resetReceiverHost()

    private fun resetReceiverHost() {
        val f = ReceiverHost::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val host = f.get(null) as? ReceiverHost
        runCatching { host?.session?.stop() }
        runCatching { host?.shutdown() }
        f.set(null, null)
    }

    private fun setupReceiver(): Activity {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).apply {
            setFirstLaunch(false)
            saveDeviceName("客厅电视")
        }
        val a = Robolectric.buildActivity(ReceiverActivity::class.java).setup().get()
        layout(a)
        return a
    }

    private fun layout(a: Activity) {
        val w = a.resources.displayMetrics.widthPixels
        val h = a.resources.displayMetrics.heightPixels
        a.window.decorView.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        a.window.decorView.layout(0, 0, w, h)
        ShadowLooper.idleMainLooper()
    }

    private fun field(a: Activity, name: String): Any {
        val f = ReceiverActivity::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(a)!!
    }

    private fun invoke(a: Activity, name: String) {
        ReceiverActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(a)
        ShadowLooper.idleMainLooper()
    }

    /** 等后台线程把二维码真正画进 ImageView，避免截图只留下白色方块。 */
    private fun awaitQr(a: Activity) {
        val qrView = field(a, "qrView") as ImageView
        val host = field(a, "host") as ReceiverHost
        val lastPayload = ReceiverActivity::class.java.getDeclaredField("lastPayload").apply { isAccessible = true }
        var waited = 0
        while (waited < 6000 && qrView.drawable == null) {
            Thread.sleep(200)
            ShadowLooper.idleMainLooper()
            if (host.port > 0 && qrView.drawable == null) {
                lastPayload.set(a, "")
                invoke(a, "refreshPairingQr")
            }
            waited += 200
        }
        assertTrue("待连接页二维码未生成", qrView.drawable != null)
    }

    private fun visibleTexts(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun capture(a: Activity, name: String) {
        val decor = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels
        val h = a.resources.displayMetrics.heightPixels
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, w, h)
        ShadowLooper.idleMainLooper()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val file = File(projectRoot(), "build/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("截图未生成：${file.absolutePath}", file.length() > 1024)
        println("TV_LAYOUT_SCREENSHOT ${file.absolutePath} ${w}x$h")
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError("未定位到项目根：${System.getProperty("user.dir")}")
    }

    private fun pairRow(waiting: View): LinearLayout {
        val mainArea = (waiting as ViewGroup).getChildAt(0) as LinearLayout
        // 第 0 个是设备名行，第 1 个是二维码/投屏码并排行。
        return mainArea.getChildAt(1) as LinearLayout
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-320dpi")
    fun waiting1080pKeepsSafeAreaAndTvType() {
        val a = setupReceiver()
        awaitQr(a)
        layout(a)
        val tv = a as ReceiverActivity
        val waiting = field(a, "waiting") as LinearLayout
        val qr = field(a, "qrView") as ImageView
        assertEquals(1920, a.resources.displayMetrics.widthPixels)
        assertEquals(1080, a.resources.displayMetrics.heightPixels)
        assertTrue("1920x1080 必须按 TV 排版", TvFocus.isTvMode(tv))
        assertEquals(96, waiting.paddingLeft)
        assertEquals(96, waiting.paddingRight)
        assertEquals(56, waiting.paddingTop)
        assertEquals(54, waiting.paddingBottom)
        assertEquals(368, qr.layoutParams.width)
        val row = pairRow(waiting)
        val codeSlot = field(a, "codeSlot") as View
        assertEquals(368, codeSlot.layoutParams.width)
        assertTrue("并排内容超出安全区", qr.width + 48 + codeSlot.width <= row.width)
        assertEquals(368, row.height)
        assertTrue("可见正文必须 ≥24px", visibleTexts(waiting).all { it.textSize >= 24f })
        capture(a, "tv-layout-1080p-waiting-20261123")
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-320dpi")
    fun settings1080pKeepsSafeAreaAndTvType() {
        val a = setupReceiver()
        invoke(a, "showSettings")
        layout(a)
        val panel = field(a, "settingsPanel") as LinearLayout
        assertEquals(96, panel.paddingLeft)
        assertEquals(96, panel.paddingRight)
        assertEquals(54, panel.paddingTop)
        assertEquals(54, panel.paddingBottom)
        assertTrue("设置页可见正文必须 ≥24px", visibleTexts(panel).all { it.textSize >= 24f })
        capture(a, "tv-layout-1080p-settings-20261123")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-320dpi")
    fun waiting720pFitsWithoutBottomCrop() {
        val a = setupReceiver()
        awaitQr(a)
        layout(a)
        val waiting = field(a, "waiting") as LinearLayout
        val qr = field(a, "qrView") as ImageView
        assertEquals(1280, a.resources.displayMetrics.widthPixels)
        assertEquals(720, a.resources.displayMetrics.heightPixels)
        assertEquals(64, waiting.paddingLeft)
        assertEquals(56, waiting.paddingTop)
        assertEquals(48, waiting.paddingBottom)
        assertEquals(245, qr.layoutParams.width)
        assertTrue(visibleTexts(waiting).all { it.textSize >= 24f })
        capture(a, "tv-layout-720p-waiting-20261123")
    }

    @Test
    @Config(qualifiers = "w512dp-h384dp-320dpi")
    fun waiting4x3UsesPairRowWithoutHorizontalClipping() {
        val a = setupReceiver()
        awaitQr(a)
        layout(a)
        val waiting = field(a, "waiting") as LinearLayout
        val qr = field(a, "qrView") as ImageView
        val row = pairRow(waiting)
        assertEquals(1024, a.resources.displayMetrics.widthPixels)
        assertEquals(768, a.resources.displayMetrics.heightPixels)
        assertEquals(64, waiting.paddingLeft)
        assertEquals(64, waiting.paddingRight)
        assertEquals(56, waiting.paddingTop)
        assertEquals(48, waiting.paddingBottom)
        assertEquals(262, qr.layoutParams.width)
        val codeSlot = field(a, "codeSlot") as View
        assertTrue(row.measuredWidth <= 1024 - 2 * 64)
        assertTrue(qr.width + 48 + codeSlot.width <= row.width)
        assertEquals(1024 - 2 * 64, row.width)
        assertTrue(visibleTexts(waiting).all { it.textSize >= 24f })
        capture(a, "tv-layout-4x3-waiting-20261123")
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-320dpi")
    fun mainEntry1080pUsesSafeAreaAndTvType() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        layout(a)
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as LinearLayout
        assertTrue(TvFocus.isTvMode(a))
        assertEquals(96, root.paddingLeft)
        assertEquals(64, root.paddingTop)
        assertEquals(54, root.paddingBottom)
        assertTrue(visibleTexts(root).all { it.textSize >= 24f })
        capture(a, "tv-layout-1080p-entry-20261123")
    }
}

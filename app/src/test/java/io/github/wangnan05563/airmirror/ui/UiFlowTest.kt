package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.update.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/** 各页面真实点击流程：导航、输入校验、首启推进、设置落盘与返回。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class UiFlowTest {

    private fun setup(clazz: Class<out Activity>): Activity {
        val a = Robolectric.buildActivity(clazz).setup().get()
        val decor = a.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1080, 2400)
        return a
    }

    private fun texts(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun click(root: View, text: String): Boolean {
        val node = texts(root).firstOrNull { it.text?.toString() == text } ?: return false
        node.performClick()
        return true
    }

    private fun clickStartsWith(root: View, prefix: String): Boolean {
        val node = texts(root).firstOrNull { it.text?.toString()?.startsWith(prefix) == true } ?: return false
        node.performClick()
        return true
    }

    private fun hasText(root: View, text: String): Boolean = texts(root).any { it.text?.toString() == text }

    @Test
    fun `sender rejects short code then explains empty device list`() {
        val a = setup(SenderActivity::class.java)
        val input = texts(a.window.decorView).filterIsInstance<EditText>().first()
        input.setText("123")
        assertTrue(click(a.window.decorView, "用码连接"))
        assertEquals("请输入电视上的 4 位 PIN 或 6 位投屏码", ShadowToast.getTextOfLatestToast())

        input.setText("123456")
        assertTrue(click(a.window.decorView, "用码连接"))
        assertEquals("没搜到设备，在下面填电视地址", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `receiver onboarding advances three steps and persists completion`() {
        val store = DeviceStore(ApplicationProvider.getApplicationContext<Context>())
        store.setFirstLaunch(true)
        assertEquals("首启标记未写入", true, store.firstLaunch())
        val a = setup(ReceiverActivity::class.java)
        val root = a.window.decorView
        assertTrue("首启引导未显示", hasText(root, "第 1 / 3 步"))

        val next = ReceiverActivity::class.java.getDeclaredField("onboardNext").apply { isAccessible = true }.get(a) as TextView
        next.performClick()
        assertTrue(hasText(root, "第 2 / 3 步"))
        next.performClick()
        assertTrue(hasText(root, "第 3 / 3 步"))
        assertEquals("完成", next.text.toString())
        next.performClick()

        val onboarding = ReceiverActivity::class.java.getDeclaredField("onboarding").apply { isAccessible = true }.get(a) as View
        assertEquals(View.GONE, onboarding.visibility)
        assertFalse(store.firstLaunch())
        assertTrue(store.deviceName("").isNotEmpty())
    }

    @Test
    fun `receiver settings toggles update labels persist and close`() {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).setFirstLaunch(false)
        val a = setup(ReceiverActivity::class.java)
        val m = ReceiverActivity::class.java.getDeclaredMethod("showSettings").apply { isAccessible = true }
        m.invoke(a)
        val root = a.window.decorView
        val store = ReceiverActivity::class.java.getDeclaredField("store").apply { isAccessible = true }.get(a) as DeviceStore
        val rotation = ReceiverActivity::class.java.getDeclaredField("rotationRow").apply { isAccessible = true }.get(a) as TextView
        val label = ReceiverActivity::class.java.getDeclaredField("labelRow").apply { isAccessible = true }.get(a) as TextView
        val badge = ReceiverActivity::class.java.getDeclaredField("badgeRow").apply { isAccessible = true }.get(a) as TextView
        val panel = ReceiverActivity::class.java.getDeclaredField("settingsPanel").apply { isAccessible = true }.get(a) as View

        assertTrue("旋转行初始文案不符", rotation.text.toString().startsWith("旋转跟随：开"))
        rotation.performClick()
        assertEquals("旋转跟随：关", rotation.text.toString())
        assertFalse(store.followRotation())

        assertTrue("竖屏行初始文案不符", label.text.toString().startsWith("竖屏标签：开"))
        label.performClick()
        assertEquals("竖屏标签：关", label.text.toString())
        assertFalse(store.portraitLabel())

        assertTrue("角标行初始文案不符", badge.text.toString().startsWith("画质角标：关"))
        badge.performClick()
        assertEquals("画质角标：开", badge.text.toString())
        assertTrue(store.qualityBadgeEnabled())

        assertTrue(click(panel, "完成"))
        val overlay = ReceiverActivity::class.java.getDeclaredField("settingsOverlay").apply { isAccessible = true }.get(a) as View
        assertEquals(View.GONE, overlay.visibility)
    }

    @Test
    fun `about page back finishes the page`() {
        val a = setup(AboutActivity::class.java)
        assertTrue(click(a.window.decorView, "返回"))
        assertTrue(a.isFinishing)
    }

    @Test
    fun `update dialog later button dismisses`() {
        val a = setup(SenderActivity::class.java)
        val release = ReleaseInfo(
            tag = "v0.1.0-m0.20261120-001",
            title = "AirMirror 更新",
            notes = "修复触控目标高度；统一弹窗样式。",
            apkUrl = "https://example.com/AirMirror.apk",
            apkName = "AirMirror.apk",
        )
        val m = SenderActivity::class.java.getDeclaredMethod("promptUpdate", ReleaseInfo::class.java).apply { isAccessible = true }
        m.invoke(a, release)
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        val negative = (dialog as androidx.appcompat.app.AlertDialog).getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
        assertEquals("稍后", negative.text.toString())
        negative.performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(dialog.isShowing)
    }
    @Test
    fun `receiver settings back closes overlay without exiting receiver`() {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).setFirstLaunch(false)
        val a = setup(ReceiverActivity::class.java)
        val m = ReceiverActivity::class.java.getDeclaredMethod("showSettings").apply { isAccessible = true }
        m.invoke(a)
        val overlay = ReceiverActivity::class.java.getDeclaredField("settingsOverlay").apply { isAccessible = true }.get(a) as View
        assertEquals(View.VISIBLE, overlay.visibility)

        a.onBackPressed()

        assertEquals("返回键应关闭显示设置", View.GONE, overlay.visibility)
        assertFalse("返回键不应结束接收端", a.isFinishing)
    }

}

package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class UiTouchAuditTest {
    private val width = 1080
    private val height = 2400

    private fun layout(activity: Activity) {
        val decor = activity.window.decorView
        val w = activity.resources.displayMetrics.widthPixels
        val h = activity.resources.displayMetrics.heightPixels
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, w, h)
    }

    private fun audit(name: String, root: View): String {
        val density = root.resources.displayMetrics.density
        val minPx = 47.5f * density
        val violations = ArrayList<String>()
        var clickableTexts = 0
        fun walk(v: View) {
            if (v is TextView && v.isClickable && v.isShown) {
                clickableTexts++
                val label = v.text?.toString()?.take(30) ?: v.contentDescription?.toString()?.take(30) ?: "(无文本)"
                // 文本型入口只要保证纵向 48dp（横向由文案自然撑开，不强制 48dp 方块）
                if (v.height < minPx) {
                    violations += "$label h=${"%.1f".format(v.height / density)}dp"
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        println("TOUCH $name clickableTexts=$clickableTexts violations=${violations.size} ${violations.joinToString()}")
        return violations.joinToString()
    }

    @Test
    fun allVisibleClickableTextViewsMeet48dp() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        layout(main)
        assertTrue("主入口: " + audit("main", main.window.decorView), audit("main", main.window.decorView).isBlank())

        val sender = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        layout(sender)
        assertTrue("发送端: " + audit("sender", sender.window.decorView), audit("sender", sender.window.decorView).isBlank())

        DeviceStore(ApplicationProvider.getApplicationContext()).setFirstLaunch(false)
        val receiver = Robolectric.buildActivity(ReceiverActivity::class.java).setup().get()
        layout(receiver)
        assertTrue("接收端: " + audit("receiver", receiver.window.decorView), audit("receiver", receiver.window.decorView).isBlank())

        val m = ReceiverActivity::class.java.getDeclaredMethod("showSettings")
        m.isAccessible = true
        m.invoke(receiver)
        layout(receiver)
        assertTrue("显示设置: " + audit("settings", receiver.window.decorView), audit("settings", receiver.window.decorView).isBlank())

        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        layout(about)
        assertTrue("关于页: " + audit("about", about.window.decorView), audit("about", about.window.decorView).isBlank())
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-320dpi")
    fun compactAllVisibleClickableTextViewsMeet48dp() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        layout(main)
        assertTrue("主入口 360: " + audit("main360", main.window.decorView), audit("main360", main.window.decorView).isBlank())

        val sender = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        layout(sender)
        assertTrue("发送端 360: " + audit("sender360", sender.window.decorView), audit("sender360", sender.window.decorView).isBlank())

        DeviceStore(ApplicationProvider.getApplicationContext()).setFirstLaunch(false)
        val receiver = Robolectric.buildActivity(ReceiverActivity::class.java).setup().get()
        layout(receiver)
        assertTrue("接收端 360: " + audit("receiver360", receiver.window.decorView), audit("receiver360", receiver.window.decorView).isBlank())

        val m = ReceiverActivity::class.java.getDeclaredMethod("showSettings")
        m.isAccessible = true
        m.invoke(receiver)
        layout(receiver)
        assertTrue("显示设置 360: " + audit("settings360", receiver.window.decorView), audit("settings360", receiver.window.decorView).isBlank())

        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        layout(about)
        assertTrue("关于页 360: " + audit("about360", about.window.decorView), audit("about360", about.window.decorView).isBlank())
    }

    @Test
    fun onboardingSkipAllMeets48dp() {
        DeviceStore(ApplicationProvider.getApplicationContext()).setFirstLaunch(true)
        val receiver = Robolectric.buildActivity(ReceiverActivity::class.java).setup().get()
        layout(receiver)
        assertTrue("首启引导: " + audit("onboarding", receiver.window.decorView), audit("onboarding", receiver.window.decorView).isBlank())
    }
}

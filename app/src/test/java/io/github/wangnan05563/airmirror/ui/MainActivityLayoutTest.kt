package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.wangnan05563.airmirror.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class MainActivityLayoutTest {

    private fun setupMain(): Activity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1080, 2400)
        return activity
    }

    private fun clickableTextViews(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.isClickable && v.visibility == View.VISIBLE) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun `all main clickable text entries meet 48dp`() {
        val activity = setupMain()
        val density = activity.resources.displayMetrics.density
        val targets = clickableTextViews(activity.window.decorView)
        val rows = targets.map { tv ->
            val h = tv.bottom - tv.top
            "${tv.text}: ${h}px / ${String.format("%.1f", h / density)}dp"
        }
        assertTrue("未找到可点 TextView", targets.isNotEmpty())
        rows.forEach { println(it) }
        targets.forEach { tv ->
            val heightDp = (tv.bottom - tv.top) / density
            assertTrue("${tv.text} 触控高度 ${heightDp}dp < 48dp", heightDp >= 48f - 0.5f)
        }
    }

    @Test
    fun `send receive privacy about entries keep their click wiring`() {
        val activity = setupMain()
        val root = activity.window.decorView
        val byText = clickableTextViews(root).associateBy { it.text.toString() }

        byText.getValue("投到其他设备").performClick()
        val send = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(SenderActivity::class.java.name, send.component?.className)

        byText.getValue("接收别人投来的画面").performClick()
        val receive = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(ReceiverActivity::class.java.name, receive.component?.className)

        byText.getValue("关于 AirMirror").performClick()
        val about = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(AboutActivity::class.java.name, about.component?.className)

        byText.getValue("查看完整说明").performClick()
        assertTrue("隐私说明未弹出对话框", ShadowDialog.getLatestDialog() != null)
    }
}

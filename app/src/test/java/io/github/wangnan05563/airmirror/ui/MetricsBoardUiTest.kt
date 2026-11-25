package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.perf.PerfLog
import io.github.wangnan05563.airmirror.perf.PerfTrace
import io.github.wangnan05563.airmirror.receiver.ReceiverHost
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * T06120：看板必须由设置页显式点开，未采集时不能显示成 0；有样本时导出按钮可用。
 * 这是入口接线与聚合结果的联合测试，真实分享面板仍由现场验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class MetricsBoardUiTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetPerfLogSingleton()
        resetReceiverHostSingleton()
        PerfLog.get(context).clear()
    }

    @After
    fun tearDown() {
        PerfLog.get(context).clear()
        resetPerfLogSingleton()
        resetReceiverHostSingleton()
    }

    @Test
    fun `settings entry opens empty dashboard without fake zeroes`() {
        val activity = openSettings()
        clickMetricsRow(activity)
        val dialog = latestDialog()
        val text = textOf(dialog.window!!.decorView)
        assertTrue("dialogText=$text", text.contains("最近一次会话：暂无数据"))
        assertFalse(dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled)
    }

    @Test
    fun `dashboard shows latest metrics and export is enabled`() {
        val perf = PerfLog.get(context)
        val sid = "ui-s1"
        perf.mark(PerfTrace.Event.SESSION_START, sid, mapOf("encrypted" to true))
        perf.mark(
            PerfTrace.Event.CONFIG,
            sid,
            mapOf("codec" to "h264", "width" to 1920, "height" to 1080),
        )
        perf.mark(
            PerfTrace.Event.FIRST_FRAME,
            sid,
            mapOf("auth_ms" to 900, "config_ms" to 600),
        )
        perf.mark(
            PerfTrace.Event.METRICS,
            sid,
            mapOf(
                "p90_ms" to 80,
                "p95_ms" to 120,
                "drift_ms" to 4,
                "received" to 100,
                "decoded" to 100,
                "rendered" to 99,
                "dropped" to 0,
                "audio_frames" to 50,
            ),
        )
        perf.mark(
            PerfTrace.Event.AUDIO_OPEN,
            sid,
            mapOf("sr" to 48_000, "ch" to 2, "codec" to "aac"),
        )
        perf.mark(
            PerfTrace.Event.SESSION_END,
            sid,
            mapOf("received" to 110, "decoded" to 110, "audio_frames" to 55),
        )

        val activity = openSettings()
        clickMetricsRow(activity)
        val dialog = latestDialog()
        val text = textOf(dialog.window!!.decorView)
        assertTrue(text.contains("P95 120ms"))
        assertTrue(text.contains("达标（≤200ms）"))
        assertTrue(text.contains("48000Hz/2ch AAC"))
        assertTrue(dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled)
    }

    private fun openSettings(): ReceiverActivity {
        val activity = Robolectric.buildActivity(ReceiverActivity::class.java).setup().get()
        val method = ReceiverActivity::class.java.getDeclaredMethod("showSettings")
        method.isAccessible = true
        method.invoke(activity)
        ShadowLooper.idleMainLooper()
        return activity
    }

    private fun clickMetricsRow(activity: ReceiverActivity) {
        val field = ReceiverActivity::class.java.getDeclaredField("metricsRow")
        field.isAccessible = true
        val row = field.get(activity) as TextView
        assertTrue(row.performClick())
        ShadowLooper.idleMainLooper()
    }

    private fun latestDialog(): AlertDialog {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        return dialog
    }

    /** Robolectric 每个测试换 Application；PerfLog 的单例会指向上一条测试的 filesDir，需要重置。 */
    private fun resetPerfLogSingleton() {
        val field = PerfLog::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    /** ReceiverHost 是全局单例，且持有自己的 DeviceStore；不重置会让后续测试读到上一条测试的 filesDir/prefs。 */
    private fun resetReceiverHostSingleton() {
        val field = ReceiverHost::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun textOf(root: View): String = buildString {
        fun walk(view: View) {
            if (view is TextView) append(view.text).append('\n')
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
    }
}

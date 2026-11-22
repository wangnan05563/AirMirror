package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.pairing.AccessMode
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.pairing.Pairing
import io.github.wangnan05563.airmirror.pairing.PairingLink
import io.github.wangnan05563.airmirror.receiver.ReceiverHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** T06113：电视设置页的三档访问模式、信任列表、改名、撤销与清空必须真正落到 Pairing。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-320dpi")
class TrustSettingsUiTest {

    /**
     * ReceiverHost 是进程级单例；Robolectric 每个测试会换 Application，旧的 host 仍抓着上一份
     * SharedPreferences。不清掉会让后续 UiFlowTest 的首启标记读不到，属于测试隔离问题。
     */
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

    private fun setup(): Activity {
        val a = Robolectric.buildActivity(ReceiverActivity::class.java).setup().get()
        val decor = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels
        val h = a.resources.displayMetrics.heightPixels
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, w, h)
        return a
    }

    private fun field(activity: Activity, name: String): Any {
        val f = ReceiverActivity::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(activity)!!
    }

    private fun texts(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun openSettings(activity: Activity): View {
        val m = ReceiverActivity::class.java.getDeclaredMethod("showSettings").apply { isAccessible = true }
        m.invoke(activity)
        ShadowLooper.idleMainLooper()
        val panel = field(activity, "settingsPanel") as View
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
        return panel
    }

    private fun latestDialog(): AlertDialog {
        ShadowLooper.idleMainLooper()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun dialogMessage(dialog: AlertDialog): String =
        (dialog.findViewById<TextView>(android.R.id.message)?.text?.toString()).orEmpty()

    @Test
    fun threeAccessModesSwitchAndPersistTheSelection() {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).setFirstLaunch(false)
        val a = setup()
        val store = field(a, "store") as DeviceStore
        store.saveAccessMode(AccessMode.CONFIRM)
        val panel = openSettings(a)

        val modeRows = field(a, "accessModeRows") as Map<AccessMode, TextView>
        assertTrue(modeRows[AccessMode.CONFIRM]!!.text.startsWith("● 每次确认"))
        assertTrue(modeRows[AccessMode.PIN]!!.text.startsWith("○ PIN"))
        assertTrue(modeRows[AccessMode.TRUSTED_ONLY]!!.text.startsWith("○ 仅信任设备"))

        modeRows[AccessMode.PIN]!!.performClick()
        assertEquals(AccessMode.PIN, store.accessMode())
        assertTrue(modeRows[AccessMode.PIN]!!.text.startsWith("● PIN"))
        assertTrue(modeRows[AccessMode.CONFIRM]!!.text.startsWith("○ 每次确认"))

        modeRows[AccessMode.TRUSTED_ONLY]!!.performClick()
        assertEquals(AccessMode.TRUSTED_ONLY, store.accessMode())
        assertTrue(modeRows[AccessMode.TRUSTED_ONLY]!!.text.startsWith("● 仅信任设备"))
        assertTrue((field(a, "cornerHint") as TextView).text.toString() == "访问模式已切换为仅信任设备")
    }

    @Test
    fun trustListRenamesRevokesAndClearsWithPersistedSnapshots() {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).setFirstLaunch(false)
        val a = setup()
        val pairing = field(a, "pairing") as Pairing
        pairing.clearTrusted()
        pairing.trust.allow("fp-phone-a-0123456789", "客厅电视", permanent = true)
        pairing.persistTrusted()
        val panel = openSettings(a)

        val trustContainer = field(a, "trustContainer") as ViewGroup
        val summary = field(a, "trustSummary") as TextView
        assertEquals("已信任 1 台 · 30 天内使用可免确认", summary.text.toString())
        assertTrue(texts(trustContainer).any { it.text.startsWith("客厅电视\n") })

        val row = texts(trustContainer).first { it.text.startsWith("客厅电视\n") }
        row.performClick()
        val detail = latestDialog()
        assertTrue(dialogMessage(detail).contains("指纹：fp-phone-a-0123456789"))
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val rename = latestDialog()
        val input = texts(rename.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText("书房电视")
        rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals("书房电视", pairing.trust.trusted().single().name)
        assertTrue((field(a, "store") as DeviceStore).trustedSnapshot().contains(PairingLink.escape("书房电视")))
        assertTrue(texts(trustContainer).any { it.text.startsWith("书房电视\n") })

        texts(trustContainer).first { it.text.startsWith("书房电视\n") }.performClick()
        val detailAgain = latestDialog()
        detailAgain.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        val confirm = latestDialog()
        assertTrue(confirm.isShowing)
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertTrue(pairing.trust.trusted().isEmpty())
        assertEquals("# AirMirror TrustRegistry v2", (field(a, "store") as DeviceStore).trustedSnapshot())
        assertEquals("尚无信任设备 · 新设备连接需先通过访问控制", summary.text.toString())
        assertFalse(trustContainer.childCount > 0)
    }

    @Test
    fun clearingAllTrustDoesNotLeaveStaleRowsOrButtons() {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).setFirstLaunch(false)
        val a = setup()
        val pairing = field(a, "pairing") as Pairing
        pairing.clearTrusted()
        pairing.trust.allow("fp-1", "手机 A", permanent = true)
        pairing.trust.allow("fp-2", "手机 B", permanent = true)
        pairing.persistTrusted()
        val panel = openSettings(a)
        val container = field(a, "trustContainer") as ViewGroup
        assertEquals(2, container.childCount)
        val clear = field(a, "clearTrustRow") as TextView
        assertTrue(clear.text.toString().startsWith("清除全部信任设备（2 台）"))

        clear.performClick()
        val confirm = latestDialog()
        assertTrue(confirm.isShowing)
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertTrue(pairing.trust.trusted().isEmpty())
        assertEquals(0, container.childCount)
        assertEquals(View.GONE, clear.visibility)
        assertEquals("已清除全部信任设备", (field(a, "cornerHint") as TextView).text.toString())
    }

    /** 电视 1920x1080 实机比例下的可复核截图：滚动到访问控制段，带上两台设备与一台正在投屏。 */
    @Test
    fun rendersTrustSectionAtTvSizeForEvidence() {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).setFirstLaunch(false)
        val a = setup()
        val pairing = field(a, "pairing") as Pairing
        pairing.clearTrusted()
        pairing.trust.allow("fp-phone-a-0123456789", "小明的手机", permanent = true)
        pairing.trust.allow("fp-phone-b-9876543210", "客厅平板", permanent = true)
        assertTrue(pairing.trust.acquireSession("fp-phone-a-0123456789"))
        pairing.persistTrusted()
        openSettings(a)

        val row = (field(a, "accessModeRows") as Map<AccessMode, TextView>)[AccessMode.CONFIRM]!!
        val scroller = field(a, "settingsScroller") as ScrollView
        scroller.scrollTo(0, (row.top - 180).coerceAtLeast(0))
        val decor = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels
        val h = a.resources.displayMetrics.heightPixels
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, w, h)
        ShadowLooper.idleMainLooper()

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val file = File(projectRoot(), "build/trust-settings-20261122.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("截图未生成：${file.absolutePath}", file.length() > 1024)
        println("TRUST_SCREENSHOT ${file.absolutePath} ${w}x$h")
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError("未定位到项目根：${System.getProperty("user.dir")}")
    }
}

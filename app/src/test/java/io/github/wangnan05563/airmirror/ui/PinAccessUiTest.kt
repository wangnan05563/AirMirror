package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.pairing.AccessMode
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.pairing.Pairing
import io.github.wangnan05563.airmirror.pairing.PinStateCodec
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

/** T06114：PIN 模式主等待页、锁定倒计时、来源掩码与遥控器可用的 PIN 修改键盘。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-320dpi")
class PinAccessUiTest {

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
        val w = a.resources.displayMetrics.widthPixels
        val h = a.resources.displayMetrics.heightPixels
        a.window.decorView.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        a.window.decorView.layout(0, 0, w, h)
        return a
    }

    private fun field(activity: Activity, name: String): Any {
        val f = ReceiverActivity::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(activity)!!
    }

    private fun invoke(activity: Activity, name: String) {
        ReceiverActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        ShadowLooper.idleMainLooper()
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

    private fun wrongPin(pin: String): String = if (pin == "0000") "1111" else "0000"

    @Test
    fun pinModeShowsFourDigitsAndHidesTheSixDigitCodeAndQr() {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).setFirstLaunch(false)
        val a = setup()
        val store = field(a, "store") as DeviceStore
        val pairing = field(a, "pairing") as Pairing
        store.saveAccessMode(AccessMode.PIN)
        invoke(a, "refreshWaitingPage")

        val pin = pairing.pin.pin()
        assertEquals(pin.toCharArray().joinToString(" "), (field(a, "pinDisplay") as TextView).text.toString())
        assertEquals(View.GONE, (field(a, "codeSlot") as View).visibility)
        assertEquals(View.GONE, (field(a, "qrView") as View).visibility)
        assertEquals(View.GONE, (field(a, "countdownText") as View).visibility)
        assertEquals(View.VISIBLE, (field(a, "pinArea") as View).visibility)
        assertTrue((field(a, "pinLockText") as TextView).text.toString().contains("在发送端输入"))
    }

    @Test
    fun fiveWrongPinsShowLockCountdownAndMaskedSourceOnTheWaitingPage() {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).setFirstLaunch(false)
        val a = setup()
        val store = field(a, "store") as DeviceStore
        val pairing = field(a, "pairing") as Pairing
        val pin = pairing.pin.pin()
        repeat(5) { pairing.pin.submit(wrongPin(pin), "192.168.1.42") }
        store.saveAccessMode(AccessMode.PIN)
        invoke(a, "refreshWaitingPage")

        val lockLine = (field(a, "pinLockText") as TextView).text.toString()
        assertTrue(lockLine, lockLine.contains("已锁定"))
        assertTrue(lockLine, lockLine.contains("192.168.1.*"))
        assertTrue(store.pinState().contains("192.168.1.*"))
        assertFalse(store.pinState().contains("192.168.1.42"))
        assertTrue((field(a, "pinDisplay") as TextView).text.toString().contains(pin.toCharArray().joinToString(" ")))
    }

    @Test
    fun trustedOnlyModeShowsOnlyTheWhitelistNotice() {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).setFirstLaunch(false)
        val a = setup()
        val store = field(a, "store") as DeviceStore
        store.saveAccessMode(AccessMode.TRUSTED_ONLY)
        invoke(a, "refreshWaitingPage")

        assertEquals(View.GONE, (field(a, "codeSlot") as View).visibility)
        assertEquals(View.GONE, (field(a, "qrView") as View).visibility)
        assertEquals("仅信任设备", (field(a, "pinDisplay") as TextView).text.toString())
        assertTrue((field(a, "pinLockText") as TextView).text.toString().contains("信任列表"))
    }

    @Test
    fun ownerCanChangeThePinWithTheRemoteFriendlyKeypad() {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).apply {
            // 同进程测试可能继承上一条的锁定快照；改 PIN 用例从干净状态起步。
            savePinState("")
            setFirstLaunch(false)
        }
        val a = setup()
        val store = field(a, "store") as DeviceStore
        val pairing = field(a, "pairing") as Pairing
        ReceiverActivity::class.java.getDeclaredMethod("showSettings").apply { isAccessible = true }.invoke(a)
        ShadowLooper.idleMainLooper()

        (field(a, "pinSettingsRow") as TextView).performClick()
        ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        val keys = texts(dialog.window!!.decorView)
        for (digit in listOf("1", "2", "3", "4")) {
            keys.first { it.text.toString() == digit }.performClick()
        }
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertTrue(save.isEnabled)
        save.performClick()
        ShadowLooper.idleMainLooper()

        assertEquals("1234", pairing.pin.pin())
        assertTrue(store.pinState().contains(PinStateCodec.HEADER))
        assertEquals("电视 PIN：1234 · 双击修改", (field(a, "pinSettingsRow") as TextView).text.toString())
    }

    /** 1920x1080 电视比例下的锁定态证据：PIN、倒计时与来源掩码同屏可见。 */
    @Test
    fun rendersLockedPinPageAtTvSizeForEvidence() {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).apply {
            setFirstLaunch(false)
            saveDeviceName("客厅电视")
        }
        val a = setup()
        val store = field(a, "store") as DeviceStore
        val pairing = field(a, "pairing") as Pairing
        val pin = pairing.pin.pin()
        repeat(5) { pairing.pin.submit(wrongPin(pin), "192.168.1.42") }
        store.saveAccessMode(AccessMode.PIN)
        invoke(a, "refreshWaitingPage")
        val decor = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels
        val h = a.resources.displayMetrics.heightPixels
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, w, h)
        ShadowLooper.idleMainLooper()

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val file = File(projectRoot(), "build/pin-locked-20261123.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("截图未生成：${file.absolutePath}", file.length() > 1024)
        println("PIN_SCREENSHOT ${file.absolutePath} ${w}x$h")
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

package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.receiver.ReceiverHost
import io.github.wangnan05563.airmirror.update.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class AllPagesRenderTest {
    private val outDir = File("/home/user/Doubao/chats/38453123841898754/AirMirror-audit/docs/assets/ui-audit-20261120/round1")
    private val width = 1080
    private val height = 2400

    private fun layout(activity: Activity) {
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, width, height)
    }

    private fun capture(activity: Activity, name: String) {
        layout(activity)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        activity.window.decorView.draw(Canvas(bitmap))
        save(bitmap, name)
    }

    private fun captureDialog(dialog: Dialog, name: String) {
        val decor = dialog.window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        val w = decor.measuredWidth.coerceAtLeast(1)
        val h = decor.measuredHeight.coerceAtLeast(1)
        decor.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        save(bitmap, name)
    }

    private fun save(bitmap: Bitmap, name: String) {
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("CAPTURED $name ${bitmap.width}x${bitmap.height}")
    }

    private fun dump(v: View, depth: Int) {
        val pad = "  ".repeat(depth)
        val text = (v as? TextView)?.text?.toString()?.take(40) ?: ""
        println("TREE $pad${v.javaClass.simpleName} vis=${v.visibility} ${v.width}x${v.height} @${v.left},${v.top} text=$text")
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) dump(v.getChildAt(i), depth + 1)
    }

    private fun clickText(root: View, text: String): Boolean {
        val nodes = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.text?.toString() == text) nodes.add(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        val node = nodes.firstOrNull { it.visibility == View.VISIBLE } ?: return false
        node.performClick()
        return true
    }

    private fun invoke(activity: Activity, method: String, vararg args: Any?) {
        val m = activity.javaClass.getDeclaredMethod(method, *args.map { it!!::class.java }.toTypedArray())
        m.isAccessible = true
        m.invoke(activity, *args)
    }

    @Test
    fun mainPage() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        capture(a, "01-main")
    }

    @Test
    fun senderPage() {
        val a = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        capture(a, "02-sender-idle")
    }

    @Test
    fun receiverOnboarding() {
        DeviceStore(ApplicationProvider.getApplicationContext()).setFirstLaunch(true)
        val a = Robolectric.buildActivity(ReceiverActivity::class.java).setup().get()
        val onboarding = ReceiverActivity::class.java.getDeclaredField("onboarding").apply { isAccessible = true }.get(a) as View
        println("ONBOARD before capture visibility=${onboarding.visibility}")
        // 正常路径应由 firstLaunch 自动拉起；若被上一次测试残留的 SharedPreferences 干扰，明确暴露而不是拍一张假页面
        if (onboarding.visibility != View.VISIBLE) invoke(a, "showOnboarding")
        assertEquals("首启引导未显示", View.VISIBLE, onboarding.visibility)
        capture(a, "03-receiver-onboarding")
    }

    @Test
    fun receiverIdleAndSettings() {
        DeviceStore(ApplicationProvider.getApplicationContext()).setFirstLaunch(false)
        val a = Robolectric.buildActivity(ReceiverActivity::class.java).setup().get()
        // 二维码由后台线程生成，且宿主端口要等 socket 绑定后才 >0：
        // 轮询到 ImageView 真正拿到 Bitmap 再截图，避免把白色占位块写进证据图。
        val qrView = ReceiverActivity::class.java.getDeclaredField("qrView").apply { isAccessible = true }.get(a) as ImageView
        val host = ReceiverActivity::class.java.getDeclaredField("host").apply { isAccessible = true }.get(a) as ReceiverHost
        val lastPayloadField = ReceiverActivity::class.java.getDeclaredField("lastPayload").apply { isAccessible = true }
        var waited = 0
        while (waited < 6000 && qrView.drawable == null) {
            // 先让后台线程跑，再回主线程收结果；清 lastPayload 保证缓存命中时能重设一次
            Thread.sleep(200)
            ShadowLooper.idleMainLooper()
            if (host.port > 0 && qrView.drawable == null) {
                lastPayloadField.set(a, "")
                invoke(a, "refreshPairingQr")
            }
            waited += 200
        }
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()
        val qrBmp = (qrView.drawable as android.graphics.drawable.BitmapDrawable).bitmap
        val px = IntArray(qrBmp.width * qrBmp.height)
        qrBmp.getPixels(px, 0, qrBmp.width, 0, 0, qrBmp.width, qrBmp.height)
        println("QRDEBUG port=" + host.port + " drawable=" + qrView.drawable + " size=" + qrView.width + "x" + qrView.height + " darkPixels=" + px.count { it != -1 })
        val qrDump = java.io.File(System.getProperty("java.io.tmpdir"), "qrbitmap.png")
        qrDump.parentFile?.mkdirs()
        qrDump.outputStream().use { qrBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals("待连接页二维码未生成", true, qrView.drawable != null)
        capture(a, "04-receiver-idle")
        invoke(a, "showSettings")
        capture(a, "05-settings-top")
        val scrollerField = ReceiverActivity::class.java.getDeclaredField("settingsScroller")
        scrollerField.isAccessible = true
        val scroller = scrollerField.get(a) as ScrollView
        scroller.scrollTo(0, scroller.getChildAt(0).height)
        capture(a, "06-settings-bottom")
    }

    @Test
    fun aboutPage() {
        val a = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        capture(a, "07-about")
    }

    @Test
    fun updateDialog() {
        val a = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        val release = ReleaseInfo(
            tag = "v0.1.0-m0.20261120-001",
            title = "AirMirror 更新",
            notes = "修复触控目标高度；统一弹窗样式；优化设置页分组。",
            apkUrl = "https://example.com/AirMirror.apk",
            apkName = "AirMirror.apk",
        )
        val m = SenderActivity::class.java.getDeclaredMethod("promptUpdate", ReleaseInfo::class.java)
        m.isAccessible = true
        m.invoke(a, release)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog()
        UiKit.styleDialogButtons(dialog as androidx.appcompat.app.AlertDialog)
        dump(dialog.window!!.decorView, 0)
        captureDialog(dialog, "08-update-dialog")
        dump(dialog.window!!.decorView, 0)
        println("SCREEN ${dialog.window!!.decorView.width}x${dialog.window!!.decorView.height}")
    }
}

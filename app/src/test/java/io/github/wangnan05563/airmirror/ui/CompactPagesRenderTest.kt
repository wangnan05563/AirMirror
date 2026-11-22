package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.receiver.ReceiverHost
import io.github.wangnan05563.airmirror.update.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 360×640dp 小屏回归：E 盘模拟器实测暴露的四类裁切问题，用同一套页面代码在 JVM 上复现并守住。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-320dpi")
class CompactPagesRenderTest {
    private val outDir = File("/home/user/Doubao/chats/38453123841898754/AirMirror-audit/docs/assets/ui-audit-20261120/round360")
    private val width = 720
    private val height = 1280

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
        println("CAPTURED360 $name ${bitmap.width}x${bitmap.height}")
    }

    private fun invoke(activity: Activity, method: String, vararg args: Any?) {
        val m = activity.javaClass.getDeclaredMethod(method, *args.map { it!!::class.java }.toTypedArray())
        m.isAccessible = true
        m.invoke(activity, *args)
    }

    private fun field(activity: Activity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun findText(root: View, target: String): TextView? {
        if (root is TextView && root.text?.toString() == target) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findText(root.getChildAt(i), target)?.let { return it }
        return null
    }

    private fun assertInsideScreen(name: String, v: View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val bottom = loc[1] + v.height
        println("BOUNDS360 $name top=${loc[1]} bottom=$bottom h=${v.height} screen=$height")
        assertTrue("$name 超出 360×640 首屏: bottom=$bottom", bottom <= height)
    }

    @Test
    fun mainAndAbout() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        capture(main, "01-main")
        assertInsideScreen("主入口-关于入口", findText(main.window.decorView, "关于 AirMirror")!!)

        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        capture(about, "07-about")
        assertInsideScreen("关于页-项目主页", findText(about.window.decorView, "打开项目主页")!!)
        assertTrue("关于页隐私说明应在首屏", findText(about.window.decorView, "投屏内容只在本次会话内使用，不保存画面、文件名与地址。") != null)
    }

    @Test
    fun senderDiagnosisCardFits() {
        val a = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        val card = field(a, "diagnosisCard") as LinearLayout
        (field(a, "diagnosisHeadline") as TextView).text = "网络禁止设备互访"
        (field(a, "diagnosisNextStep") as TextView).text = "先允许互访，或用投屏码连接"
        card.visibility = View.VISIBLE
        capture(a, "02-sender-diagnosis")
        val entry = findText(a.window.decorView, "用投屏码连接")!!
        assertInsideScreen("发送端-用投屏码连接", entry)
    }

    @Test
    fun onboardingStep3Fits() {
        DeviceStore(ApplicationProvider.getApplicationContext()).setFirstLaunch(true)
        val a = Robolectric.buildActivity(ReceiverActivity::class.java).setup().get()
        val onboarding = field(a, "onboarding") as View
        if (onboarding.visibility != View.VISIBLE) invoke(a, "showOnboarding")
        val next = field(a, "onboardNext") as Button
        next.performClick()
        next.performClick()
        layout(a)
        val input = field(a, "onboardInput") as View
        assertEquals("第 3 步输入框应 48dp", 96, input.height)
        assertTrue("第 3 步完成按钮宽应 ≥48dp", next.width >= 96)
        capture(a, "03-onboarding-step3")
    }

    @Test
    fun receiverIdleFits() {
        DeviceStore(ApplicationProvider.getApplicationContext()).setFirstLaunch(false)
        val a = Robolectric.buildActivity(ReceiverActivity::class.java).setup().get()
        // 二维码后台生成；等 ImageView 真正拿到 Bitmap 再拍，避免白色占位块
        val qrView = field(a, "qrView") as ImageView
        val host = field(a, "host") as ReceiverHost
        val lastPayloadField = a.javaClass.getDeclaredField("lastPayload").apply { isAccessible = true }
        var waited = 0
        while (waited < 6000 && qrView.drawable == null) {
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
        capture(a, "04-receiver-idle")
        val more = findText(a.window.decorView, "更多信息")!!
        assertInsideScreen("接收端-更多信息", more)

        invoke(a, "showSettings")
        capture(a, "05-settings-top")
        val scroller = field(a, "settingsScroller") as ScrollView
        scroller.scrollTo(0, scroller.getChildAt(0).height)
        capture(a, "06-settings-bottom")
        val done = findText(a.window.decorView, "完成")!!
        assertInsideScreen("显示设置-完成", done)
    }

    @Test
    fun updateDialogButtonsMeet48dp() {
        val a = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        val release = ReleaseInfo(
            tag = "v0.1.0-m0.20261120-001",
            title = "AirMirror 更新",
            notes = "修复小屏触控与裁切；统一弹窗样式；优化设置页分组。",
            apkUrl = "https://example.com/AirMirror.apk",
            apkName = "AirMirror.apk",
        )
        val m = SenderActivity::class.java.getDeclaredMethod("promptUpdate", ReleaseInfo::class.java)
        m.isAccessible = true
        m.invoke(a, release)
        ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog()
        captureDialog(dialog, "08-update-dialog")
        val positive = (dialog as androidx.appcompat.app.AlertDialog).getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        assertTrue("下载按钮高度应 ≥48dp", positive.height >= 96)
    }
}

package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.receiver.ReceiverHost
import io.github.wangnan05563.airmirror.receiver.ReceiverSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * T06103：TV 关键页（配对确认、结束确认、设置、首启、入口页）的 D-pad 焦点链与返回键行为。
 * 焦点顺序用 nextFocusId 断言，不依赖模拟器几何搜索。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TvFocusUiTest {

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

    private fun setupReceiver(firstLaunch: Boolean = false): ReceiverActivity {
        DeviceStore(ApplicationProvider.getApplicationContext<Context>()).apply {
            setFirstLaunch(firstLaunch)
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

    /**
     * Robolectric 没有真实 ViewRootImpl，`Activity.currentFocus` 恒为 null；
     * 直接读 decor 树的 focus 标记，才能区分"requestFocus 落空"和"窗口没接管"。
     */
    private fun focusedId(a: Activity): Int? = a.window.decorView.findFocus()?.id

    private fun field(a: Activity, name: String): Any {
        val f = ReceiverActivity::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(a)!!
    }

    private fun invoke(a: Activity, name: String, vararg args: Any) {
        val method = ReceiverActivity::class.java.declaredMethods.first {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        method.invoke(a, *args)
        ShadowLooper.idleMainLooper()
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
        println("TV_FOCUS_SCREENSHOT ${file.absolutePath} ${w}x$h")
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根：${System.getProperty("user.dir")}")
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-320dpi")
    fun promptFocusesDenyAndBackDenies() {
        val a = setupReceiver()
        invoke(a, "showPrompt", ReceiverSession.Prompt("peer-1", "小米手机", 0L))
        layout(a)
        val bar = field(a, "promptBar") as LinearLayout
        val deny = field(a, "promptDeny") as View
        val allow = field(a, "promptAllow") as View
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals("拒绝必须是默认焦点", deny.id, focusedId(a))
        // 焦点态必须是 TvFocus 的 2dp 描边层，而不是被 MaterialButton 的 tint 盖回按钮底色
        assertTrue("焦点拒绝必须换成描边背景", deny.background is GradientDrawable)
        assertNull("焦点态必须摘掉按钮 tint", deny.backgroundTintList)
        assertEquals("拒绝→右→允许", allow.id, deny.nextFocusRightId)
        assertEquals("允许→左→拒绝", deny.id, allow.nextFocusLeftId)
        capture(a, "tv-layout-1080p-pairing-20261123")

        a.onBackPressed()
        ShadowLooper.idleMainLooper()
        assertEquals("返回键应拒绝并收起确认条", View.GONE, bar.visibility)
        assertEquals("收起后焦点回到显示设置入口", (field(a, "settingsEntry") as View).id, focusedId(a))
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-320dpi")
    fun endConfirmFocusesCancelAndBackCancels() {
        val a = setupReceiver()
        invoke(a, "onCasting", true, "小米手机")
        invoke(a, "showEndConfirm")
        layout(a)
        val bar = field(a, "endBar") as LinearLayout
        val cancel = field(a, "endCancelButton") as View
        val confirm = field(a, "endConfirmButton") as View
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals("取消必须是默认焦点", cancel.id, focusedId(a))
        assertEquals("取消→右→结束", confirm.id, cancel.nextFocusRightId)
        assertEquals("结束→左→取消", cancel.id, confirm.nextFocusLeftId)

        a.onBackPressed()
        ShadowLooper.idleMainLooper()
        assertEquals("返回键应取消结束确认", View.GONE, bar.visibility)
        assertEquals("取消后焦点回到显示设置入口", (field(a, "settingsEntry") as View).id, focusedId(a))
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-320dpi")
    fun settingsRowsAreLinkedInVisualOrder() {
        val a = setupReceiver()
        invoke(a, "showSettings")
        layout(a)
        val overlay = field(a, "settingsOverlay") as View
        val modeRows = field(a, "modeRows") as Map<*, *>
        val rows = modeRows.values.toList()
        val first = rows[0] as View
        val second = rows[1] as View
        assertEquals("设置页第一行→下→第二行", second.id, first.nextFocusDownId)
        assertEquals("设置页第二行→上→第一行", first.id, second.nextFocusUpId)

        a.onBackPressed()
        ShadowLooper.idleMainLooper()
        assertEquals("返回键关闭设置面板", View.GONE, overlay.visibility)
        assertEquals("返回后焦点回到显示设置入口", (field(a, "settingsEntry") as View).id, focusedId(a))
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-320dpi")
    fun onboardingChainFollowsStepAndBackReturnsPreviousStep() {
        val a = setupReceiver(firstLaunch = true)
        layout(a)
        val onboarding = field(a, "onboarding") as View
        val skipAll = field(a, "onboardSkipAll") as View
        val next = field(a, "onboardNext") as View
        assertEquals(View.VISIBLE, onboarding.visibility)
        assertEquals("第一步：跳过全部→下→下一步", next.id, skipAll.nextFocusDownId)
        assertEquals("第一步：下一步→上→跳过全部", skipAll.id, next.nextFocusUpId)
        assertEquals("首启默认焦点在下一步", next.id, focusedId(a))

        invoke(a, "advanceOrFinish")
        layout(a)
        val autoToggle = field(a, "onboardAutoToggle") as View
        assertEquals("第二步：跳过全部→下→自启开关", autoToggle.id, skipAll.nextFocusDownId)
        assertEquals("第二步：自启开关→下→下一步", next.id, autoToggle.nextFocusDownId)
        assertEquals(2, field(a, "onboardStep"))
        assertEquals("第二步默认焦点在下一步", next.id, focusedId(a))

        a.onBackPressed()
        ShadowLooper.idleMainLooper()
        assertEquals("返回键回到上一步", 1, field(a, "onboardStep"))
        assertEquals("返回第一步焦点回下一步", next.id, focusedId(a))
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-320dpi")
    fun primaryButtonRestoresFillAndTextAfterFocusLeaves() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        layout(a)
        val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
        val send = root.getChildAt(2) as android.widget.TextView
        val receive = root.getChildAt(3) as View
        assertEquals("首焦点主按钮文字必须亮色", Theme.TEXT_PRIMARY, send.currentTextColor)
        capture(a, "t06129-main-1080p-focus-20261126")

        receive.requestFocus()
        ShadowLooper.idleMainLooper()
        assertEquals("失焦后文字必须恢复主按钮原色", Theme.component.buttonPrimaryFg, send.currentTextColor)
        capture(a, "t06129-main-1080p-primary-unfocused-20261126")
        val bitmap = Bitmap.createBitmap(send.width, send.height, Bitmap.Config.ARGB_8888)
        send.draw(Canvas(bitmap))
        assertEquals(
            "失焦后主按钮必须恢复品牌青绿底，不能退回黑色默认底",
            Theme.component.buttonPrimaryBg,
            bitmap.getPixel(send.width / 4, send.height / 2),
        )
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-320dpi")
    fun mainEntryFollowsVisualChain() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        layout(a)
        val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
        val send = root.getChildAt(2) as View
        val receive = root.getChildAt(3) as View
        val privacy = root.getChildAt(6) as View
        val about = root.getChildAt(7) as View
        assertEquals("投屏→下→接收", receive.id, send.nextFocusDownId)
        assertEquals("接收→下→数据边界", privacy.id, receive.nextFocusDownId)
        assertEquals("数据边界→下→关于", about.id, privacy.nextFocusDownId)
        // TV 首焦点会把主按钮背景换成深蓝焦点层；文字必须切到亮色，
        // 否则 buttonPrimaryFg（background）与焦点底色同色，按钮文字不可见（T06129 1080p 实测）。
        assertEquals(
            "TV 焦点主按钮必须使用亮色文字",
            Theme.TEXT_PRIMARY,
            (send as android.widget.TextView).currentTextColor,
        )
    }
}

package io.github.wangnan05563.airmirror.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.wangnan05563.airmirror.net.EmptyNetworkCheck
import io.github.wangnan05563.airmirror.net.NetworkDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.File
import java.io.FileOutputStream

/**
 * T06107 / AC-01：把 T06106 的链路判定接入页面后，飞行模式与 15 秒无结果都要在真实页面上
 * 显示对应原因，而不是仍停留在“搜索中”的无设备文案。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-320dpi")
class SenderEmptyNetworkUiTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun connectivity(): ConnectivityManager =
        ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun setWifiConnected() {
        val info = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            true,
        )
        Shadows.shadowOf(connectivity()).setActiveNetworkInfo(info)
    }

    private fun setNoNetwork() {
        Shadows.shadowOf(connectivity()).setActiveNetworkInfo(null)
    }

    @Test
    fun `飞行模式 360dp 显示手机没有联网`() {
        setNoNetwork()
        val a = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        layout(a, 720, 1280)
        val card = field(a, "diagnosisCard") as ErrorStateCard
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals("手机没有联网", card.headline.text.toString())
        assertEquals("错误码 AM-NET-04", card.errorCode.text.toString())
        assertTrue("必须给出打开网络的入口", findText(a.window.decorView, "去设置")?.visibility == View.VISIBLE)
        assertTrue("必须允许重试发现", findText(a.window.decorView, "重试发现")?.visibility == View.VISIBLE)
        save(a, "sender-empty-no-network-360dp")
    }

    @Test
    fun `十五秒无接收端显示没有发现接收端`() {
        setWifiConnected()
        val a = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        // Robolectric 的 elapsedRealtime 起点很小，直接减 16 秒会变成负数；用可控时钟表达 15 秒窗口。
        set(
            a,
            "network",
            NetworkDiagnostics(ApplicationProvider.getApplicationContext()) { 20_000L },
        )
        set(a, "searchStartedAt", 1L)
        invoke(a, "renderEmptyState")
        layout(a, 720, 1280)
        val card = field(a, "diagnosisCard") as ErrorStateCard
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals("没有发现接收端", card.headline.text.toString())
        assertEquals("错误码 AM-ND-02", card.errorCode.text.toString())
        save(a, "sender-empty-no-receiver-360dp")
    }

    @Test
    fun `空态接线顺序与超时窗口来自 T06106`() {
        val sender = File(
            projectRoot(),
            "app/src/main/java/io/github/wangnan05563/airmirror/ui/SenderActivity.kt",
        ).readText()
        assertTrue("必须先跑网络判决", sender.contains("network.check("))
        assertTrue("15 秒窗口必须由页面重排", sender.contains("EmptyNetworkCheck.NO_RESULT_TIMEOUT_MS"))
        assertTrue("重试发现要重置窗口", sender.contains("restartEmptyWindow()"))
        assertTrue("无网络要接系统无线设置", sender.contains("Settings.ACTION_WIRELESS_SETTINGS"))
        assertTrue("旧三动作空态仍在", sender.contains("diagnosisCard.bind(cause, EmptyDiagnosis.ACTIONS)"))
    }

    private fun save(activity: android.app.Activity, name: String) {
        val outDir = File(projectRoot(), "docs/assets/m1-regression-20261124").apply { mkdirs() }
        val file = File(outDir, "$name-20261124.png")
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        activity.window.decorView.draw(Canvas(bitmap))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("截图必须落盘：${file.name}", file.length() > 1_000L)
    }

    private fun layout(activity: android.app.Activity, width: Int, height: Int) {
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, width, height)
    }

    private fun set(activity: android.app.Activity, name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    private fun field(activity: android.app.Activity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun invoke(activity: android.app.Activity, name: String) {
        activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }

    private fun findText(root: View, target: String): TextView? {
        if (root is TextView && root.text?.toString() == target) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            findText(root.getChildAt(i), target)?.let { return it }
        }
        return null
    }
}

package io.github.wangnan05563.airmirror.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** T06105 页面级回归：默认扫码、无相机/识别失败切投屏码，以及自动选中的源码接线点。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-320dpi")
class SenderTwoStepUiTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun uiDir() = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui")

    private fun save(bitmap: Bitmap, name: String) {
        val outDir = File(projectRoot(), "docs/assets/two-step-cast-20261123").apply { mkdirs() }
        val file = File(outDir, "$name-20261123.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("截图必须落盘：${file.name}", file.length() > 1_000L)
    }

    @Test
    fun `相机可用时默认露出扫码`() {
        val a = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        set(a, "cameraAvailable", true)
        set(a, "entryMode", TwoStepCastPolicy.EntryMode.SCAN)
        set(a, "entryReason", "")
        invoke(a, "refreshEntryVisibility")
        layout(a)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, (field(a, "scanRow") as View).visibility)
        assertEquals(View.GONE, (field(a, "codeRow") as View).visibility)
        assertTrue("扫码按钮必须在首屏", visible(a.window.decorView, "扫码连接"))
        assertTrue("必须有投屏码回退入口", visible(a.window.decorView, "改用投屏码"))
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        a.window.decorView.draw(Canvas(bitmap))
        save(bitmap, "two-step-scan-360dp")
    }

    @Test
    fun `扫码失败切换投屏码并保留输入框`() {
        val a = Robolectric.buildActivity(SenderActivity::class.java).setup().get()
        set(a, "cameraAvailable", true)
        set(a, "entryMode", TwoStepCastPolicy.EntryMode.CODE)
        set(a, "entryReason", "没识别到投屏二维码，已切换到投屏码")
        invoke(a, "refreshEntryVisibility")
        layout(a)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(View.GONE, (field(a, "scanRow") as View).visibility)
        assertEquals(View.VISIBLE, (field(a, "codeRow") as View).visibility)
        val input = field(a, "codeInput") as EditText
        assertEquals(View.VISIBLE, input.visibility)
        assertEquals("没识别到投屏二维码，已切换到投屏码", (field(a, "entryNote") as TextView).text.toString())
        assertTrue("投屏码回退必须有提交按钮", visible(a.window.decorView, "用码连接"))
        assertTrue("允许返回扫码重试", visible(a.window.decorView, "返回扫码"))
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        a.window.decorView.draw(Canvas(bitmap))
        save(bitmap, "two-step-code-360dp")
    }

    @Test
    fun `自动选中只在首次稳定窗口执行并记录成功设备`() {
        val sender = File(uiDir(), "SenderActivity.kt").readText()
        assertTrue("自动选中必须接纯策略层", sender.contains("TwoStepCastPolicy.autoTarget("))
        assertTrue("自动连接只能尝试一次", sender.contains("autoAttempted = true"))
        assertTrue("首帧成功才写上次设备", sender.contains("store.saveLastCastTarget("))
        assertTrue("扫码结果必须落到一次性令牌连接", sender.contains("connectTo(target, link.token"))
        assertFalse("不得用异常类名/堆栈占位", sender.contains("printStackTrace"))
    }

    @Test
    fun `扫码不新增相机权限`() {
        val manifest = File(projectRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertFalse("T06105 走系统相机，不应声明 CAMERA 权限", manifest.contains("android.permission.CAMERA"))
        assertTrue("提供一次性拍照输出 provider", manifest.contains("androidx.core.content.FileProvider"))
        assertTrue("Android 11 包可见性要能解析系统相机", manifest.contains("android.media.action.IMAGE_CAPTURE"))
        assertTrue("拍照目录限定在 cache", File(projectRoot(), "app/src/main/res/xml/qr_file_paths.xml").readText().contains("cache-path"))
    }

    private fun layout(activity: android.app.Activity) {
        activity.window.decorView.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
        )
        activity.window.decorView.layout(0, 0, 720, 1280)
    }

    private fun set(activity: android.app.Activity, name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    private fun field(activity: android.app.Activity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun invoke(activity: android.app.Activity, name: String) {
        activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }

    private fun visible(root: View, text: String): Boolean = findText(root, text)?.visibility == View.VISIBLE

    private fun findText(root: View, target: String): TextView? {
        if (root is TextView && root.text?.toString() == target) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) {
            findText(root.getChildAt(i), target)?.let { return it }
        }
        return null
    }
}

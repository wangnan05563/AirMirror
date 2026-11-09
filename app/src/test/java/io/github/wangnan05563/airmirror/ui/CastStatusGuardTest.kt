package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码守卫：UX-03 六态状态条必须走 CastStatus 共享文案表。
 * 退回「各界面各写一份中文状态串」、给控制条加按钮、或让状态表依赖 android.* 都在这里失败。
 */
class CastStatusGuardTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "AirMirror-PRD-v1.1.md").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun uiDir() = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui")

    private fun sender() = File(uiDir(), "SenderActivity.kt").readText()

    private fun receiver() = File(uiDir(), "ReceiverActivity.kt").readText()

    private fun status() = File(uiDir(), "CastStatus.kt").readText()

    @Test
    fun `发送端三态走共享文案表 不留本地中文串`() {
        val src = sender()
        assertTrue(src.contains("CastStatus.line(CastStatus.State.CONNECTING)"))
        assertTrue(src.contains("CastStatus.line(CastStatus.State.CASTING, if (token != null) "跨网设备" else target.name)"))
        assertTrue(src.contains("CastStatus.line(CastStatus.State.PAUSED)"))
        assertFalse("连接中文案不得再写第二份", src.contains("\"正在连接…\""))
        assertFalse("投屏中文案不得再写第二份", src.contains("\"正在投屏到"))
        assertFalse("常量副本已删", src.contains("const val CONNECTING"))
    }

    @Test
    fun `接收端恢复提示走共享文案表`() {
        val src = receiver()
        assertTrue(src.contains("CastStatus.line(CastStatus.State.RECOVERING)"))
        assertFalse("恢复中中文案不得再写第二份", src.contains("\"连接中断，正在恢复\""))
    }

    @Test
    fun `暂停只在无错误时接管状态条 恢复后还原投屏目标`() {
        val src = sender()
        assertTrue("暂停提示必须让位给错误文案", src.contains("if (SenderReport.last.isEmpty())"))
        assertTrue("恢复后要回到画质文案", src.contains("SenderReport.quality.ifEmpty { CastStatus.castingLine() }"))
    }

    @Test
    fun `控制条仍固定三个按钮`() {
        val src = sender()
        assertEquals("控制条按钮数不得扩容", 3, src.split("controlBar.addView").size - 1)
    }

    @Test
    fun `状态表保持纯逻辑不依赖 android`() {
        val src = status()
        assertFalse(src.contains("import android."))
        assertFalse(src.contains("import androidx."))
        assertTrue("错误态文案必须交给 ErrorNotice", src.contains("State.ERROR -> null"))
    }
}

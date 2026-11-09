package io.github.wangnan05563.airmirror.ui

import io.github.wangnan05563.airmirror.receiver.RecoveryWindow
import io.github.wangnan05563.airmirror.sender.RecoverySchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD UX-03：六态枚举与共享文案表的纯逻辑单测。 */
class CastStatusTest {

    @Test
    fun `六态齐全且逐态有唯一文案`() {
        assertEquals(6, CastStatus.State.values().size)
        val lines = CastStatus.State.values().mapNotNull { CastStatus.line(it, "电视") }
        assertEquals("除错误态外五态都要有文案", 5, lines.size)
        assertEquals("状态文案不得撞车", 5, lines.toSet().size)
    }

    @Test
    fun `错误态不另造措辞 文案由 ErrorNotice 提供`() {
        assertNull(CastStatus.line(CastStatus.State.ERROR))
    }

    @Test
    fun `投屏态随目标收敛 无目标时不留悬空介词`() {
        assertEquals("正在投屏", CastStatus.line(CastStatus.State.CASTING))
        assertEquals("正在投屏到客厅电视", CastStatus.line(CastStatus.State.CASTING, "客厅电视"))
        assertEquals("正在投屏到跨网设备", CastStatus.line(CastStatus.State.CASTING, "跨网设备"))
    }

    @Test
    fun `首行文案不超过 18 字`() {
        val lines = CastStatus.State.values().mapNotNull { CastStatus.line(it) }
        lines.forEach { assertTrue("文案过长：$it", it.length <= 18) }
    }

    @Test
    fun `控制条固定暂停 停止 声音三件事`() {
        assertEquals(3, CastStatus.CONTROLS.size)
        assertEquals(
            listOf(CastStatus.Control.PAUSE, CastStatus.Control.STOP, CastStatus.Control.SOUND),
            CastStatus.CONTROLS,
        )
    }

    @Test
    fun `恢复提示常驻到 30 秒窗口收口`() {
        assertTrue(CastStatus.recoveryVisible(0L))
        assertTrue(CastStatus.recoveryVisible(29_999L))
        assertFalse(CastStatus.recoveryVisible(30_000L))
        assertFalse(CastStatus.recoveryVisible(60_000L))
        assertFalse("负数时间不应视为窗口内", CastStatus.recoveryVisible(-1L))
    }

    @Test
    fun `30 秒窗口与既有 FS-10 口径同源`() {
        assertEquals(RecoverySchedule.WINDOW_MS, CastStatus.RECOVERY_WINDOW_MS)
        assertEquals(RecoveryWindow.DEFAULT_WINDOW_MS, CastStatus.RECOVERY_WINDOW_MS)
    }
}

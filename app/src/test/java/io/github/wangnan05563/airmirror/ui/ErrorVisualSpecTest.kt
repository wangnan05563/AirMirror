package io.github.wangnan05563.airmirror.ui

import io.github.wangnan05563.airmirror.error.ErrorNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T06101 纯逻辑：三套插画场景、稳定错误码、主/次/兜底、10 秒加载口径。 */
class ErrorVisualSpecTest {

    @Test
    fun `三类空态原因各自映射到唯一插画`() {
        assertEquals(ErrorVisualSpec.Scene.NO_DEVICE, ErrorVisualSpec.sceneFor(null))
        assertEquals(ErrorVisualSpec.Scene.NO_DEVICE, ErrorVisualSpec.sceneFor(ErrorNotice.Cause.NO_DISCOVERY))
        assertEquals(ErrorVisualSpec.Scene.OFFLINE, ErrorVisualSpec.sceneFor(ErrorNotice.Cause.DEVICE_OFFLINE))
        assertEquals(ErrorVisualSpec.Scene.NETWORK, ErrorVisualSpec.sceneFor(ErrorNotice.Cause.DISCOVERY_AP_ISOLATED))
        assertEquals(ErrorVisualSpec.Scene.NETWORK, ErrorVisualSpec.sceneFor(ErrorNotice.Cause.DISCOVERY_DUAL_BAND))
        assertEquals(ErrorVisualSpec.Scene.NETWORK, ErrorVisualSpec.sceneFor(ErrorNotice.Cause.UNREACHABLE))
        assertNull("码错误不硬套发现类插画", ErrorVisualSpec.sceneFor(ErrorNotice.Cause.CODE_EXPIRED))
    }

    @Test
    fun `错误码稳定且不复用`() {
        assertEquals("AM-ND-01", ErrorVisualSpec.codeFor(ErrorNotice.Cause.NO_DISCOVERY))
        assertEquals("AM-OFF-01", ErrorVisualSpec.codeFor(ErrorNotice.Cause.DEVICE_OFFLINE))
        assertEquals("AM-NET-01", ErrorVisualSpec.codeFor(ErrorNotice.Cause.DISCOVERY_AP_ISOLATED))
        assertEquals("AM-NET-02", ErrorVisualSpec.codeFor(ErrorNotice.Cause.DISCOVERY_DUAL_BAND))
        assertEquals("AM-NET-03", ErrorVisualSpec.codeFor(ErrorNotice.Cause.UNREACHABLE))
        assertEquals("未映射原因有兜底码", "AM-ERR-01", ErrorVisualSpec.codeFor(ErrorNotice.Cause.CODE_EXPIRED))
        val mapped = listOf(
            ErrorNotice.Cause.NO_DISCOVERY,
            ErrorNotice.Cause.DEVICE_OFFLINE,
            ErrorNotice.Cause.DISCOVERY_AP_ISOLATED,
            ErrorNotice.Cause.DISCOVERY_DUAL_BAND,
            ErrorNotice.Cause.UNREACHABLE,
        ).map(ErrorVisualSpec::codeFor)
        assertEquals("同一原因不能出现两个码", mapped.size, mapped.toSet().size)
    }

    @Test
    fun `第一枚动作主按钮第二枚次按钮其余兜底`() {
        assertEquals(ErrorVisualSpec.ActionRole.PRIMARY, ErrorVisualSpec.roleFor(0))
        assertEquals(ErrorVisualSpec.ActionRole.SECONDARY, ErrorVisualSpec.roleFor(1))
        assertEquals(ErrorVisualSpec.ActionRole.GHOST, ErrorVisualSpec.roleFor(2))
        assertEquals(ErrorVisualSpec.ActionRole.GHOST, ErrorVisualSpec.roleFor(9))
    }

    @Test
    fun `首次连接十秒前只显示进度语义`() {
        val early = ErrorVisualSpec.loading("客厅电视", 9_999L)
        assertEquals("正在连接客厅电视…", early.line)
        assertTrue(!early.showCancel)
        assertEquals("正在连接客厅电视…", ErrorVisualSpec.loading("客厅电视", -1L).line)
        assertEquals("没名字也不能空白", "正在连接接收端…", ErrorVisualSpec.loading("  ", 0L).line)
    }

    @Test
    fun `超过十秒显示仍在连接并露出取消`() {
        val late = ErrorVisualSpec.loading("客厅电视", ErrorVisualSpec.CANCEL_AFTER_MS)
        assertEquals("仍在连接客厅电视…", late.line)
        assertTrue(late.showCancel)
        assertTrue(ErrorVisualSpec.loading("客厅电视", 30_000L).showCancel)
    }

    @Test
    fun `长设备名截到二十字符仍保留仍在连接`() {
        val late = ErrorVisualSpec.loading("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 10_000L)
        assertEquals("仍在连接ABCDEFGHIJKLMNOPQRST…", late.line)
        assertTrue("前缀必须在", late.line.startsWith("仍在连接"))
        assertTrue("取消必须露出", late.showCancel)
    }
}

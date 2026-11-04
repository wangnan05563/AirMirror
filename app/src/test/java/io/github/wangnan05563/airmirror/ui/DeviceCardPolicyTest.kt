package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD §11.3 手机端卡片交互 + 7.3 错误提示规则的判据锁。 */
class DeviceCardPolicyTest {

    private fun card(online: Boolean = true, supported: Boolean = true, last: Long? = null) =
        DeviceCardPolicy.Card("fp-1", online, supported, last)

    @Test
    fun `点同一张收起 点另一张展开 同一时刻只有一张`() {
        assertEquals("fp-1", DeviceCardPolicy.toggle(null, "fp-1"))
        assertEquals(null, DeviceCardPolicy.toggle("fp-1", "fp-1"))
        assertEquals("fp-2", DeviceCardPolicy.toggle("fp-1", "fp-2"))
    }

    @Test
    fun `在线且支持的卡片展开两个动作，避免先连再选`() {
        assertEquals(
            listOf(DeviceCardPolicy.CardAction.MIRROR, DeviceCardPolicy.CardAction.PUSH_VIDEO),
            DeviceCardPolicy.actions(card()),
        )
    }

    @Test
    fun `离线与不支持的卡片不展开动作，只走原因提示`() {
        assertTrue(DeviceCardPolicy.actions(card(online = false)).isEmpty())
        assertTrue(DeviceCardPolicy.actions(card(supported = false)).isEmpty())
        assertTrue(DeviceCardPolicy.actions(card(online = false, supported = false)).isEmpty())
    }

    @Test
    fun `控制条只在投屏中出现且固定三件事`() {
        assertEquals(
            listOf(
                DeviceCardPolicy.ControlAction.PAUSE,
                DeviceCardPolicy.ControlAction.STOP,
                DeviceCardPolicy.ControlAction.AUDIO,
            ),
            DeviceCardPolicy.controlActions(true),
        )
        assertTrue(DeviceCardPolicy.controlActions(false).isEmpty())
    }

    @Test
    fun `暂停按钮文案跟随暂停态`() {
        assertEquals("暂停投屏", DeviceCardPolicy.pauseLabel(false))
        assertEquals("继续投屏", DeviceCardPolicy.pauseLabel(true))
    }

    @Test
    fun `离线卡片显示上次连接与可能原因`() {
        val now = 1_800_000_000_000L
        assertEquals("离线 · 还没连过", DeviceCardPolicy.offlineLabel(null, now))
        assertEquals("离线 · 还没连过", DeviceCardPolicy.offlineLabel(0L, now))
        assertTrue(DeviceCardPolicy.offlineLabel(now - 10_000L, now).contains("刚刚连过"))
        assertTrue(DeviceCardPolicy.offlineLabel(now - 5 * 60_000L, now).contains("5 分钟前"))
        assertTrue("未来时间戳不能出现负数", !DeviceCardPolicy.offlineLabel(now + 60_000L, now).contains("-"))
    }

    @Test
    fun `提示必须可执行 不能只报状态`() {
        val offline = DeviceCardPolicy.offlineHint()
        assertTrue(offline.contains("设备已离线"))
        assertTrue(offline.contains("投屏码") || offline.contains("IP"))
        val fallback = DeviceCardPolicy.pushVideoFallback()
        assertTrue(fallback.contains("暂未内置"))
        assertTrue("要给替代路径", fallback.contains("投屏按钮") || fallback.contains("镜像屏幕"))
        assertFalse("不能只写错误码", fallback == "不支持")
    }
}

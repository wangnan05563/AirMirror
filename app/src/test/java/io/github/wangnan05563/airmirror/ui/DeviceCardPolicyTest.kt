package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `标签三态互斥 离线优先于不支持 不支持优先于协议角标`() {
        assertEquals(
            DeviceCardPolicy.Tag(DeviceCardPolicy.TagKind.PROTOCOL, "DLNA 推送"),
            DeviceCardPolicy.tag(card(), "DLNA 推送"),
        )
        assertEquals(
            DeviceCardPolicy.Tag(DeviceCardPolicy.TagKind.OFFLINE, "离线"),
            DeviceCardPolicy.tag(card(online = false, supported = false), "DLNA 推送"),
        )
        assertEquals(
            DeviceCardPolicy.Tag(DeviceCardPolicy.TagKind.UNSUPPORTED, "不支持"),
            DeviceCardPolicy.tag(card(supported = false), "DLNA 推送"),
        )
        assertTrue("离线标记必须同时给形状线索", DeviceCardPolicy.needsOfflineMarker(card(online = false)))
        assertFalse(DeviceCardPolicy.needsOfflineMarker(card()))
    }

    @Test
    fun `最近使用只认 24 小时内的现有缓存时间`() {
        val now = 1_800_000_000_000L
        val day = DeviceCardPolicy.RECENT_WINDOW_MS
        assertTrue(DeviceCardPolicy.isRecent(now - 60_000L, now))
        assertTrue("正好在窗口边界仍算最近使用", DeviceCardPolicy.isRecent(now - day, now))
        assertFalse("超过窗口不再标记", DeviceCardPolicy.isRecent(now - day - 1, now))
        assertFalse("没有缓存时间不标记", DeviceCardPolicy.isRecent(null, now))
        assertFalse(DeviceCardPolicy.isRecent(0L, now))
        assertTrue("时钟回拨不该被判成很久以前", DeviceCardPolicy.isRecent(now + 60_000L, now))
    }

    @Test
    fun `副行离线说清原因 在线近期给最近使用 其余不占行`() {
        val now = 1_800_000_000_000L
        assertTrue(
            DeviceCardPolicy.subtitle(card(online = false, last = now - 5 * 60_000L), now)!!.contains("上次连接"),
        )
        assertEquals("最近使用", DeviceCardPolicy.subtitle(card(last = now - 60_000L), now))
        assertEquals(null, DeviceCardPolicy.subtitle(card(last = now - 3 * DeviceCardPolicy.RECENT_WINDOW_MS), now))
        assertEquals(null, DeviceCardPolicy.subtitle(card(), now))
    }

    @Test
    fun `展开动作行与触控区固定 48dp 换卡不跳`() {
        assertEquals(48, DeviceCardPolicy.TOUCH_TARGET_DP)
        assertEquals(48, DeviceCardPolicy.ACTION_ROW_DP)
        assertTrue("触控区不得低于 48dp", DeviceCardPolicy.TOUCH_TARGET_DP >= 48)
        assertTrue("圆角标签半径要落在 UI-01 卡片圆角量级", DeviceCardPolicy.TAG_RADIUS_DP in 8..16)
    }

    @Test
    fun `标签衬底与文字仍过 AA`() {
        for (kind in DeviceCardPolicy.TagKind.values()) {
            val chip = StatusColors.composite(DeviceCardTag.backgroundColor(kind), Theme.SURFACE)
            val fg = if (kind == DeviceCardPolicy.TagKind.PROTOCOL) Theme.ACCENT else StatusColors.offlineText()
            assertTrue(
                "$kind 标签文字低于 4.5:1（实测 ${StatusColors.contrast(fg, chip)}）",
                StatusColors.contrast(fg, chip) >= StatusColors.BODY_MIN,
            )
        }
    }

    @Test
    fun `卡片标签走平台圆角绘制 不使用位图`() {
        val tag = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/DeviceCardTag.kt").readText()
        assertTrue("标签必须用 GradientDrawable 画圆角", tag.contains("GradientDrawable()"))
        assertTrue(tag.contains("cornerRadius"))
        assertFalse("不引位图", tag.contains("R.drawable") || tag.contains("BitmapFactory"))
        val sender = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/SenderActivity.kt").readText()
        assertTrue("卡片标签统一走 DeviceCardTag.chip", sender.contains("DeviceCardTag.chip("))
        assertTrue("卡片头行按 48dp 触控区", sender.contains("DeviceCardPolicy.TOUCH_TARGET_DP"))
        assertTrue("展开动作行高度固定", sender.contains("DeviceCardPolicy.ACTION_ROW_DP"))
        assertTrue("副行走 DeviceCardPolicy.subtitle", sender.contains("DeviceCardPolicy.subtitle("))
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "AirMirror-PRD-v1.1.md").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }
}

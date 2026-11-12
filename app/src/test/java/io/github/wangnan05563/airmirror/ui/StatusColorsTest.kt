package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** PRD UI-04：六态语义色、WCAG AA 对比度与「离线不靠灰度」的纯逻辑单测。 */
class StatusColorsTest {

    @Test
    fun `对比度公式对齐 WCAG 已知值`() {
        assertEquals("纯白对纯黑应为 21:1", 21.0, StatusColors.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
        assertEquals("同色应为 1:1", 1.0, StatusColors.contrast(Theme.BACKGROUND, Theme.BACKGROUND), 0.001)
        assertEquals("正文白在页底上 16.52:1", 16.52, StatusColors.contrast(Theme.TEXT_PRIMARY, Theme.BACKGROUND), 0.02)
        assertEquals("强调色在页底上 7.46:1", 7.46, StatusColors.contrast(Theme.ACCENT, Theme.BACKGROUND), 0.02)
        assertEquals("危险色在页底上 5.95:1", 5.95, StatusColors.contrast(Theme.DANGER, Theme.BACKGROUND), 0.02)
    }

    @Test
    fun `半透明令牌先合成实色再算比值`() {
        assertEquals("70% 白压深蓝底合成为 #B6BCC4", 0xFFB6BCC4.toInt(), StatusColors.composite(Theme.TEXT_SECONDARY, Theme.BACKGROUND))
        assertEquals("次级白在页底上 8.64:1", 8.64, StatusColors.contrast(Theme.TEXT_SECONDARY, Theme.BACKGROUND), 0.02)
    }

    @Test
    fun `六态语义色逐态达标 页底与恢复条都要过 4_5`() {
        val table = StatusColors.table()
        assertEquals("六态各一条色值", 6, table.size)
        assertEquals(CastStatus.State.values().toSet(), table.map { it.state }.toSet())
        table.forEach {
            assertTrue("正文低于 4.5:1：${it.state} 页底 ${it.onPage}", it.onPage >= StatusColors.BODY_MIN)
        }
        val recovering = StatusColors.textOf(CastStatus.State.RECOVERING)
        assertTrue("恢复条压在纯白画面上仍要过 AA", StatusColors.meetsAa(recovering, StatusColors.overVideo()))
    }

    @Test
    fun `语义取值固定 强调色表已连接 危险色表出错`() {
        assertEquals(Theme.ACCENT, StatusColors.textOf(CastStatus.State.CASTING))
        assertEquals(Theme.ACCENT, StatusColors.textOf(CastStatus.State.PAUSED))
        assertEquals(Theme.DANGER, StatusColors.textOf(CastStatus.State.ERROR))
        assertEquals(Theme.TEXT_SECONDARY, StatusColors.textOf(CastStatus.State.IDLE))
        assertEquals(Theme.TEXT_PRIMARY, StatusColors.textOf(CastStatus.State.CONNECTING))
        assertEquals(Theme.TEXT_PRIMARY, StatusColors.textOf(CastStatus.State.RECOVERING))
    }

    @Test
    fun `C 级设备静态高对比 全不透明且仍过 AA`() {
        StatusColors.table(highContrast = true).forEach {
            assertEquals("C 级不得用半透明状态字：${it.state}", 0xFF, (it.text ushr 24) and 0xFF)
            assertTrue("C 级对比度不足：${it.state}", it.onPage >= StatusColors.BODY_MIN)
        }
        assertEquals(Theme.TEXT_PRIMARY, StatusColors.textOf(CastStatus.State.IDLE, highContrast = true))
        assertEquals(Theme.TEXT_PRIMARY, StatusColors.secondaryText(highContrast = true))
        assertEquals(Theme.TEXT_SECONDARY, StatusColors.secondaryText(highContrast = false))
    }

    @Test
    fun `离线态不再靠低透明度文字表达`() {
        assertTrue("离线文字必须过正文 AA", StatusColors.meetsAa(StatusColors.offlineText(), Theme.BACKGROUND))
        assertTrue("画面之上也要过", StatusColors.meetsAa(StatusColors.offlineText(), StatusColors.overVideo()))
        assertFalse("原 30% 白只能做装饰：2.70:1 未达标", StatusColors.meetsAa(StatusColors.offlineMarker(), Theme.BACKGROUND))
        assertTrue("文字与装饰必须分开取", StatusColors.offlineText() != StatusColors.offlineMarker())
    }

    @Test
    fun `投屏浮层文字在任意画面之上仍达标`() {
        // 最差画面是纯白：80% 深蓝压上去后仍要与白字拉开 4.5:1
        val worst = StatusColors.composite(StatusColors.OVERLAY_LAYER, 0xFFFFFFFF.toInt())
        assertTrue("浮层白字最差 8.76:1", StatusColors.meetsAa(StatusColors.overlayText(), worst))
        assertFalse("强调色在那个底色上只有 3.95:1，不能当浮层字色", StatusColors.meetsAa(Theme.ACCENT, worst))
    }

    @Test
    fun `恢复条压白画面时 正文仍达标`() {
        assertEquals("90% 深蓝叠纯白合成为 #23354D", 0xFF23354D.toInt(), StatusColors.overVideo())
        assertEquals("正文白在恢复条上 12.44:1", 12.44, StatusColors.contrast(Theme.TEXT_PRIMARY, StatusColors.overVideo()), 0.02)
    }

    @Test
    fun `界面不再把低透明度令牌当文字色`() {
        listOf("MainActivity.kt", "SenderActivity.kt", "ReceiverActivity.kt").forEach { name ->
            val source = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/$name").readText()
            assertFalse("$name 仍用 Theme.OFFLINE 当文字色", source.contains("setTextColor(Theme.OFFLINE)"))
            assertFalse("$name 仍用 Theme.OFFLINE 当输入提示色", source.contains("setHintTextColor(Theme.OFFLINE)"))
        }
        val sender = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/SenderActivity.kt").readText()
        assertTrue("发送端状态行必须按六态取色", sender.contains("StatusColors.textOf("))
        val receiver = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/ReceiverActivity.kt").readText()
        assertTrue("接收端浮层文字改用 StatusColors.overlayText", receiver.contains("StatusColors.overlayText()"))
        assertTrue("C 级高对比判定接入接收端", receiver.contains("highContrastUi"))
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }
}

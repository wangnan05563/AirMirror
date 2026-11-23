package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-15 用户口径的源码守卫：
 * 1. 全站按钮统一走开源库 UI（MaterialButton，经 [UiKit.button] 创建），不再直接 new 平台 Button；
 * 2. 标题 / 正文按角色取字号（[FocusPolicy.titleSp] / [FocusPolicy.bodySp]），各页面不各写一套；
 * 3. 同一屏并排的按钮之间必须留间距（主入口 16dp，其余 8dp）。
 */
class UiConsistencyGuardTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun ui(name: String): String =
        File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/$name").readText()

    private val pages = listOf("MainActivity.kt", "SenderActivity.kt", "ReceiverActivity.kt")

    @Test
    fun `三个页面不再直接创建平台 Button`() {
        for (page in pages) {
            assertFalse("$page 仍在直接 new 平台 Button", Regex("""\bButton\(this""").containsMatchIn(ui(page)))
        }
    }

    @Test
    fun `按钮统一由 UiKit 创建`() {
        for (page in pages) {
            assertTrue("$page 未走 UiKit.button", ui(page).contains("UiKit.button("))
        }
    }

    @Test
    fun `主入口两个按钮之间留 24dp`() {
        val src = ui("MainActivity.kt")
        assertTrue("投到其他设备按钮未设置下间距", src.contains("UiKit.fullWidth(this, Theme.SPACE_XL_DP, Theme.SPACE_XL_DP)"))
        assertTrue("接收按钮未设置底部留边", src.contains("UiKit.fullWidth(this, 0, Theme.SPACE_XL_DP)"))
    }

    @Test
    fun `并排按钮统一留 8dp 间距`() {
        // 接收端 允许/拒绝、结束/取消；发送端 接管 让出/拒绝；卡片动作按钮
        assertTrue(ui("ReceiverActivity.kt").contains("leftMargin = Theme.dp(this@ReceiverActivity, Theme.SPACE_SM_DP)"))
        assertTrue(ui("SenderActivity.kt").contains("leftMargin = Theme.dp(this@SenderActivity, Theme.SPACE_SM_DP)"))
    }

    @Test
    fun `标题与正文字号按角色统一`() {
        for (page in pages) {
            assertFalse("$page 又写死了标题字号", ui(page).contains("Theme.SENDER_TITLE_SP"))
        }
        assertEquals("手机标题必须走同一令牌", Theme.PHONE_TITLE_SP, FocusPolicy.titleSp(false), 0.0001f)
        assertEquals("发送端标题已并入手机标题", Theme.PHONE_TITLE_SP, Theme.SENDER_TITLE_SP, 0.0001f)
        assertTrue("按钮字号令牌缺失", Theme.PHONE_BUTTON_SP >= 16f)
    }

    @Test
    fun `诊断卡三动作全宽且不换行`() {
        val sender = ui("SenderActivity.kt")
        val card = ui("ErrorStateCard.kt")
        assertTrue("发送端诊断区未委托 ErrorStateCard", sender.contains("ErrorStateCard"))
        assertTrue(
            "诊断卡动作区未按窄屏横排/宽屏纵向切换",
            card.contains("orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL"),
        )
        assertTrue(
            "诊断卡按钮未全宽或未限制单行",
            card.contains("LayoutParams(LayoutParams.MATCH_PARENT, buttonHeight)") &&
                card.contains("maxLines = 1") &&
                card.contains("TextUtils.TruncateAt.END"),
        )
    }

    @Test
    fun `设置面板使用全屏遮罩`() {
        val src = ui("ReceiverActivity.kt")
        assertTrue("设置面板未包全屏 overlay", src.contains("buildSettingsOverlay()"))
        assertTrue("设置面板未切换遮罩可见性", src.contains("settingsOverlay.visibility = View.VISIBLE"))
    }

    @Test
    fun `弹窗统一走 Material 品牌主题`() {
        for (page in pages) {
            assertFalse("$page 仍有平台 AlertDialog", ui(page).contains("AlertDialog.Builder("))
        }
        assertTrue("UiKit 未提供统一对话框", ui("UiKit.kt").contains("MaterialAlertDialogBuilder"))
    }
}

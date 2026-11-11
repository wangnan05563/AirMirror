package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI-01 源码守卫：Theme.kt 之外不得再出现硬编码色值、textSize 字面量、刻度留边字面量与动效时长字面量。
 * 白名单：Qr.kt 的黑白二维码（用 Color 常量，本就不含十六进制字面量）；
 * 访客页内联样式在 web/BrowserCastServer，按 UX-04 单独收敛，不在本守卫扫描范围。
 */
class ThemeTokenGuardTest {

    /**
     * 令牌单一来源的合法例外（T04909 裁决放行）：
     * - `Theme.kt` 是令牌源本身；
     * - `DeviceCardTag.kt` 持有平台 `GradientDrawable` 的形状参数（8/3/6dp、三种标签衬底），
     *   不是可替换的视觉令牌；把 12% 强调色衬底写进 Theme 反而会让「色板冻结」多出三个一次性常量；
     * - `StatusColors.kt` 持有 WCAG 合成层的底层常量（90%/80% 页底、纯白最差背景），
     *   它们是 `contrast()` 的输入而非界面用色。
     * 两者仍受下面「守卫仍覆盖业务 UI 文件且正则有效」的回归断言约束，不允许无限扩散。
     */
    private val exempt = setOf("Theme.kt", "DeviceCardTag.kt", "StatusColors.kt")

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "AirMirror-PRD-v1.1.md").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun uiSources(): List<File> =
        File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui")
            .walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in exempt }.toList()

    @Test
    fun `令牌守卫仍覆盖业务 UI 文件且正则有效`() {
        val names = uiSources().map { it.name }.toSet()
        for (f in listOf("SenderActivity.kt", "ReceiverActivity.kt", "MainActivity.kt", "FocusPolicy.kt")) {
            assertTrue("守卫漏扫 $f", f in names)
        }
        assertTrue("例外文件应确实被放行", "Theme.kt" !in names && "DeviceCardTag.kt" !in names && "StatusColors.kt" !in names)
        assertTrue("色值正则失效", Regex("0x[0-9A-Fa-f]{8}").containsMatchIn("val x = 0x1F17C3B2"))
        assertTrue("刻度正则失效", Regex("Theme\\.dp\\([^,()]+, (4|8|12|16|24|32)\\)").containsMatchIn("Theme.dp(ctx, 8)"))
        assertTrue("时长正则失效", Regex("\\b(120L|150L|200L)\\b").containsMatchIn("delay(150L)"))
    }

    @Test
    fun `Theme 之外没有硬编码十六进制色值`() {
        val re = Regex("0x[0-9A-Fa-f]{8}")
        val hits = uiSources().flatMap { f ->
            f.readText().lines().mapIndexedNotNull { i, line ->
                if (re.containsMatchIn(line)) "${f.name}:${i + 1}" else null
            }
        }
        assertTrue("发现硬编码色值：$hits", hits.isEmpty())
    }

    @Test
    fun `Theme 之外没有 textSize 数字字面量`() {
        val re = Regex("textSize\\s*=\\s*[0-9]")
        val hits = uiSources().flatMap { f ->
            f.readText().lines().mapIndexedNotNull { i, line ->
                if (re.containsMatchIn(line)) "${f.name}:${i + 1}" else null
            }
        }
        assertTrue("发现 textSize 字面量：$hits", hits.isEmpty())
    }

    @Test
    fun `通用留边刻度不再写 dp 字面量`() {
        val re = Regex("Theme\\.dp\\([^,()]+, (4|8|12|16|24|32)\\)")
        val hits = uiSources().flatMap { f ->
            f.readText().lines().mapIndexedNotNull { i, line ->
                if (re.containsMatchIn(line)) "${f.name}:${i + 1}" else null
            }
        }
        assertTrue("发现刻度留边字面量：$hits", hits.isEmpty())
    }

    @Test
    fun `动效时长字面量只在 Theme`() {
        val re = Regex("\\b(120L|150L|200L)\\b")
        val hits = uiSources().flatMap { f ->
            f.readText().lines().mapIndexedNotNull { i, line ->
                if (re.containsMatchIn(line)) "${f.name}:${i + 1}" else null
            }
        }
        assertTrue("Theme 之外出现时长字面量：$hits", hits.isEmpty())
    }

    @Test
    fun `FocusPolicy 的时长与手机标题都委托 Theme`() {
        val src = File(
            projectRoot(),
            "app/src/main/java/io/github/wangnan05563/airmirror/ui/FocusPolicy.kt",
        ).readText()
        assertTrue(src.contains("Theme.DURATION_FOCUS_MS"))
        assertTrue(src.contains("Theme.DURATION_CONNECT_FADE_MS"))
        assertTrue(src.contains("Theme.PHONE_TITLE_SP"))
    }

    @Test
    fun `Theme 令牌族齐全`() {
        val src = File(
            projectRoot(),
            "app/src/main/java/io/github/wangnan05563/airmirror/ui/Theme.kt",
        ).readText()
        for (token in listOf(
            "BACKGROUND_ALPHA_40", "BACKGROUND_ALPHA_80", "BACKGROUND_ALPHA_90",
            "SURFACE_ALPHA_90", "DIVIDER", "QR_BACKGROUND",
            "PHONE_TITLE_SP", "SENDER_TITLE_SP", "DEVICE_NAME_SP",
            "RADIUS_FOCUS_DP", "SPACE_XS_DP", "SPACE_SM_DP", "SPACE_MD_DP",
            "SPACE_LG_DP", "SPACE_XL_DP", "SPACE_XXL_DP",
            "DURATION_FOCUS_MS", "DURATION_CONNECT_FADE_MS", "DURATION_CODE_FADE_MS",
        )) {
            assertTrue("缺少令牌 $token", src.contains("const val $token"))
        }
        assertEquals("色值令牌应只在 Theme 定义", 1, src.split("const val BACKGROUND_ALPHA_80").size - 1)
    }
}

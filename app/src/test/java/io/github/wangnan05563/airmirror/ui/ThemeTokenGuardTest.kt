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
     * 令牌单一来源的合法例外（REQ-008）：
     * - `ThemeTokens.kt` 是三层令牌与 Raw 色板的唯一定义点；
     * - 其余 UI 文件（含 Theme.kt、StatusColors.kt、DeviceCardTag.kt）不得再出现十六进制色值，
     *   只能引用 Semantic/Component 或旧 Theme 兼容别名。
     * debug 预览页只读取 Raw 用于展示，不在 release 源码内。
     */
    private val exempt = setOf("ThemeTokens.kt")

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
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
        for (f in listOf("SenderActivity.kt", "ReceiverActivity.kt", "MainActivity.kt", "FocusPolicy.kt", "UiKit.kt")) {
            assertTrue("守卫漏扫 $f", f in names)
        }
        assertTrue("例外文件应确实被放行", "ThemeTokens.kt" !in names)
        for (f in listOf("Theme.kt", "StatusColors.kt", "DeviceCardTag.kt")) {
            assertTrue("$f 不应再被豁免", f in names)
        }
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
    fun `Theme 旧令牌族齐全且全部为别名`() {
        val src = File(
            projectRoot(),
            "app/src/main/java/io/github/wangnan05563/airmirror/ui/Theme.kt",
        ).readText()
        for (token in listOf(
            "BACKGROUND", "SURFACE", "ACCENT", "TEXT_PRIMARY", "TEXT_SECONDARY", "OFFLINE", "DANGER",
            "BACKGROUND_ALPHA_40", "BACKGROUND_ALPHA_80", "BACKGROUND_ALPHA_90",
            "SURFACE_ALPHA_90", "DIVIDER", "QR_BACKGROUND",
            "SURFACE_ELEVATED", "SURFACE_STROKE", "SECTION_TITLE_SP",
            "PHONE_TITLE_SP", "SENDER_TITLE_SP", "DEVICE_NAME_SP",
            "RADIUS_FOCUS_DP", "RADIUS_CARD_DP", "RADIUS_INSET_DP", "RADIUS_DIALOG_DP",
            "SPACE_XS_DP", "SPACE_SM_DP", "SPACE_MD_DP",
            "SPACE_LG_DP", "SPACE_XL_DP", "SPACE_XXL_DP",
            "DURATION_FOCUS_MS", "DURATION_CONNECT_FADE_MS", "DURATION_CODE_FADE_MS",
        )) {
            assertTrue("缺少令牌 $token", Regex("val $token\\s*:").containsMatchIn(src))
        }
        assertTrue("Theme 必须暴露组件层入口", src.contains("val component: ComponentTokens"))
        assertTrue("Theme 不得再持有十六进制色值", !Regex("0x[0-9A-Fa-f]{8}").containsMatchIn(src))
    }

    @Test
    fun `Raw 只允许 ThemeTokens 与 debug 预览读取`() {
        val root = projectRoot()
        val uiDir = File(root, "app/src/main/java/io/github/wangnan05563/airmirror/ui")
        val hits = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "ThemeTokens.kt" }
            .flatMap { f ->
                f.readText().lines().mapIndexedNotNull { i, line ->
                    if (line.contains("RawPalette") || line.contains("RawColors")) "${f.name}:${i + 1}" else null
                }
            }.toList()
        assertTrue("Raw 层被越权引用：$hits", hits.isEmpty())

        val preview = File(root, "app/src/debug/java/io/github/wangnan05563/airmirror/ui/ThemePreviewActivity.kt")
        assertTrue("debug 预览页应存在", preview.isFile)
        assertTrue("debug 预览页应读取 Raw 色板", preview.readText().contains("ThemeTokens.raw"))
    }

    @Test
    fun `平台 colors_xml 只是 Raw 的镜像且不得漂移`() {
        val xml = File(projectRoot(), "app/src/main/res/values/colors.xml").readText()
        val expected = mapOf(
            "airmirror_background" to "FF0B1F3A",
            "airmirror_surface" to "FF12314F",
            "airmirror_surface_elevated" to "FF1A3D61",
            "airmirror_accent" to "FF17C3B2",
            "airmirror_text_primary" to "FFFFFFFF",
            "airmirror_text_secondary" to "B3FFFFFF",
            "airmirror_offline" to "4DFFFFFF",
            "airmirror_danger" to "FFFF6B6B",
        )
        for ((name, argb) in expected) {
            assertTrue("colors.xml 缺少 Raw 镜像 $name", Regex("name=\"$name\"[^>]*>#?$argb<").containsMatchIn(xml))
        }
    }
}

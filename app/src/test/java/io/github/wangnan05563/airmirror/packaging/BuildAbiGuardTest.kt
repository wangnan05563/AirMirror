package io.github.wangnan05563.airmirror.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 构建期守卫（PRD 1.1）：打包口径必须在 [app/build.gradle.kts] 里真实落地，防止
 * 「代码/文档里写的策略」和「实际打出来的包」漂移。任何对 splits/命名逻辑的改动
 * 若与本类断言不一致，这里会让 testDebugUnitTest 变红。
 *
 * 2026-11-15 T05734 收敛：工程无 native 库，per-ABI 包只会产出重复包，
 * 打包口径由「arm64-v8a + armeabi-v7a 双独立包 + universal」收敛为「只出 universal 一个包」。
 */
class BuildAbiGuardTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun gradleText(): String = File(projectRoot(), "app/build.gradle.kts").readText()

    /** 只留代码行：注释里提到旧配置属正常文档记录，不应触发守卫（2026-11-15 实测被 `//` 注释误伤）。 */
    private fun gradleCodeText(): String =
        gradleText().lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    @Test
    fun `minSdk 为 22（Q-5 重签后的硬下限）`() {
        val m = Regex("""minSdk\s*=\s*(\d+)""").find(gradleText())
        assertTrue("build.gradle.kts 未声明 minSdk", m != null)
        // Q-5 原裁 19（Android 4.4）；2026-11-12 T04908 裁决 1 重签为 22，
        // 换取 View.setAccessibilityTraversalBefore/After（API 22）修正无障碍遍历顺序。
        assertEquals("minSdk 必须等于 22（Q-5 已重签，不得静默回退）", 22, m!!.groupValues[1].toInt())
    }

    @Test
    fun `单包分发：per-ABI splits 关闭`() {
        val text = gradleCodeText()
        // 无 native 库时拆 per-ABI 包只会产出内容相同的重复包（2026-11-15 实测三包 SHA-256 一致），
        // 打包口径收敛为单包：splits 必须保持关闭。
        assertTrue("splits.abi 配置块缺失（口径无处可查）", text.contains("splits {"))
        assertFalse(
            "splits 又被打开（无 native 库时只会产出重复包；实测空 ABI 列表还会产出缺 dex 的残缺包）",
            Regex("""isEnable\s*=\s*true""").containsMatchIn(text),
        )
        assertFalse(
            "splits 又拆出了 per-ABI 独立包",
            Regex("""include\(\s*"arm""").containsMatchIn(text),
        )
    }

    @Test
    fun `产物名带 AirMirror 前缀、版本号与 universal 标记`() {
        val text = gradleText()
        assertTrue("版本号未取 versionName", text.contains("android.defaultConfig.versionName"))
        assertTrue(
            "产物命名未同时带版本号与 universal 标记",
            text.contains("\"AirMirror-\$apkVersionLabel-\$abiTag\$tail.apk\""),
        )
        assertTrue("单包模式下未补 universal 标记", text.contains("\"universal-\""))
    }
}

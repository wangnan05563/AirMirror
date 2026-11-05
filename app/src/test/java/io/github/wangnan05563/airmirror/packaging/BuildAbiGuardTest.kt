package io.github.wangnan05563.airmirror.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 构建期守卫（PRD 1.1）：[AbiPolicy] 的口径必须在 [app/build.gradle.kts] 里真实落地，
 * 防止「代码里写的 ABI 集合」和「实际打出来的包」漂移。任何对 splits/ndk ABI 的改动
 * 若与 AbiPolicy.SHIPPED_ABIS 不一致，这里会让 testDebugUnitTest 变红。
 */
class BuildAbiGuardTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "AirMirror-PRD-v1.1.md").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun gradleText(): String = File(projectRoot(), "app/build.gradle.kts").readText()

    @Test
    fun `minSdk 仍是 19（Q-5 硬下限不被抬高）`() {
        val m = Regex("""minSdk\s*=\s*(\d+)""").find(gradleText())
        assertTrue("build.gradle.kts 未声明 minSdk", m != null)
        assertEquals("minSdk 必须等于 19（Q-5 已裁，不得静默抬高）", 19, m!!.groupValues[1].toInt())
    }

    @Test
    fun `splits 启用双 ABI 且 universal 保留`() {
        val text = gradleText()
        assertTrue("splits.abi 未启用", text.contains("abi") && text.contains("isEnable = true"))
        assertTrue("未产出 armeabi-v7a 独立包", text.contains("\"armeabi-v7a\""))
        assertTrue("未产出 arm64-v8a 独立包", text.contains("\"arm64-v8a\""))
        // universal 保留 → 开发机与模拟器(emulator-5554, x86_64)的安装验证通道不被砍断
        assertTrue("universal 包被关闭（模拟器/开发通道需要它）", text.contains("isUniversalApk = true"))
    }

    @Test
    fun `发布的 ABI 集合与 AbiPolicy 一致`() {
        val text = gradleText()
        for (abi in AbiPolicy.SHIPPED_ABIS) {
            assertTrue("build.gradle.kts 缺少发布 ABI：$abi", text.contains("\"$abi\""))
        }
    }
}

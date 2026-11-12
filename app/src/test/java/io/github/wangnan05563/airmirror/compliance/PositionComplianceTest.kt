package io.github.wangnan05563.airmirror.compliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级守卫（PRD §7 / §10 扩展纪律 / §11.3 不做的交互）：
 * 定位底线是一次性承诺，不能只靠自觉——本测试用 PositionGuard 的口径
 * 全量扫描本仓库的依赖坐标、Manifest 权限、main 源码与 res 文案，
 * 任何广告 / 计费 / 强制登录 / 运营化标记的引入都会在这里构建失败。
 */
class PositionComplianceTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根（找不到 settings.gradle.kts），cwd=" + System.getProperty("user.dir"))
    }

    private fun gradleFiles(): List<File> {
        val root = projectRoot()
        return listOf("build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts", "gradle/libs.versions.toml")
            .map { File(root, it) }
            .filter { it.isFile }
    }

    private fun mainSources(): List<File> {
        val base = File(projectRoot(), "app/src/main/java")
        assertTrue("源码目录不存在：$base", base.isDirectory)
        return base.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun resFiles(): List<File> {
        val base = File(projectRoot(), "app/src/main/res")
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown().filter { it.isFile && (it.extension == "xml" || it.extension == "html") }.toList()
    }

    private fun assertClean(label: String, files: List<File>) {
        val hits = files.flatMap { f -> PositionGuard.check(f.path, f.readText()).map { f.name to it } }
        assertTrue(
            "扩展纪律守卫命中（$label）：\n" + hits.joinToString("\n") { "${it.first} → ${it.second.reason}" } +
                "\n处置：" + PositionGuard.verdict(hits.map { it.second }),
            hits.isEmpty(),
        )
    }

    @Test
    fun `依赖坐标不引入广告或计费 SDK`() {
        val files = gradleFiles()
        assertTrue("没扫到任何构建脚本，守卫失效", files.isNotEmpty())
        assertClean("构建脚本", files)
        println("[合规] 构建脚本 ${files.size} 个：无广告/计费 SDK")
    }

    @Test
    fun `Manifest 不声明计费或广告权限`() {
        val manifest = File(projectRoot(), "app/src/main/AndroidManifest.xml")
        assertClean("AndroidManifest", listOf(manifest))
        val text = manifest.readText()
        for (permission in PositionGuard.BANNED_PERMISSIONS) {
            assertEquals("出现违禁权限：$permission", 0, text.split(permission).size - 1)
        }
        println("[合规] Manifest 权限口径 ${PositionGuard.BANNED_PERMISSIONS.size} 项全部 0 命中")
    }

    @Test
    fun `main 源码无广告计费强制登录标记`() {
        val files = mainSources()
        assertTrue("没扫到任何源码，守卫失效", files.size > 20)
        assertClean("main 源码", files)
        // 自我引用豁免必须是真实机制而不是巧合：确认 compliance 包确实在扫描范围内又被跳过
        assertTrue(files.any { it.path.replace('\\', '/').contains("/compliance/") })
        println("[合规] main 源码 ${files.size} 个文件（含 compliance 豁免）违禁标记 0 命中")
    }

    @Test
    fun `res 文案无运营化交互入口`() {
        val files = resFiles()
        assertClean("res 文案", files)
        println("[合规] res 文案 ${files.size} 个文件：无签到/积分/任务中心/会员墙入口")
    }

    @Test
    fun `无外部推广通道——代码不请求非局域网营销域名`() {
        val all = mainSources().joinToString("\n") { it.readText() }
        for (marker in listOf("umeng", "appsflyer", "adjust.com", "fabric.io", "crashlytics")) {
            assertEquals("出现营销/统计通道：$marker", 0, all.lowercase().split(marker).size - 1)
        }
        println("[合规] 营销/统计通道 0 处")
    }
}

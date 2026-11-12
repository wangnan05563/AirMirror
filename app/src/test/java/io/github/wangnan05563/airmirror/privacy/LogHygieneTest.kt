package io.github.wangnan05563.airmirror.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级守卫：PRD 14.4「不保存画面、文件名、URL」是一次性承诺，不能只靠某个日志点自觉。
 * 本测试扫描全部 main 源码，把「曾经真实泄漏过的写法」和「新增落盘通道」钉成硬失败，
 * 同时作为回传结论里「全量扫描 N 个文件 / M 行日志」的实测证据。
 */
class LogHygieneTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根（找不到 settings.gradle.kts），cwd=" + System.getProperty("user.dir"))
    }

    private fun sources(): List<File> {
        val base = File(projectRoot(), "app/src/main/java")
        assertTrue("源码目录不存在：$base", base.isDirectory)
        return base.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `日志里不再出现片名 地址 对端IP 或异常原文`() {
        val files = sources()
        val logLines = mutableListOf<Pair<String, String>>()
        for (file in files) {
            file.readLines().forEachIndexed { index, line ->
                if (line.contains("Log.")) logLines += file.name to "L${index + 1}: $line"
            }
        }
        assertTrue("没有扫到任何日志行，守卫失效", logLines.isNotEmpty())
        val dangerous = listOf("title=", "\$location", "hostAddress", "\${e.message}", "\${it.message}", "media?.title")
        val hits = logLines.filter { (_, line) -> dangerous.any { line.contains(it) } }
        assertTrue(
            "以下日志行仍可能带出用户内容：\n" + hits.joinToString("\n") { "${it.first} ${it.second}" },
            hits.isEmpty(),
        )
        println("[日志卫生] 扫描 ${files.size} 个源码文件、${logLines.size} 行 Log.*，敏感写法 0 处")
    }

    @Test
    fun `脱敏接线确实存在，不是只加了个没用的类`() {
        val all = sources().joinToString("\n") { it.readText() }
        val throwableUses = all.split("Redactor.throwable(").size - 1
        assertTrue("异常脱敏要覆盖全站（守卫实测 25 处），实际 $throwableUses", throwableUses >= 25)
        assertTrue("服务停止时要清掉会话内存", all.contains("machine.clear()"))
        val installs = all.split(".install()").size - 1
        assertTrue("诊断接管要覆盖主入口与开机自启路径，实际 $installs", installs >= 2)
        println("[接线] Redactor.throwable=$throwableUses 处，machine.clear=1 处，install=$installs 处")
    }

    @Test
    fun `没有新增外部存储或公共目录的落盘通道`() {
        val all = sources().joinToString("\n") { it.readText() }
        for (forbidden in listOf("getExternalStorage", "MediaStore.", "openFileOutput", "FileOutputStream", "Environment.getExternal")) {
            assertEquals("出现外部落盘通道：$forbidden", 0, all.split(forbidden).size - 1)
        }
        // 全工程唯一的 writeText 只允许是诊断文件（应用私有 filesDir）
        assertEquals("写文件只允许诊断这一处", 1, all.split("writeText(").size - 1)
        val manifest = File(projectRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue("卸载即清空要求 allowBackup=false", manifest.contains("android:allowBackup=\"false\""))
        println("[落盘] 外部存储/公共目录通道 0 处，writeText 1 处，allowBackup=false")
    }
}

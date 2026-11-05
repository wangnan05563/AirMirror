package io.github.wangnan05563.airmirror.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级守卫：发现降级片最关键的约束是「探测绝不碰 41523 媒体端口」和「双端真的接了线」。
 * 前者一旦破戒，8.9 的限流闸门会把机主自己关进 60 秒静默；后者防止只留一堆没被调用的类。
 */
class DiscoveryWiringTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "AirMirror-PRD-v1.1.md").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun mainSources(): List<File> =
        File(projectRoot(), "app/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun source(name: String): String =
        mainSources().firstOrNull { it.name == name }?.readText()
            ?: throw AssertionError("找不到源码文件 $name")

    /** 只看代码行，注释里解释「不碰 41523」的文案不算命中。 */
    private fun codeLines(text: String): List<String> = text.lines().filter {
        val trimmed = it.trimStart()
        !trimmed.startsWith("*") && !trimmed.startsWith("//") && !trimmed.startsWith("/*")
    }

    @Test
    fun `探测端口与媒体端口严格分开`() {
        assertFalse(ProbeProtocol.PROBE_PORT == 41523)
        assertFalse(ProbeProtocol.ANNOUNCE_PORT == 41523)
        val probeFiles = mainSources().filter { it.name.startsWith("Probe") && it.extension == "kt" }
        assertTrue("没扫到探测层源码，守卫失效", probeFiles.size >= 3)
        for (file in probeFiles) {
            val code = codeLines(file.readText())
            assertTrue(
                "${file.name} 的代码里出现了媒体端口 41523：\n" + code.filter { it.contains("41523") }.joinToString("\n"),
                code.none { it.contains("41523") },
            )
            assertTrue(
                "${file.name} 里出现了 TCP Socket：探测只能用 UDP",
                code.none { it.contains("= Socket(") || it.contains("java.net.Socket(") },
            )
        }
    }

    @Test
    fun `接收端与发送端都真的接了线`() {
        val host = source("ReceiverHost.kt")
        assertTrue(host.contains("ProbeResponder("))
        assertTrue(host.contains("ensureProbe(deviceName)"))
        assertTrue(host.contains("fun shutdown()"))
        assertTrue(source("ReceiverService.kt").contains("ReceiverHost.get(this).shutdown()"))
        val sender = source("SenderActivity.kt")
        assertTrue(sender.contains("startScanning()"))
        assertTrue(sender.contains("noteMulticastHit()"))
        assertTrue(sender.contains("RecentTargets.decode(store.recentTargets())"))
        assertTrue("空列表要用降级判决的可执行原因", sender.contains("discoveryNotice.ifEmpty { SEARCHING }"))
        assertTrue("连接过要落盘直连缓存", sender.contains("saveRecentTargets(it.snapshot())"))
        assertTrue("离开页面要停扫描器", sender.contains("it.stop()"))
        assertTrue(source("DeviceStore.kt").contains("fun recentTargets()"))
    }

    @Test
    fun `探测日志不含异常原文与对端地址`() {
        val probe = codeLines(source("ProbeResponder.kt")).joinToString("\n")
        assertFalse(probe.contains("\${it.message}"))
        assertFalse(probe.contains("\${e.message}"))
        assertTrue("异常日志必须过脱敏", probe.contains("Redactor.throwable"))
        assertEquals("日志不得带对端地址", 0, probe.lines().count { it.contains("Log.") && it.contains("announce to") })
    }
}

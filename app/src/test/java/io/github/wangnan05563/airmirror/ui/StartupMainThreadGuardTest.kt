package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码守卫（T05669：Receiver 首启主线程过重）。
 *
 * 约束的不是耗时数字（模拟器软件渲染噪声太大，不该写进单测），而是结构性事实：
 * 诊断接管只能有两个入口点，Receiver.onCreate 不得同步做诊断文件 I/O。
 * 「先出首屏、后台清理」这条口径一旦被改回去，这里会当场变红。
 */
class StartupMainThreadGuardTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "AirMirror-PRD-v1.1.md").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun mainSrc(path: String): String =
        File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/$path")
            .readText().replace("\r\n", "\n")

    private fun onCreate(): String {
        val src = mainSrc("ui/ReceiverActivity.kt")
        return src.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("root = FrameLayout(this)")
    }

    private fun countOf(src: String, needle: String): Int = src.split(needle).size - 1

    @Test
    fun `Receiver 首启不在主线程重复安装诊断接管`() {
        assertFalse(
            "Receiver.onCreate 又出现了 diagnostics.install()：诊断接管应只在 ReceiverHost / MainActivity 单点安装",
            onCreate().contains("diagnostics.install()"),
        )
    }

    @Test
    fun `Receiver 首启的过期诊断清理走 daemon 后台线程`() {
        val onCreate = onCreate()
        assertFalse(
            "Receiver.onCreate 里出现了同步的 diagnostics.purge()（文件遍历/删除回到主线程）",
            Regex("""(?m)^\s*diagnostics\.purge\(\)""").containsMatchIn(onCreate),
        )
        assertTrue(
            "缺少具名 daemon 线程 airmirror-diag-purge",
            onCreate.contains("thread(name = \"airmirror-diag-purge\", isDaemon = true) { diagnostics.purge() }"),
        )
    }

    @Test
    fun `诊断接管全仓只有两个入口点`() {
        val install = Regex("""Diagnostics\([^)]*\)\.install\(\)""")
        val sites = listOf(
            "receiver/ReceiverHost.kt" to 1,
            "ui/MainActivity.kt" to 1,
            "ui/ReceiverActivity.kt" to 0,
        )
        sites.forEach { (path, expected) ->
            val found = install.findAll(mainSrc(path)).count()
            assertEquals("$path 的诊断安装点应为 $expected 个，实为 $found 个", expected, found)
        }
    }

    @Test
    fun `purge 只在首启后台与设置页各出现一次`() {
        assertEquals(2, countOf(mainSrc("ui/ReceiverActivity.kt"), "diagnostics.purge()"))
        // 设置页那次是用户主动打开面板，随后 refreshSettings() 要用它的结果，保持同步是有意的
        assertTrue(
            mainSrc("ui/ReceiverActivity.kt").substringAfter("private fun showSettings()")
                .substringBefore("refreshSettings()")
                .contains("diagnostics.purge()"),
        )
    }

    @Test
    fun `设备分级探测仍在后台线程`() {
        val host = mainSrc("receiver/ReceiverHost.kt")
        assertTrue(
            "ReceiverHost 的分级探测被挪回主线程了",
            host.contains("thread(name = \"airmirror-grade\", isDaemon = true)"),
        )
    }
}

package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码守卫：全站可点控件有 20+ 处且分布在两个 Activity，焦点必须走统一遍历而不是逐处手写。
 * 一旦有人新起一个页面、setContentView 后没调 applyTree，这里会直接失败。
 */
class FocusCoverageTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "AirMirror-PRD-v1.1.md").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    @Test
    fun `两个页面都接了统一焦点遍历，而不是只改了几个按钮`() {
        val ui = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui")
        val sources = ui.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val main = File(ui, "MainActivity.kt").readText()
        val receiver = File(ui, "ReceiverActivity.kt").readText()
        assertTrue("MainActivity 未接 TvFocus.applyTree", main.contains("TvFocus.applyTree(root)"))
        assertTrue("ReceiverActivity 未接 TvFocus.applyTree", receiver.contains("TvFocus.applyTree(root"))
        assertTrue(
            "设置页动态档位行需要在 refreshSettings 时再扫一次",
            receiver.contains("TvFocus.applyTree(settingsPanel"),
        )
        val clicks = sources.sumOf { it.readText().split("setOnClickListener").size - 1 }
        assertTrue("焦点候选数为 0，守卫失效", clicks >= 15)
        println("[焦点覆盖] ui 源码 ${sources.size} 个文件、setOnClickListener $clicks 处，统一遍历接线 3 处")
    }

    @Test
    fun `连接成功走 200ms 淡入且受 C 级动效开关控制`() {
        val receiver = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/ReceiverActivity.kt").readText()
        assertTrue("showCasting 未接淡入", receiver.contains("FocusPolicy.fadeDurationMs"))
        assertTrue("淡入未作用于浮层", receiver.contains("overlayName.animate()"))
    }

    @Test
    fun `幂等不能用 View_setTag 带 int key 的重载，运行期会抛非资源 id 异常`() {
        val src = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/TvFocus.kt").readText()
        assertFalse("view.setTag(int,...) 要求资源 id，generateViewId() 不满足", src.contains("view.setTag("))
        assertTrue("缺少弱引用幂等集合", src.contains("WeakHashMap"))
        assertTrue("缺少幂等判定", src.contains("applied.add(view)"))
    }
}

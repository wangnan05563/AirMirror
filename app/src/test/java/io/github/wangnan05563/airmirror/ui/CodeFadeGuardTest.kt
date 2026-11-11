package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码守卫：换码过渡必须走「双层码槽 + 单一时长令牌 + 值变化才渲染」，
 * 任何退回单 TextView 每秒赋值、或另起一套动画框架的改动都在这里失败。
 */
class CodeFadeGuardTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "AirMirror-PRD-v1.1.md").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun uiDir() = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui")

    private fun receiver() = File(uiDir(), "ReceiverActivity.kt").readText()

    @Test
    fun `单 TextView 直接写码的旧路径已清零`() {
        val src = receiver()
        assertFalse("codeText 仍在，说明还是每秒整段重写", src.contains("codeText"))
        assertTrue("缺少固定尺寸码槽", src.contains("codeSlot"))
        assertTrue("缺少两层码视图", src.contains("codeA") && src.contains("codeB"))
        assertTrue("两层必须共享同一套样式", src.contains("private fun codeStyle"))
    }

    @Test
    fun `只有值变化才渲染，每秒刷新不能触发动画`() {
        val src = receiver()
        assertTrue(src.contains("CodeFadePolicy.shouldCommit"))
        assertTrue(src.contains("CodeFadePolicy.shouldAnimate"))
        assertTrue("刷新入口必须走 renderCode", src.contains("renderCode(if (locked) LOCK_PLACEHOLDER else code.code())"))
    }

    @Test
    fun `两层动画使用同一个时长变量`() {
        val src = receiver()
        val start = src.indexOf("private fun renderCode")
        assertTrue("未找到 renderCode", start >= 0)
        val end = src.indexOf("private fun refreshWaitingPage", start)
        assertTrue("未找到 renderCode 结尾", end > start)
        val body = src.substring(start, end)
        assertEquals("两层必须各调一次 setDuration(fadeMs)", 2, body.split("setDuration(fadeMs)").size - 1)
        assertTrue("时长必须来自 FocusPolicy.codeFadeMs", body.contains("FocusPolicy.codeFadeMs"))
    }

    @Test
    fun `150ms 只允许出现在 Theme 令牌`() {
        val hits = uiDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readText().lines().map { it to file.name } }
            .filter { (line, _) -> line.contains("150L") }
            .toList()
        assertEquals("150L 应只作为 Theme.DURATION_CODE_FADE_MS 出现", 1, hits.size)
        assertEquals("Theme.kt", hits.first().second)
    }

    @Test
    fun `不引入属性动画框架或逐帧动画`() {
        val offenders = uiDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val src = file.readText()
                src.contains("ObjectAnimator") || src.contains("AnimationDrawable") || src.contains("android.animation")
            }
            .map { it.name }
            .toList()
        assertTrue("发现动画框架依赖：$offenders", offenders.isEmpty())
    }

    @Test
    fun `生命周期回调只维护过渡开关，不启停会话`() {
        val src = receiver()
        assertTrue(src.contains("override fun onResume()"))
        assertTrue(src.contains("override fun onPause()"))
        assertTrue(src.contains("resumed = false"))
    }

    @Test
    fun `轮换与锁定参数没有被过渡改动`() {
        val cast = File(
            projectRoot(),
            "app/src/main/java/io/github/wangnan05563/airmirror/pairing/CastCode.kt",
        ).readText()
        assertTrue(cast.contains("private const val DEFAULT_TTL = 60_000L"))
        assertTrue(cast.contains("private const val DEFAULT_GRACE = 10_000L"))
        assertTrue(cast.contains("private const val DEFAULT_LOCK = 60_000L"))
        assertTrue(cast.contains("private const val DEFAULT_MAX_ATTEMPTS = 5"))
    }
}

package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD UI-06：换码 150ms 交叉淡化、C 级 0ms、每秒刷新不触发动画。 */
class CodeFadePolicyTest {

    private fun input(
        animationsEnabled: Boolean = true,
        firstRender: Boolean = false,
        previous: String? = "123456",
        next: String = "654321",
        pageVisible: Boolean = true,
        resumed: Boolean = true,
    ) = CodeFadePolicy.Input(animationsEnabled, firstRender, previous, next, pageVisible, resumed)

    @Test
    fun `A_B 级换码走 150ms 过渡，时长只有 Theme 一个来源`() {
        assertEquals(150L, Theme.DURATION_CODE_FADE_MS)
        assertEquals(Theme.DURATION_CODE_FADE_MS, FocusPolicy.codeFadeMs(true))
        assertTrue(CodeFadePolicy.shouldAnimate(input()))
    }

    @Test
    fun `C 级关动效时 0ms 瞬切但仍要提交新码`() {
        assertEquals(0L, FocusPolicy.codeFadeMs(false))
        assertFalse(CodeFadePolicy.shouldAnimate(input(animationsEnabled = false)))
        assertTrue(CodeFadePolicy.shouldCommit(input(animationsEnabled = false)))
    }

    @Test
    fun `首次显示直接置终态，首帧不等动画`() {
        assertFalse(CodeFadePolicy.shouldAnimate(input(firstRender = true, previous = null)))
        assertTrue(CodeFadePolicy.shouldCommit(input(firstRender = true, previous = null)))
    }

    @Test
    fun `每秒刷新值没变时既不提交也不动画`() {
        assertFalse(CodeFadePolicy.shouldCommit(input(previous = "123456", next = "123456")))
        assertFalse(CodeFadePolicy.shouldAnimate(input(previous = "123456", next = "123456")))
    }

    @Test
    fun `码值变化触发交叉淡化`() {
        assertTrue(CodeFadePolicy.shouldCommit(input(previous = "123456", next = "654321")))
        assertTrue(CodeFadePolicy.shouldAnimate(input(previous = "123456", next = "654321")))
    }

    @Test
    fun `码到锁定占位符同样过渡`() {
        assertTrue(CodeFadePolicy.shouldAnimate(input(previous = "123456", next = "------")))
    }

    @Test
    fun `锁定占位符解锁回新码同样过渡`() {
        assertTrue(CodeFadePolicy.shouldAnimate(input(previous = "------", next = "246810")))
    }

    @Test
    fun `页面不可见时不排队动画，但值仍要提交`() {
        val hidden = input(pageVisible = false)
        assertFalse(CodeFadePolicy.shouldAnimate(hidden))
        assertTrue(CodeFadePolicy.shouldCommit(hidden))
    }

    @Test
    fun `Activity 暂停时不排队动画`() {
        assertFalse(CodeFadePolicy.shouldAnimate(input(resumed = false)))
    }

    @Test
    fun `过渡时长远小于轮换周期与旧码宽限`() {
        val fade = FocusPolicy.codeFadeMs(true)
        assertTrue(fade < 1_000L)
        assertTrue(fade < 60_000L)
        assertTrue(fade < 10_000L)
    }

    @Test
    fun `两层只有一个时长值，不存在第二套配置`() {
        assertEquals(FocusPolicy.codeFadeMs(true), FocusPolicy.codeFadeMs(true))
        assertEquals(0L, FocusPolicy.codeFadeMs(false))
    }

    @Test
    fun `首帧同值也只提交一次，不产生动画`() {
        val first = input(firstRender = true, previous = null, next = "------")
        assertTrue(CodeFadePolicy.shouldCommit(first))
        assertFalse(CodeFadePolicy.shouldAnimate(first))
    }
}

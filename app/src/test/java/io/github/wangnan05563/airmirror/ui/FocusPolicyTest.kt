package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD §11.5 视觉与可用性规范的判据锁。 */
class FocusPolicyTest {

    @Test
    fun `焦点态是 2px 描边加轻微放大，关动效也要放大`() {
        assertEquals(2, FocusPolicy.OUTLINE_DP)
        assertEquals(1.04f, FocusPolicy.focusScale(true, true), 0.0001f)
        assertEquals(1.04f, FocusPolicy.focusScale(false, true), 0.0001f)
        assertEquals(1f, FocusPolicy.focusScale(true, false), 0.0001f)
    }

    @Test
    fun `C 级设备关动效但保留状态变化`() {
        assertEquals(120L, FocusPolicy.focusAnimMs(true))
        assertEquals(0L, FocusPolicy.focusAnimMs(false))
        assertEquals(200L, FocusPolicy.fadeDurationMs(true))
        assertEquals(0L, FocusPolicy.fadeDurationMs(false))
    }

    @Test
    fun `只有可点控件进焦点链，纯展示文本不抢焦点`() {
        assertTrue(FocusPolicy.isFocusTarget(clickable = true, hasClickListener = false))
        assertTrue(FocusPolicy.isFocusTarget(clickable = false, hasClickListener = true))
        assertFalse(FocusPolicy.isFocusTarget(clickable = false, hasClickListener = false))
    }

    @Test
    fun `开局总是尝试定位首焦点，触屏模式由 isFocusableInTouchMode_false 自然挡住`() {
        assertTrue(FocusPolicy.shouldRequestInitialFocus())
    }

    @Test
    fun `TV 字号满足 3 米观看下限`() {
        assertEquals(40f, FocusPolicy.titleSp(true), 0.0001f)
        assertEquals(24f, FocusPolicy.bodySp(true), 0.0001f)
        assertEquals(14f, FocusPolicy.bodySp(false), 0.0001f)
        assertTrue(FocusPolicy.bodySp(true) >= 24f)
        assertTrue(FocusPolicy.titleSp(true) >= 40f)
    }
}

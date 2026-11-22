package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T06100：TV 布局识别、5% 安全区、二维码高度收敛与宽屏并排阈值。 */
class TvLayoutSpecTest {

    @Test
    fun `大横屏不依赖 uiMode 也按 TV 排版`() {
        assertTrue(TvLayoutSpec.isTvLayout(uiModeTv = false, widthPx = 1920, heightPx = 1080))
        assertTrue(TvLayoutSpec.isTvLayout(uiModeTv = false, widthPx = 1280, heightPx = 720))
        assertTrue(TvLayoutSpec.isTvLayout(uiModeTv = false, widthPx = 1024, heightPx = 768))
        assertTrue(TvLayoutSpec.isTvLayout(uiModeTv = true, widthPx = 800, heightPx = 600))
        assertFalse(TvLayoutSpec.isTvLayout(uiModeTv = false, widthPx = 1080, heightPx = 2400))
        assertFalse(TvLayoutSpec.isTvLayout(uiModeTv = false, widthPx = 1000, heightPx = 700))
    }

    @Test
    fun `1080p 720p 与 4x3 的安全区都是屏宽高 5 percent`() {
        assertEquals(TvLayoutSpec.SafeInsets(96, 54), TvLayoutSpec.safeInsets(1920, 1080))
        assertEquals(TvLayoutSpec.SafeInsets(64, 36), TvLayoutSpec.safeInsets(1280, 720))
        assertEquals(TvLayoutSpec.SafeInsets(52, 39), TvLayoutSpec.safeInsets(1024, 768))
    }

    @Test
    fun `二维码边长按屏高 34 percent 收敛`() {
        assertEquals(368, TvLayoutSpec.qrSidePx(1920, 1080, 380))
        assertEquals(245, TvLayoutSpec.qrSidePx(1280, 720, 380))
        assertEquals(262, TvLayoutSpec.qrSidePx(1024, 768, 380))
        // 手机竖屏屏高足够，不缩基础尺寸
        assertEquals(468, TvLayoutSpec.qrSidePx(1080, 2400, 468))
    }

    @Test
    fun `6 位码字号跟着码槽同比缩放`() {
        assertEquals(34.86f, TvLayoutSpec.codeSp(368, 2f, 190f, 36f), 0.01f)
        assertEquals(23.21f, TvLayoutSpec.codeSp(245, 2f, 190f, 36f), 0.01f)
        assertEquals(24.82f, TvLayoutSpec.codeSp(262, 2f, 190f, 36f), 0.01f)
        // 手机小屏的基础码槽本来就是 156dp，不应被二次缩小
        assertEquals(36f, TvLayoutSpec.codeSp(468, 3f, 156f, 36f), 0.01f)
    }

    @Test
    fun `只有 TV 宽屏才把二维码和投屏码并排`() {
        assertTrue(TvLayoutSpec.usePairRow(isTvLayout = true, widthDp = 960))
        assertTrue(TvLayoutSpec.usePairRow(isTvLayout = true, widthDp = 512))
        assertFalse(TvLayoutSpec.usePairRow(isTvLayout = true, widthDp = 411))
        assertFalse(TvLayoutSpec.usePairRow(isTvLayout = false, widthDp = 960))
    }
}

package io.github.wangnan05563.airmirror.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayFitTest {

    @Test
    fun fullDisplayKeepsPortraitAspectInsideLandscapeScreen() {
        val viewport = DisplayFit.viewport(DisplayMode.FIT, 1920, 1080, 1080, 1920)
        assertEquals(608, viewport.width)
        assertEquals(1080, viewport.height)
    }

    @Test
    fun fillCropsPortraitToCoverLandscapeScreen() {
        val viewport = DisplayFit.viewport(DisplayMode.FILL, 1920, 1080, 1080, 1920)
        assertEquals(1920, viewport.width)
        assertEquals(3413, viewport.height)
    }

    @Test
    fun stretchMatchesScreenExactly() {
        val viewport = DisplayFit.viewport(DisplayMode.STRETCH, 1920, 1080, 1080, 1920)
        assertEquals(Viewport(1920, 1080), viewport)
    }

    @Test
    fun fullDisplayNeverShrinksBelowQuarterOfScreenWidth() {
        // 1:4 的极窄画面完整显示只剩 270px 宽，按 25% 下限放大并允许上下裁切
        val viewport = DisplayFit.viewport(DisplayMode.FIT, 1920, 1080, 100, 400)
        assertEquals(480, viewport.width)
        assertEquals(1920, viewport.height)
    }

    @Test
    fun sameAspectFilmNeedsNoHint() {
        assertFalse(DisplayFit.mismatched(1920, 1080, 1280, 720))
    }

    @Test
    fun portraitOnLandscapeScreenIsMismatched() {
        assertTrue(DisplayFit.mismatched(1920, 1080, 1080, 1920))
    }

    @Test
    fun ultraWideContentIsMismatched() {
        assertTrue(DisplayFit.mismatched(1920, 1080, 2560, 1080))
    }

    @Test
    fun degenerateSizesProduceEmptyViewport() {
        assertEquals(Viewport(0, 0), DisplayFit.viewport(DisplayMode.FIT, 0, 1080, 1080, 1920))
        assertEquals(Viewport(0, 0), DisplayFit.viewport(DisplayMode.FILL, 1920, 1080, 0, 0))
        assertFalse(DisplayFit.mismatched(0, 0, 0, 0))
    }

    @Test
    fun unknownSavedModeFallsBackToFullDisplay() {
        assertEquals(DisplayMode.FIT, DisplayMode.parse(null))
        assertEquals(DisplayMode.FIT, DisplayMode.parse("GARBAGE"))
        assertEquals(DisplayMode.STRETCH, DisplayMode.parse("STRETCH"))
    }

    @Test
    fun onlyStretchNeedsConfirmation() {
        assertTrue(DisplayMode.STRETCH.needsConfirm)
        assertFalse(DisplayMode.FILL.needsConfirm)
        assertFalse(DisplayMode.FIT.needsConfirm)
    }
}
package io.github.wangnan05563.airmirror.sender

import io.github.wangnan05563.airmirror.device.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FS-10 恢复画质：先低一档，10 秒到点只回档一次。 */
class RecoveryQualityTest {

    private var clock = 5_000L
    private val quality = RecoveryQuality(now = { clock }, restoreMillis = 10_000L)

    @Test
    fun `recovery starts one tier below the negotiated tier`() {
        quality.start(QualityTier.FHD60)
        assertTrue(quality.active)
        assertEquals(QualityTier.HD30, quality.tier())
        assertEquals(QualityTier.FHD60, quality.fullTier())
    }

    @Test
    fun `the tier returns after ten seconds and tick fires once`() {
        quality.start(QualityTier.FHD60)
        clock += 9_999L
        assertFalse(quality.tick())
        assertEquals(QualityTier.HD30, quality.tier())
        clock += 1L
        assertTrue(quality.tick())
        assertEquals(QualityTier.FHD60, quality.tier())
        assertFalse(quality.active)
        assertFalse("tick must not fire twice", quality.tick())
    }

    @Test
    fun `a low tier never goes below the floor`() {
        quality.start(QualityTier.SD480)
        assertEquals(QualityTier.SD480, quality.tier())
    }

    @Test
    fun `stopping ends the recovery window`() {
        quality.start(QualityTier.QHD)
        quality.stop()
        assertFalse(quality.active)
        assertEquals(QualityTier.QHD, quality.tier())
        assertFalse(quality.tick())
    }

    @Test
    fun `the text tells the user it will restore`() {
        quality.start(QualityTier.FHD60)
        assertTrue(quality.text().contains("10 秒内回到 1080p60"))
        clock += 10_000L
        quality.tick()
        assertEquals("已恢复投屏", quality.text())
    }
}
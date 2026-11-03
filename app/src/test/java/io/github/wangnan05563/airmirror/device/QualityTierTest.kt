package io.github.wangnan05563.airmirror.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTierTest {

    @Test
    fun `negotiation takes the lower of local and peer ceiling`() {
        assertEquals(
            QualityTier.FHD60,
            QualityTiers.negotiate(QualityTier.UHD60, QualityTier.FHD60, NetworkBand.GHZ_5),
        )
        assertEquals(
            QualityTier.HD30,
            QualityTiers.negotiate(QualityTier.HD30, QualityTier.UHD60, NetworkBand.GHZ_5),
        )
    }

    @Test
    fun `2_4ghz caps at 720p`() {
        assertEquals(
            QualityTier.HD30,
            QualityTiers.negotiate(QualityTier.UHD60, QualityTier.UHD60, NetworkBand.GHZ_2),
        )
    }

    @Test
    fun `unsupported peer turns the negotiation off`() {
        assertFalse(QualityTiers.negotiate(QualityTier.UHD60, QualityTier.OFF, NetworkBand.GHZ_5).isUsable)
    }

    @Test
    fun `downgrade walks down one tier and stops at the floor`() {
        assertEquals(QualityTier.QHD, QualityTiers.downgrade(QualityTier.UHD60))
        assertEquals(QualityTier.FHD60, QualityTiers.downgrade(QualityTier.QHD))
        assertEquals(QualityTier.HD30, QualityTiers.downgrade(QualityTier.FHD60))
        assertEquals(QualityTier.SD480, QualityTiers.downgrade(QualityTier.HD30))
        assertEquals(QualityTier.SD480, QualityTiers.downgrade(QualityTier.SD480))
    }

    @Test
    fun `upgrade climbs back but never above the ceiling`() {
        assertEquals(QualityTier.HD30, QualityTiers.upgrade(QualityTier.SD480, QualityTier.FHD60))
        assertEquals(QualityTier.FHD60, QualityTiers.upgrade(QualityTier.QHD, QualityTier.FHD60))
        assertEquals(QualityTier.FHD60, QualityTiers.upgrade(QualityTier.FHD60, QualityTier.FHD60))
    }

    @Test
    fun `decoder fit falls back to a tier it can actually decode`() {
        assertEquals(QualityTier.HD30, QualityTiers.fitDecoder(QualityTier.FHD60, 1280, 720, true))
        assertEquals(QualityTier.FHD60, QualityTiers.fitDecoder(QualityTier.FHD60, 1920, 1080, true))
        assertEquals(QualityTier.OFF, QualityTiers.fitDecoder(QualityTier.FHD60, 0, 0, true))
    }

    @Test
    fun `software only decoder falls back to the smallest tier`() {
        assertEquals(QualityTier.SD480, QualityTiers.fitDecoder(QualityTier.UHD60, 0, 0, false))
    }

    @Test
    fun `portrait screen is capped by its long edge and keeps orientation`() {
        val profile = QualityTiers.profile(QualityTier.FHD60, 1080, 2400)
        assertEquals(864, profile.width)
        assertEquals(1920, profile.height)
        assertTrue(profile.width % 2 == 0 && profile.height % 2 == 0)
        assertEquals(60, profile.fps)
        assertTrue(profile.bitRateKbps in QualityTier.FHD60.minBitRateKbps..QualityTier.FHD60.maxBitRateKbps)
    }

    @Test
    fun `source that already fits is not upscaled`() {
        val profile = QualityTiers.profile(QualityTier.FHD60, 1080, 1920)
        assertEquals(1080, profile.width)
        assertEquals(1920, profile.height)
    }

    @Test
    fun `unsupported tier produces no encoder input`() {
        val profile = QualityTiers.profile(QualityTier.OFF, 1080, 2400)
        assertEquals(0, profile.width)
        assertEquals(0, profile.height)
    }

    @Test
    fun `tier within maps a peer resolution onto our ladder`() {
        assertEquals(QualityTier.UHD60, QualityTiers.tierWithin(3840, 2160))
        assertEquals(QualityTier.FHD60, QualityTiers.tierWithin(1920, 1080))
        assertEquals(QualityTier.SD480, QualityTiers.tierWithin(1000, 600))
        assertEquals(QualityTier.OFF, QualityTiers.tierWithin(0, 0))
    }

    @Test
    fun `receiver ceiling is the lower of grade and decoder capability`() {
        val bGrade = DeviceFacts(28, 2_000, 1920, 1080, true, false, 4_000, true, true)
        val policy = DeviceGrading.policyFor(bGrade)
        assertEquals(QualityTier.FHD60, QualityTiers.receiverCeiling(policy, bGrade))

        val cappedDecoder = bGrade.copy(hardwareDecodeWidth = 1280, hardwareDecodeHeight = 720)
        assertEquals(QualityTier.HD30, QualityTiers.receiverCeiling(policy, cappedDecoder))
    }

    @Test
    fun `unsupported device has no ceiling`() {
        val facts = DeviceFacts(30, 4_000, 3840, 2160, true, true, 4_000, false, true)
        val policy = DeviceGrading.policyFor(facts)
        assertEquals(QualityTier.OFF, QualityTiers.receiverCeiling(policy, facts))
    }

    @Test
    fun `note names the peer when the receiver is what caps quality`() {
        val note = QualityTiers.note(QualityTier.FHD60, QualityTier.UHD60, QualityTier.FHD60, NetworkBand.GHZ_5)
        assertEquals(QualityTier.FHD60, note.tier)
        assertEquals("对方上限", note.reason)
        assertEquals("已按 1080p60 投屏 · 对方上限", note.text())
    }

    @Test
    fun `note names 2_4ghz when the band is what caps quality`() {
        val note = QualityTiers.note(QualityTier.HD30, QualityTier.UHD60, QualityTier.UHD60, NetworkBand.GHZ_2)
        assertEquals("2.4GHz Wi-Fi 上限", note.reason)
        assertTrue(note.text().contains("720p30"))
    }

    @Test
    fun `note names the local ceiling when only this phone is limited`() {
        val note = QualityTiers.note(QualityTier.HD30, QualityTier.HD30, QualityTier.UHD60, NetworkBand.GHZ_5)
        assertEquals("本机上限", note.reason)
    }

    @Test
    fun `note stays quiet when 4K is actually reachable`() {
        val note = QualityTiers.note(QualityTier.UHD60, QualityTier.UHD60, QualityTier.UHD60, NetworkBand.GHZ_5)
        assertEquals("", note.reason)
        assertEquals("已按 4K 投屏", note.text())
    }

    @Test
    fun `note says unavailable instead of inventing a tier`() {
        assertEquals("当前无法投屏", QualityTiers.note(QualityTier.OFF, QualityTier.UHD60, null, NetworkBand.GHZ_5).text())
    }
}

package io.github.wangnan05563.airmirror.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4K 四道门（PRD FS-05 判定条件 + 验收「四道门全部满足才允许进入 4K 档位；
 * 任何一道不满足，该档位在界面上不出现」）。逐道门单独不通，再看叠加取最低。
 */
class FourGatesTest {

    private val allOpen = GateInputs(
        screenReaches4K = true,
        hevc4KHardDecode = true,
        interfaceReaches4K = true,
        band = NetworkBand.GHZ_5,
    )

    private fun withScreen(v: Boolean) = allOpen.copy(screenReaches4K = v)
    private fun withDecoder(v: Boolean) = allOpen.copy(hevc4KHardDecode = v)
    private fun withInterface(v: Boolean) = allOpen.copy(interfaceReaches4K = v)
    private fun withBand(v: NetworkBand) = allOpen.copy(band = v)

    @Test
    fun `four open gates keep 4K on the table`() {
        val report = FourGates.evaluate(allOpen)
        assertTrue(report.unmetReason(), report.reaches4K)
        assertEquals(QualityTier.UHD60, report.maxTier)
    }

    @Test
    fun `the screen alone caps at 1080p`() {
        val report = FourGates.evaluate(withScreen(false))
        assertEquals(listOf(FourGate.SCREEN), report.unmet)
        assertEquals("不满足时那一列写的是最高档位止于 1080p", QualityTier.FHD60, report.maxTier)
    }

    @Test
    fun `no H_265_4K_hard_decode drops to 1440p`() {
        val report = FourGates.evaluate(withDecoder(false))
        assertEquals(listOf(FourGate.DECODER), report.unmet)
        assertEquals(QualityTier.QHD, report.maxTier)
    }

    @Test
    fun `a 2_4GHz link is not a 4K network and drops to the band ceiling`() {
        val report = FourGates.evaluate(withBand(NetworkBand.GHZ_2))
        assertEquals(listOf(FourGate.NETWORK), report.unmet)
        assertEquals("2.4GHz 只承诺 720p30", QualityTier.HD30, report.maxTier)
    }

    @Test
    fun `an unknown band is not evidence of 5GHz`() {
        // 「量不到」不能当成「够」：频段探不到时 4K 的适用条件不成立，但档位仍按 1080p 默认档走
        val report = FourGates.evaluate(withBand(NetworkBand.UNKNOWN))
        assertEquals(listOf(FourGate.NETWORK), report.unmet)
        assertEquals(QualityTier.FHD60, report.maxTier)
    }

    @Test
    fun `measured bandwidth below the 4K bitrate fails the network gate`() {
        val report = FourGates.evaluate(allOpen.copy(sustainedKbps = 15_000))
        assertEquals(listOf(FourGate.NETWORK), report.unmet)
        assertEquals("15Mbps 撑得起 1440p（12Mbps 下限）", QualityTier.QHD, report.maxTier)
    }

    @Test
    fun `measured bandwidth at the 4K floor keeps the network gate open`() {
        val report = FourGates.evaluate(allOpen.copy(sustainedKbps = 20_000))
        assertTrue(report.unmetReason(), report.reaches4K)
    }

    @Test
    fun `the 4K floor is compared in kbps not rounded Mbps`() {
        // 19.999Mbps 按 Mbps 取整会读成 19，被当成「差得远」；反过来 20_500 读成 20 又会放过
        // 一个本来够格的速率。门槛两侧都按真实 kbps 判，才不会出现「量到了却读不成」
        val justBelow = FourGates.evaluate(allOpen.copy(sustainedKbps = 19_999))
        assertEquals(listOf(FourGate.NETWORK), justBelow.unmet)
        assertEquals("19_999kbps 够 1440p，但差 4K 下限 1kbps", QualityTier.QHD, justBelow.maxTier)
    }

    @Test
    fun `an interface that cannot scan 4K disables the tier`() {
        val report = FourGates.evaluate(withInterface(false))
        assertEquals(listOf(FourGate.INTERFACE), report.unmet)
        assertEquals(QualityTier.QHD, report.maxTier)
    }

    @Test
    fun `several closed gates take the lowest tier`() {
        val report = FourGates.evaluate(
            GateInputs(
                screenReaches4K = false,
                hevc4KHardDecode = false,
                interfaceReaches4K = false,
                band = NetworkBand.GHZ_2,
            ),
        )
        assertEquals(
            listOf(FourGate.SCREEN, FourGate.DECODER, FourGate.NETWORK, FourGate.INTERFACE),
            report.unmet,
        )
        assertEquals(QualityTier.HD30, report.maxTier)
    }

    @Test
    fun `a weak link below 720p still floors at 480p`() {
        val report = FourGates.evaluate(allOpen.copy(sustainedKbps = 1_000))
        assertEquals(QualityTier.SD480, report.maxTier)
    }

    @Test
    fun `the headline names the ceiling and the reason`() {
        val open = FourGates.evaluate(allOpen)
        assertEquals("本机最高支持 4K", FourGates.headline(open))

        val weak = FourGates.evaluate(withScreen(false))
        assertEquals(
            "本机最高支持 1080p60 · 未达 4K：屏幕输出",
            FourGates.headline(QualityTier.FHD60, weak),
        )
        assertFalse(weak.reaches4K)
    }

    @Test
    fun `the headline reports the real ceiling when hard decode is lower than the gates`() {
        // 门全通但硬解只到 720p：页面必须写 720p，否则又是「宣传 4K、实际 720p」
        val report = FourGates.evaluate(allOpen)
        assertEquals("本机最高支持 720p30", FourGates.headline(QualityTier.HD30, report))
    }

    @Test
    fun `gate wording matches the PRD table`() {
        assertEquals("屏幕输出", FourGate.SCREEN.label)
        assertEquals("解码能力", FourGate.DECODER.label)
        assertEquals("网络带宽", FourGate.NETWORK.label)
        assertEquals("接口带宽", FourGate.INTERFACE.label)
        assertEquals("最高档位止于 1080p", FourGate.SCREEN.unmet)
        assertEquals("降为 1440p 或 1080p", FourGate.DECODER.unmet)
        assertEquals("降至可维持档位", FourGate.NETWORK.unmet)
        assertEquals("不启用 4K 档位", FourGate.INTERFACE.unmet)
    }
}

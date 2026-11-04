package io.github.wangnan05563.airmirror.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内实测带宽（PRD FS-05「稳定可用带宽」与「带宽骤降」的证据来源）。
 *
 * 两条底线：①「量不到」必须回 0，而不是回一个看起来像事实的数字；
 * ②「带宽骤降」的判据不能被静止画面骗——静止时字节速率本身就低，
 * 真正区分两者的是媒体时钟有没有被链路压住。
 */
class BandwidthGaugeTest {

    private val window = BandwidthGauge.DEFAULT_WINDOW_MS

    /**
     * 按 [kbps] 的码率、每 [frameMs] 一帧灌流，从 [fromMs] 灌到 [toMs]（不含）。
     * [clockRatio] 是媒体时间与墙钟时间的比值：1.0 为实时产出，0.5 表示墙钟走 2 秒而
     * pts 只推进 1 秒（链路把流压住）；传 null 表示发送端不给可用 pts（恒 0）。
     */
    private fun pump(
        gauge: BandwidthGauge,
        kbps: Int,
        fromMs: Long,
        toMs: Long,
        frameMs: Long = 100L,
        clockRatio: Double? = 1.0,
    ) {
        val bytesPerFrame = kbps * frameMs / 8
        var t = fromMs
        while (t < toMs) {
            // pts 按墙钟绝对时刻折算，多次 pump 之间时间轴保持连续（换速率不改时间轴）
            val ptsUs = if (clockRatio == null) 0L else (t * 1000L * clockRatio).toLong()
            gauge.add(bytesPerFrame.toInt(), ptsUs, t)
            t += frameMs
        }
    }

    @Test
    fun `nothing is measured before a full window closes`() {
        val gauge = BandwidthGauge()
        assertEquals(0, gauge.sustainedKbps())
        gauge.add(1_000_000, 0L, 500L)
        assertEquals("单个不完整窗口不能当成已量到", 0, gauge.sustainedKbps())
    }

    @Test
    fun `a steady stream reads its own rate`() {
        val gauge = BandwidthGauge()
        pump(gauge, 25_000, fromMs = 0L, toMs = 4 * window)
        pump(gauge, 25_000, fromMs = 4 * window, toMs = 4 * window + 100L)
        assertEquals(25_000, gauge.sustainedKbps())
    }

    @Test
    fun `a near-static stream still reads a rate instead of zero`() {
        val gauge = BandwidthGauge()
        // 一屏静止内容实测约 50kbps：换成 Mbps 取整就是 0，判决会当成「没量到」而永不触发
        pump(gauge, 50, fromMs = 0L, toMs = 4 * window)
        pump(gauge, 50, fromMs = 4 * window, toMs = 4 * window + 100L)
        assertEquals(50, gauge.sustainedKbps())
    }

    @Test
    fun `a burst does not become the sustained rate`() {
        val gauge = BandwidthGauge()
        pump(gauge, 10_000, fromMs = 0L, toMs = 4 * window)
        // 突发一窗 60Mbps：取最近两窗更低的那一个，判决跟着链路走而不是跟着高峰走
        pump(gauge, 60_000, fromMs = 4 * window, toMs = 5 * window)
        assertEquals(10_000, gauge.sustainedKbps())
    }

    @Test
    fun `a collapse is confirmed when the slow window closes`() {
        val gauge = BandwidthGauge()
        pump(gauge, 40_000, fromMs = 0L, toMs = 6 * window)
        assertEquals(40_000, gauge.sustainedKbps())
        // 骤降的那些帧属于还没关的窗口，所以判据要等这个窗口关掉：
        // 最迟两窗（≈4 秒）认出来，比凭半窗证据来回动画质稳
        pump(gauge, 5_000, fromMs = 6 * window, toMs = 7 * window)
        assertEquals("低速率那一窗还没关完，不动画质", 40_000, gauge.sustainedKbps())
        pump(gauge, 5_000, fromMs = 7 * window, toMs = 8 * window)
        assertEquals(5_000, gauge.sustainedKbps())
    }

    @Test
    fun `a starved media clock is caught even when the byte rate looks fine`() {
        val gauge = BandwidthGauge()
        // 编码器被限流到链路能给的量，到达速率仍然踩着档位下限，但每一帧都在往后拖
        pump(gauge, 3_000, fromMs = 0L, toMs = 4 * window, clockRatio = 0.5)
        assertTrue(gauge.hasMediaClock)
        assertTrue("媒体时间连续落后墙钟两窗即判撑不住", gauge.isStarved())
    }

    @Test
    fun `a still screen is not starvation`() {
        val gauge = BandwidthGauge()
        // 静止画面字节极少，但帧帧按时到齐：这不能读成「带宽骤降」，否则画面一不动就降档
        pump(gauge, 50, fromMs = 0L, toMs = 4 * window)
        assertTrue(gauge.hasMediaClock)
        assertFalse(gauge.isStarved())
    }

    @Test
    fun `jitter within the tolerance is not starvation`() {
        val gauge = BandwidthGauge()
        pump(gauge, 8_000, fromMs = 0L, toMs = 4 * window, clockRatio = 0.9)
        assertFalse("落后不足 ${BandwidthGauge.STARVE_TOLERANCE_PERCENT}% 只是抖动", gauge.isStarved())
    }

    @Test
    fun `a sender without usable pts gives no clock verdict`() {
        val gauge = BandwidthGauge()
        pump(gauge, 1_000, fromMs = 0L, toMs = 4 * window, clockRatio = null)
        assertFalse("pts 恒为 0：无从判断媒体时钟，调用方只能退回字节速率判据", gauge.hasMediaClock)
        assertFalse(gauge.isStarved())
    }

    @Test
    fun `the live rate shows before a window closes so the badge is never empty`() {
        val gauge = BandwidthGauge()
        // 20Mbps 跑 1 秒是 2_500_000 字节，窗口还没关也要能读出当前速率
        gauge.add(2_500_000, 0L, 1_000L)
        assertEquals(20_000, gauge.liveKbps(2_000L))
        assertEquals("没收到过字节时不给假读数", 0, BandwidthGauge().liveKbps(5_000L))
    }

    @Test
    fun `a new session starts over`() {
        val gauge = BandwidthGauge()
        pump(gauge, 3_000, fromMs = 0L, toMs = 4 * window, clockRatio = 0.5)
        assertTrue(gauge.isStarved())
        gauge.reset()
        assertEquals(0, gauge.sustainedKbps())
        assertEquals(0, gauge.liveKbps(10_000L))
        assertFalse(gauge.hasMediaClock)
        assertFalse(gauge.isStarved())
    }

    @Test
    fun `out of order timestamps restart the window instead of computing a negative rate`() {
        val gauge = BandwidthGauge()
        pump(gauge, 20_000, fromMs = 0L, toMs = 4 * window)
        pump(gauge, 20_000, fromMs = 4 * window, toMs = 4 * window + 100L)
        // 乱序的这一批把窗口起点挪回 1000ms，之后按新起点重算，不出现负速率
        pump(gauge, 5_000, fromMs = 1_000L, toMs = 3_000L)
        pump(gauge, 5_000, fromMs = 3_000L, toMs = 5_000L)
        assertEquals(5_000, gauge.sustainedKbps())
    }
}

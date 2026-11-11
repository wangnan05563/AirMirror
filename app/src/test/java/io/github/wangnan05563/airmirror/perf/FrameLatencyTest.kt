package io.github.wangnan05563.airmirror.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLatencyTest {

    @Test
    fun `snapshot describes only the current window`() {
        val latency = FrameLatency()
        // 起播的高延迟只进累计直方图，不能污染降级策略用的窗口
        latency.record(4_000L)
        latency.record(-5L)
        latency.reset()
        for (value in 1L..30L) latency.record(value)
        val window = latency.snapshot()
        assertEquals(30L, window.frames)
        assertEquals(27L, window.p90Ms)
        assertEquals(30L, window.maxMs)
        assertEquals(15L, window.p50Ms)
        assertEquals("换流水线前的坏点不该留在新窗口", 0L, window.clockSkew)
        assertEquals(0L, window.overWindow)
        assertTrue(window.isTrustworthy)
        latency.reset()
        assertEquals(0L, latency.snapshot().frames)
        assertFalse(latency.snapshot().isTrustworthy)
    }

    @Test
    fun `超大正延迟只记成超窗口 不会把桶下标撑爆`() {
        val latency = FrameLatency()
        // 两端 uptime 差几十天时，pts 与渲染时刻相减就是这个量级；
        // 先 toInt 截成负下标会在 counts[负] 处直接抛 ArrayIndexOutOfBoundsException
        latency.record(3_000_000_000L)
        latency.record(Long.MAX_VALUE)
        assertEquals(2L, latency.samples)
        assertEquals(2L, latency.overCount)
        assertEquals(Long.MAX_VALUE, latency.maxMs())
        assertEquals(0L, latency.clockSkewCount)
    }

    @Test
    fun `samples skewed by the clock make the window untrustworthy`() {
        val latency = FrameLatency()
        repeat(10) { latency.record(it + 1L) }
        repeat(25) { latency.record(-1L) }
        val window = latency.snapshot()
        assertEquals(10L, window.frames)
        assertEquals(25L, window.clockSkew)
        assertFalse(window.isTrustworthy)
    }

    @Test
    fun `no samples reports zeros`() {
        val latency = FrameLatency()
        assertEquals(0L, latency.samples)
        assertEquals(0L, latency.percentile(50))
        assertEquals(0L, latency.percentile(90))
        assertEquals(0L, latency.averageMs())
        assertEquals(0L, latency.maxMs())
        assertEquals("n=0 p50=0ms p90=0ms p99=0ms avg=0ms max=0ms", latency.summary())
    }

    @Test
    fun `percentiles land on the recorded value when one millisecond per bucket`() {
        val latency = FrameLatency()
        for (value in 1L..100L) latency.record(value)
        assertEquals(100L, latency.samples)
        assertEquals(1L, latency.percentile(0))
        assertEquals(50L, latency.percentile(50))
        assertEquals(90L, latency.percentile(90))
        assertEquals(99L, latency.percentile(99))
        assertEquals(100L, latency.percentile(100))
        assertEquals(50L, latency.averageMs())
        assertEquals(100L, latency.maxMs())
    }

    @Test
    fun `p90 ignores the slow tail the way the budget is written`() {
        val latency = FrameLatency()
        // 90 帧 40ms + 10 帧 300ms：P90 仍应卡在预算内，P99 才暴露长尾
        repeat(90) { latency.record(40) }
        repeat(10) { latency.record(300) }
        assertEquals(40L, latency.percentile(50))
        assertEquals(40L, latency.percentile(90))
        assertEquals(300L, latency.percentile(99))
        assertEquals(300L, latency.maxMs())
    }

    @Test
    fun `coarser buckets report the lower edge of the bucket`() {
        val latency = FrameLatency(bucketMs = 5, bucketCount = 10)
        latency.record(12)
        assertEquals(10L, latency.percentile(50))
        assertEquals(12L, latency.maxMs())
    }

    @Test
    fun `samples beyond the window are counted separately`() {
        val latency = FrameLatency(bucketMs = 1, bucketCount = 100)
        repeat(99) { latency.record(30) }
        latency.record(6_000)
        assertEquals(1L, latency.overCount)
        assertEquals(6_000L, latency.maxMs())
        // 超窗样本压到窗口下沿，摘要必须点名，否则会把 6 秒的卡顿说成 99ms
        assertEquals(99L, latency.percentile(100))
        assertEquals("n=100 p50=30ms p90=30ms p99=30ms avg=89ms max=6000ms over=1", latency.summary())
    }

    @Test
    fun `negative samples mean the clocks disagree`() {
        val latency = FrameLatency()
        latency.record(-1)
        latency.record(-800)
        assertEquals(0L, latency.samples)
        assertEquals(2L, latency.clockSkewCount)
        assertEquals(0L, latency.percentile(90))
        assertEquals("n=0 p50=0ms p90=0ms p99=0ms avg=0ms max=0ms skew=2", latency.summary())
    }

    @Test
    fun `reset clears buckets counters and extremes`() {
        val latency = FrameLatency()
        repeat(50) { latency.record(120) }
        latency.record(-5)
        latency.reset()
        assertEquals(0L, latency.samples)
        assertEquals(0L, latency.clockSkewCount)
        assertEquals(0L, latency.maxMs())
        latency.record(10)
        assertEquals(10L, latency.percentile(50))
    }
}

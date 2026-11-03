package io.github.wangnan05563.airmirror.perf

/**
 * 帧延迟分位数统计：按固定毫秒宽度的桶累计，内存恒定。
 *
 * 「P90 ≤150ms」这类指标必须在低内存盒子上跑几小时也拿得出分位数，
 * 留存样本数组（60fps × 8h ≈ 170 万点）不可行，因此只留桶计数与极值。
 * 落在同一个毫秒桶内的样本不可区分，取该桶下沿作为分位数值。
 */
class FrameLatency(private val bucketMs: Int = DEFAULT_BUCKET_MS, private val bucketCount: Int = DEFAULT_BUCKETS) {
    private val counts = IntArray(bucketCount)

    /** 参与统计的有效样本数 */
    var samples = 0L
        private set

    private var totalMs = 0L
    private var maxMs = 0L

    /** 超出统计窗口（bucketMs × bucketCount）的样本数：分位数会被压到窗口下沿，报告时要点名 */
    var overCount = 0L
        private set

    /**
     * 时钟基准不一致导致的负延迟样本数。
     * 采集时间戳与渲染时刻不在同一基准上时才会出现，非零说明打点本身不可信。
     */
    var clockSkewCount = 0L
        private set

    fun record(latencyMs: Long) {
        if (latencyMs < 0) {
            clockSkewCount++
            return
        }
        samples++
        totalMs += latencyMs
        if (latencyMs > maxMs) maxMs = latencyMs
        val index = (latencyMs / bucketMs).toInt()
        if (index >= bucketCount) {
            overCount++
            counts[bucketCount - 1]++
        } else {
            counts[index]++
        }
    }

    /** 第 [percent] 百分位的延迟（毫秒）：取累计计数首次达到 ceil(n×p/100) 的那个桶的下沿。 */
    fun percentile(percent: Int): Long {
        if (samples == 0L) return 0L
        val p = percent.coerceIn(0, 100)
        var rank = (samples * p + 99) / 100
        if (rank < 1) rank = 1
        var accumulated = 0L
        for (index in counts.indices) {
            accumulated += counts[index]
            if (accumulated >= rank) return index.toLong() * bucketMs
        }
        return maxMs
    }

    fun averageMs(): Long = if (samples == 0L) 0L else totalMs / samples

    fun maxMs(): Long = maxMs

    /** 取一张窗口快照交给延迟降级策略；调用方随后 [reset] 开始下一个窗口。 */
    fun snapshot(): LatencyWindow = LatencyWindow(
        frames = samples,
        p50Ms = percentile(50),
        p90Ms = percentile(90),
        averageMs = averageMs(),
        maxMs = maxMs,
        overWindow = overCount,
        clockSkew = clockSkewCount,
    )

    fun reset() {
        counts.fill(0)
        samples = 0L
        totalMs = 0L
        maxMs = 0L
        overCount = 0L
        clockSkewCount = 0L
    }

    /** 一行式摘要，直接进 logcat 与投屏状态行，便于「双端打点」取证。 */
    fun summary(): String {
        val builder = StringBuilder()
        builder.append("n=").append(samples)
            .append(" p50=").append(percentile(50)).append("ms")
            .append(" p90=").append(percentile(90)).append("ms")
            .append(" p99=").append(percentile(99)).append("ms")
            .append(" avg=").append(averageMs()).append("ms")
            .append(" max=").append(maxMs).append("ms")
        if (overCount > 0) builder.append(" over=").append(overCount)
        if (clockSkewCount > 0) builder.append(" skew=").append(clockSkewCount)
        return builder.toString()
    }

    companion object {
        const val DEFAULT_BUCKET_MS = 1

        /** 统计窗口 5000ms：远超镜像延迟的量级，又只是 20KB 计数数组 */
        const val DEFAULT_BUCKETS = 5_000
    }
}

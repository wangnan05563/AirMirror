package io.github.wangnan05563.airmirror.perf

/**
 * 会话内实测带宽（PRD FS-05 的「稳定可用带宽」与「带宽骤降」判定）。
 *
 * 两个读数缺一不可：
 * ① 字节速率（[sustainedKbps] / [liveKbps]）——链路此刻真的送过来多少；
 * ② 媒体时钟有没有跟上墙钟（[isStarved]）——发送端产出的时间轴是否被压住。
 *
 * 只用①判「带宽骤降」会误伤静止画面：编码器是 VBR，画面不动时几乎不产字节，
 * 速率能读到几十 kbps，看着像链路崩了，其实每帧都按时到齐、画面毫不卡。
 * ②与内容无关——帧的 pts 推进明显慢于真实时间，才是「这一档放不下」。
 * 老发送端的 pts 不可用（不递增）时只能退回①，此时静止画面的误判无法避免。
 *
 * 速率一律用 kbps：镜像一屏静止内容约 0.05 Mbps，按 Mbps 取整会读成 0，
 * 角标写出「0 Mbps」是假话，判决又会把 0 当成「还没量到」而永不触发。
 *
 * 窗口按 [windowMs] 滚动，内存恒定（只有两窗口的累计值），长跑不增长。
 */
class BandwidthGauge(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var windowStartMs = -1L
    private var windowBytes = 0L
    private var windowFirstPtsUs = -1L
    private var windowLastPtsUs = -1L
    private var currentKbps = 0
    private var previousKbps = 0
    private var completedWindows = 0

    /** 最近一个完整窗口是否拿到了可用的媒体时钟（pts 在窗口内递增） */
    private var mediaClockUsable = false
    private var starvedStreak = 0

    /** 一段媒体字节到达（[ptsUs] 为该帧的媒体时间戳，[nowMs] 为收到的墙钟时刻） */
    fun add(bytes: Int, ptsUs: Long, nowMs: Long) {
        if (bytes <= 0) return
        if (windowStartMs < 0L || nowMs < windowStartMs) openWindow(nowMs)
        val elapsed = nowMs - windowStartMs
        if (elapsed >= windowMs) {
            closeWindow(elapsed)
            openWindow(nowMs)
        }
        // 关窗之后到达的这一笔算下一个窗口：把它算进刚关的窗口等于把时长 2 秒的流量摊到 2 秒外，
        // 稳定码流会被读成两倍带宽，降档判决就会凭一个不存在的余量往上回档
        windowBytes += bytes
        if (windowFirstPtsUs < 0L) windowFirstPtsUs = ptsUs else windowLastPtsUs = ptsUs
    }

    /**
     * 稳定可用带宽（kbps）：取最近两个完整窗口里更低的那个。
     * 只看单窗会把一次突发读成常态带宽；不足两个窗口回 0，表示还没量到。
     */
    fun sustainedKbps(): Int = if (completedWindows < 2) 0 else minOf(previousKbps, currentKbps)

    /** 实时速率，给画面角标用：不足一个完整窗口也要能出数，否则角标一直空着 */
    fun liveKbps(nowMs: Long): Int {
        if (windowStartMs < 0L) return 0
        return rateOf(windowBytes, (nowMs - windowStartMs).coerceAtLeast(1L))
    }

    /** 本会话的发送端是否提供了可用 pts；不可用时调用方只能按字节速率判骤降 */
    val hasMediaClock: Boolean get() = mediaClockUsable

    /**
     * 链路是否已经堵到放不下当前档位：媒体时间连续 [STARVE_WINDOWS] 个窗口
     * 都比墙钟走得慢 [STARVE_TOLERANCE_PERCENT]% 以上。
     */
    fun isStarved(): Boolean = mediaClockUsable && starvedStreak >= STARVE_WINDOWS

    fun reset() {
        openWindow(-1L)
        currentKbps = 0
        previousKbps = 0
        completedWindows = 0
        mediaClockUsable = false
        starvedStreak = 0
    }

    private fun openWindow(nowMs: Long) {
        windowStartMs = nowMs
        windowBytes = 0L
        windowFirstPtsUs = -1L
        windowLastPtsUs = -1L
    }

    private fun closeWindow(elapsedMs: Long) {
        previousKbps = currentKbps
        currentKbps = rateOf(windowBytes, elapsedMs)
        completedWindows++
        // 一个窗口里的媒体推进量按「首帧到末帧」算，少算一个帧间隔（30fps 约 33ms），
        // 相对 2 秒窗口是 1.6% 的误差，方向偏保守（更容易判成堵），可以接受。
        // pts 从 0 起步是正常编码器的写法，所以只看窗口内是否递增，不看绝对值
        val mediaMs = if (windowFirstPtsUs >= 0L && windowLastPtsUs > windowFirstPtsUs)
            (windowLastPtsUs - windowFirstPtsUs) / 1000L else -1L
        if (mediaMs < 0L) {
            mediaClockUsable = false
            starvedStreak = 0
            return
        }
        mediaClockUsable = true
        starvedStreak = if (mediaMs * 100L < elapsedMs * (100L - STARVE_TOLERANCE_PERCENT)) starvedStreak + 1 else 0
    }

    private fun rateOf(bytes: Long, elapsedMs: Long): Int =
        if (elapsedMs <= 0L) 0 else ((bytes * 8L) / elapsedMs).toInt()

    companion object {
        /** 2 秒：更短会被一次 GOP 的突发带偏，更长则「带宽骤降」要等太久才反应 */
        const val DEFAULT_WINDOW_MS = 2_000L

        /** 媒体时间落后墙钟 25% 以上才算堵：留出帧抖动与调度延迟的余量，不把瞬时抖动读成骤降 */
        const val STARVE_TOLERANCE_PERCENT = 25L

        /** 单窗证据不算数，连续两窗（约 4 秒）才判链路撑不住 */
        const val STARVE_WINDOWS = 2
    }
}

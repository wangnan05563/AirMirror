package io.github.wangnan05563.airmirror.net

/**
 * FR-02 / FS-07 的发现节拍与判决（PRD 状态与异常表三行：AP 隔离、双频分离、组播被禁用）。
 *
 * 三路通道是**并行**的（PRD「多路并行监听，任一路命中即可展示」），轮次只是重发节奏：
 * 每一轮同时补发广播探测与定向单播探测，组播那一路由 NSD 自己持续监听。
 * 判「组播被禁用」看的是结果而不是网络配置——组播一路始终没命中、另外任意一路命中了，
 * 就是需求里那句「广播无响应但可直连」，此时**不提示**，列表照常见到设备。
 * 三轮下来一路都没命中才给原因，因为再早给都只是把「还在搜」说成「搜不到」。
 */
class DiscoveryLadder(
    private val roundMillis: Long = DEFAULT_ROUND,
    private val maxRounds: Int = DEFAULT_ROUNDS,
) {
    enum class Channel { MULTICAST, BROADCAST, DIRECT }

    /** 空列表的原因：还没搜完、被网络隔开、还是两台根本不在同一个频段。 */
    enum class Empty { SEARCHING, AP_ISOLATED, DUAL_BAND }

    sealed class Verdict {
        /** [downgraded] 为真表示这次靠的是广播/直连，属于已按需求静默降级，界面不该再说话。 */
        data class Found(val downgraded: Boolean, val via: Set<Channel>) : Verdict()

        data class Nothing(val reason: Empty, val notice: String) : Verdict()
    }

    private val hits = LinkedHashSet<Channel>()
    private var startedAt = 0L
    private var lastProbedAt = 0L

    /** [start] 之后已走完几轮，上限就是 [maxRounds]；没起表时算 0。 */
    fun roundsDone(now: Long): Int {
        if (startedAt == 0L) return 0
        return ((now - startedAt) / roundMillis).toInt().coerceIn(0, maxRounds)
    }

    /** 搜索窗口是否已经走完：走完才允许对用户下「搜不到」的结论。 */
    fun exhausted(now: Long): Boolean = startedAt != 0L && now - startedAt >= roundMillis * maxRounds

    /**
     * 这一趟该发探测了没有。命中之后就再也不发：大屏已经在列表里，继续敲它的门只是给
     * 一台已经连上的设备刷无谓的包。
     */
    fun probeDue(now: Long): Boolean {
        if (startedAt == 0L || hits.isNotEmpty() || exhausted(now)) return false
        return now - lastProbedAt >= roundMillis / PROBE_PER_ROUND
    }

    /** 发过一轮探测就记一次时刻，[probeDue] 按它控节奏。 */
    fun markProbed(now: Long) {
        lastProbedAt = now
    }

    fun start(now: Long) {
        startedAt = now
        lastProbedAt = 0L
        hits.clear()
    }

    fun noteHit(channel: Channel) {
        hits.add(channel)
    }

    fun hitChannels(): Set<Channel> = hits.toSet()

    /**
     * [cachedCrossSubnet] 来自上次连过的接收端地址：本机换了网段而缓存地址还在旧网段，
     * 就是需求里的「双频分离/跨频段不可见」；网段没变还搜不到才更像 AP 隔离。
     */
    fun verdict(now: Long, cachedCrossSubnet: Boolean): Verdict {
        if (hits.isNotEmpty()) {
            return Verdict.Found(downgraded = Channel.MULTICAST !in hits, via = hits.toSet())
        }
        if (!exhausted(now)) return Verdict.Nothing(Empty.SEARCHING, "")
        return if (cachedCrossSubnet) {
            Verdict.Nothing(Empty.DUAL_BAND, TEXT_DUAL_BAND)
        } else {
            Verdict.Nothing(Empty.AP_ISOLATED, TEXT_AP_ISOLATED)
        }
    }

    companion object {
        const val DEFAULT_ROUND = 1_500L
        const val DEFAULT_ROUNDS = 3

        /** 探测在每一轮里发几次：一轮只发一次的话，路由器恰好丢掉那一个包就整轮白等。 */
        private const val PROBE_PER_ROUND = 2

        /** 文案与 PRD 状态与异常表逐字对齐，被单测锁死。 */
        const val TEXT_AP_ISOLATED = "当前网络禁止设备互访，可关闭 AP 隔离或改用扫码"
        const val TEXT_DUAL_BAND = "手机与电视可能不在同一频段"
    }
}

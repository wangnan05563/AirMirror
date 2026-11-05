package io.github.wangnan05563.airmirror.receiver

/**
 * 协议模块的闲置释放判据（PRD 1152：A/B 级协议常驻，C 级按需加载、闲置 5 分钟释放）。
 *
 * 释放的是**协议运行时**（播放器/解码器/已载入的媒体这类重资源），不是监听入口：
 * DLNA 的 SSDP/HTTP 监听一旦停掉，控制点就再也找不到这台设备，那是拿功能残废换内存，不能算「按需加载」。
 * 下一次真正用到（SetAVTransportURI）时播放器本来就是懒建的，天然满足「按需加载」。
 *
 * 私有通道不在这里跟踪：它没有可释放的常驻运行时（会话资源随每条连接进出），监听必须一直在。
 */
enum class ProtocolModule(val label: String) {
    DLNA("DLNA 推送"),
    PRIVATE_CHANNEL("私有通道"),
}

/**
 * 闲置计时与时机的唯一判据，时钟由外部注入，便于在 JVM 里逐毫秒断言。
 *
 * 方法都加锁：活动来自多条连接线程（DLNA 的每个请求一条），释放由主线程巡检触发，
 * 两个线程池都会碰这份计时表。
 */
class ProtocolResidency(
    private val now: () -> Long,
    private val releaseAfterMillis: Long = IDLE_RELEASE_MILLIS,
) {
    private val lastActive = LinkedHashMap<ProtocolModule, Long>()
    private val busy = mutableSetOf<ProtocolModule>()

    /** 模块被加载/被用到：记一笔活动。已释放的模块再被用到时按需重新跟踪。 */
    @Synchronized
    fun activity(module: ProtocolModule) {
        lastActive[module] = now()
    }

    /**
     * 会话/播放中钉住：忙的时候永远不进释放名单。
     * 解除钉住的那一瞬要记一笔活动——闲置 5 分钟应从「播完/会话结束」开始算，
     * 而不是从电影开播前那一次请求算起，否则一部长片看完会立刻被当成闲置。
     */
    @Synchronized
    fun hold(module: ProtocolModule, active: Boolean) {
        if (active) busy += module else busy -= module
        if (module in lastActive) lastActive[module] = now()
    }

    /** 这一轮该释放哪些模块。只返回被跟踪、不忙、且空闲达到阈值（5 分钟）的。 */
    @Synchronized
    fun releaseCandidates(): List<ProtocolModule> {
        val t = now()
        return lastActive.filter { (module, at) -> module !in busy && t - at >= releaseAfterMillis }.keys.toList()
    }

    @Synchronized
    fun markReleased(module: ProtocolModule) {
        lastActive.remove(module)
        busy.remove(module)
    }

    @Synchronized
    fun isTracked(module: ProtocolModule): Boolean = module in lastActive

    @Synchronized
    fun idleMillis(module: ProtocolModule): Long = lastActive[module]?.let { now() - it } ?: 0L

    companion object {
        /** PRD 1152 的「闲置 5 分钟释放」 */
        const val IDLE_RELEASE_MILLIS = 5 * 60_000L

        /** 巡检粒度：只要远小于释放窗口，多点开销换判断及时 */
        const val TICK_MILLIS = 30_000L
    }
}

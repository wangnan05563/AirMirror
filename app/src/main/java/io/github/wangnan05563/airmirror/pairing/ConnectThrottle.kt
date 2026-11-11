package io.github.wangnan05563.airmirror.pairing

/**
 * PRD 8.9「防滥用：单设备连接请求频率限制」。
 *
 * 与投屏码错误限流（[CastCode]）管的是两件事：那边数「码输错几次」，这里数「同一来源被拒了几回」。
 * 只靠前者挡不住刷子——它可以每轮换一个形状合法的码、或者干脆连 AUTH 都不发，把接收端拖在握手
 * 与超时上，这类连接一次都不会记进投屏码的失败账。
 *
 * 只给**被拒**的连接记账，授权成功一律清零：FS-10 断线恢复本来就是「短时间内重连三次」，
 * 把正常恢复也算进频率里，等于自己把自己的恢复路径限死。
 */
class ConnectThrottle(
    private val now: () -> Long,
    private val windowMillis: Long = DEFAULT_WINDOW,
    private val maxRefusals: Int = DEFAULT_MAX_REFUSALS,
    private val baseBlockMillis: Long = DEFAULT_BLOCK,
    private val maxBlockMillis: Long = DEFAULT_MAX_BLOCK,
    private val trackedKeys: Int = DEFAULT_TRACKED_KEYS,
) {
    private class State {
        val refusals = ArrayList<Long>()

        /** 0 = 没在服刑。用 0 而不是「负无穷」做哨兵，减法不会绕回一个天文数字的剩余时间。 */
        var blockedUntil = 0L
        var strikes = 0
        var lastSeenAt = 0L
    }

    private val states = LinkedHashMap<String, State>()

    /** 还剩多少毫秒才放行，0 表示当前没被限。查询本身算一次「见过」，否则正被限住的来源会被 prune 剪掉。 */
    @Synchronized
    fun blockedFor(key: String): Long {
        // 地址缺失时不记账：宁可不拦，也不要把拿不到来源的连接跟别人捆在一起拦掉
        if (key.isBlank()) return 0L
        val t = now()
        val state = states.getOrPut(key) { State() }
        state.lastSeenAt = t
        return (state.blockedUntil - t).coerceAtLeast(0L)
    }

    /** 向上取整到秒：界面显示「还剩 0 秒」时静默其实还没到期，宁可多说一秒。 */
    @Synchronized
    fun secondsLeft(key: String): Int = ((blockedFor(key) + 999L) / 1000L).toInt()

    /**
     * 记一次被拒，返回记完之后还要等多久（0 表示没触发静默）。
     * 静默期内的再次撞击只回「还剩多久」，不叠加也不翻倍——否则一个不停敲门的客户端会把等待一路
     * 顶到上限，连机主要重连都要等 15 分钟。翻倍只发生在**上一轮静默结束后还继续犯**的来源上。
     */
    @Synchronized
    fun refuse(key: String): Long {
        if (key.isBlank()) return 0L
        val t = now()
        val state = states.getOrPut(key) { State() }
        val quietFor = t - state.lastSeenAt
        state.lastSeenAt = t
        if (t < state.blockedUntil) return state.blockedUntil - t
        // 安静满一个窗口就当前科对待：限流防的是「这一分钟的刷子」，不是上周犯过一次的人
        if (quietFor >= windowMillis) {
            state.strikes = 0
            state.refusals.clear()
        }
        state.refusals.add(t)
        state.refusals.removeAll { t - it >= windowMillis }
        if (state.refusals.size < maxRefusals) return 0L
        state.strikes += 1
        state.refusals.clear()
        state.blockedUntil = t + blockMillis(state.strikes)
        return state.blockedUntil - t
    }

    /** 授权成功：这个来源的账全清，正常用户不该为之前几次打错码继续背锅。 */
    @Synchronized
    fun allow(key: String) {
        states.remove(key)
    }

    /**
     * 剪掉静默已到期且一个窗口都没再出现的来源。条目仍超出上限时按最久未出现继续剪 ——
     * 记账对象是「同一来源被拒次数」，来源是刷子的 IP，不设上限就等于给攻击者开了一个内存增长点。
     */
    @Synchronized
    fun prune() {
        val t = now()
        states.entries.removeAll { (_, state) -> t >= state.blockedUntil && t - state.lastSeenAt >= windowMillis }
        // 上限只清没在静默期里的账：把还在服刑的来源挤掉等于让攻击者靠「多开几个新身份」把自己的记录丢掉
        while (states.size > trackedKeys) {
            val stale = states.entries
                .filter { t >= it.value.blockedUntil }
                .minByOrNull { it.value.lastSeenAt } ?: return
            states.remove(stale.key)
        }
    }

    private fun blockMillis(strikes: Int): Long {
        var block = baseBlockMillis
        repeat(strikes - 1) { if (block < maxBlockMillis) block = block * 2 }
        return block.coerceIn(baseBlockMillis, maxBlockMillis)
    }

    companion object {
        /** 60 秒内被拒 6 次起限：人手输错三次再看一眼码重来，到不了这个量级，只有脚本才会撞上去。 */
        const val DEFAULT_MAX_REFUSALS = 6

        /** 同时记账的来源上限：闸门本身不能变成攻击者的内存增长点。 */
        const val DEFAULT_TRACKED_KEYS = 64
        private const val DEFAULT_WINDOW = 60_000L
        private const val DEFAULT_BLOCK = 60_000L
        private const val DEFAULT_MAX_BLOCK = 15 * 60_000L
    }
}

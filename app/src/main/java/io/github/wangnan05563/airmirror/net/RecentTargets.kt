package io.github.wangnan05563.airmirror.net

/**
 * 上次连过的接收端地址缓存（PRD FS-07 参数「缓存展示 | 缓存有效期 10 分钟；过期后不再置顶但仍可搜索」）。
 *
 * 两件事靠它才做得成：
 * 1）首屏快速返回——一进来就把最近用的设备列出来，不等探测跑完；
 * 2）直连探测——组播和广播都被网络挡住时，照着旧地址单播问一句，这是需求里那条「可直连」的唯一入口。
 *
 * 过期的条目只退出**探测**与**置顶**，记录本身留着（就是那句「过期后不再置顶但仍可搜索」）。
 * 这里只有设备名与局域网地址，画面、文件名与 URL 一概不进（PRD 14.4）。
 */
class RecentTargets(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMillis: Long = DEFAULT_TTL,
) {
    data class Target(
        val fingerprint: String,
        val name: String,
        val host: String,
        val port: Int,
        val lastConnectedAt: Long,
    )

    private val entries = LinkedHashMap<String, Target>()

    /** 同一台设备换了地址也还是同一条：按设备指纹覆盖，列表与探测都只认一份。 */
    fun record(fingerprint: String, name: String, host: String, port: Int, now: Long) {
        if (fingerprint.isBlank() || host.isBlank() || port !in 1..65_535) return
        entries[fingerprint] = Target(fingerprint, name, host, port, now)
        trim()
    }

    /** 还在有效期内的条目，最近连成功的排前面。 */
    fun viable(now: Long): List<Target> = entries.values
        .filter { now - it.lastConnectedAt < ttlMillis }
        .sortedByDescending { it.lastConnectedAt }

    fun all(): List<Target> = entries.values.toList()

    /**
     * 本机和缓存里的设备不在同一个网段——「双频合一/跨频段不可见」的判据就靠这一句：
     * 手机连上了另一个频段、拿到新网段，而电视还在旧网段，那它注定搜不到，但不是 AP 隔离。
     * 只看还在有效期内的条目：上周连过的旧网段不能拿来给今天的搜索失败找借口。
     */
    fun crossSubnet(now: Long, localPrefix: String?): Boolean {
        if (localPrefix.isNullOrBlank()) return false
        return viable(now).any { it.host.substringBeforeLast('.') != localPrefix }
    }

    fun prune(now: Long) {
        entries.values.removeAll { now - it.lastConnectedAt >= ttlMillis * STALE_FACTOR }
    }

    private fun trim() {
        while (entries.size > capacity) {
            val stale = entries.values.minByOrNull { it.lastConnectedAt } ?: return
            entries.remove(stale.fingerprint)
        }
    }

    /** 落盘与读回：一行一条，名字用十六进制避开分隔符，与报文层同一套转义规则。 */
    fun encode(): String = entries.values.joinToString("\n") { target ->
        listOf(
            target.fingerprint,
            ProbeProtocol.hexEncode(target.name.toByteArray(Charsets.UTF_8)),
            target.host,
            target.port.toString(),
            target.lastConnectedAt.toString(),
        ).joinToString("|")
    }

    companion object {
        const val DEFAULT_CAPACITY = 8
        const val DEFAULT_TTL = 10 * 60_000L

        /** 超过有效期这么久才真删：缓存还要撑着「上次连接」的置灰条目，删太早列表会凭空闪一下。 */
        private const val STALE_FACTOR = 6L

        fun decode(text: String, capacity: Int = DEFAULT_CAPACITY, ttlMillis: Long = DEFAULT_TTL): RecentTargets {
            val store = RecentTargets(capacity, ttlMillis)
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val fields = line.split('|')
                if (fields.size != 5) return@forEach
                val name = ProbeProtocol.hexDecodeOrNull(fields[1]) ?: return@forEach
                val port = fields[3].toIntOrNull() ?: return@forEach
                val at = fields[4].toLongOrNull() ?: return@forEach
                store.record(fields[0], name, fields[2], port, at)
            }
            return store
        }
    }
}

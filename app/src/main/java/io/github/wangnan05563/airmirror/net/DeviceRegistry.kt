package io.github.wangnan05563.airmirror.net

/**
 * FR-02 设备列表的判定层：多路广播按设备指纹合并为一条并保留其最强协议能力，
 * 离线条目置灰保留 10 分钟后移除，排序为「最近成功连接 → 同网段 → 其他」。
 *
 * 展示模型 [Row] 刻意不含地址：卡片只给设备名、协议角标与在线状态，
 * 地址仅通过 [addressOf] 在真正发起连接时取用。
 */
class DeviceRegistry(
    private val now: () -> Long,
    private val sameSubnet: (String) -> Boolean = { false },
    private val offlineRetentionMillis: Long = DEFAULT_RETENTION,
) {
    data class Row(
        val fingerprint: String,
        val name: String,
        /** 按能力从强到弱排列，界面只取第一个作角标。 */
        val protocols: List<String>,
        val online: Boolean,
        val lastConnectedAt: Long?,
    ) {
        val strongest: String get() = protocols.firstOrNull().orEmpty()
    }

    private class Entry(
        val fingerprint: String,
        var name: String,
        val addresses: LinkedHashSet<String>,
        val protocols: MutableSet<String>,
        var lastSeenAt: Long,
        var lostAt: Long?,
        var lastConnectedAt: Long?,
    )

    private val entries = LinkedHashMap<String, Entry>()

    /** 同一次发现可能被 NSD/UPnP/组播各报一遍，地址与协议在合并条目上累加。 */
    fun report(fingerprint: String, name: String, protocol: String, address: String) {
        val t = now()
        val entry = entries.getOrPut(fingerprint) {
            Entry(fingerprint, name, LinkedHashSet(), LinkedHashSet(), t, null, null)
        }
        if (name.isNotBlank()) entry.name = name
        entry.addresses.add(address)
        entry.protocols.add(protocol)
        entry.lastSeenAt = t
        entry.lostAt = null
    }

    fun markLost(fingerprint: String) {
        entries[fingerprint]?.lostAt = now()
    }

    fun markConnected(fingerprint: String) {
        entries[fingerprint]?.lastConnectedAt = now()
    }

    fun addressOf(fingerprint: String): String? = entries[fingerprint]?.addresses?.firstOrNull()

    fun rows(): List<Row> {
        prune()
        return entries.values.map { entry ->
            Row(
                fingerprint = entry.fingerprint,
                name = entry.name,
                protocols = strongestFirst(entry.protocols),
                online = entry.lostAt == null,
                lastConnectedAt = entry.lastConnectedAt,
            )
        }.sortedWith(compareByDescending<Row> { it.lastConnectedAt ?: 0L }
            .thenByDescending { it.online && sameSubnet(addressOf(it.fingerprint) ?: "") }
            .thenByDescending { it.online }
            .thenByDescending { strongestRank(it.protocols) })
    }

    /** 列表为空时界面必须说明原因并给替代入口，这里区分「没搜到」与「全离线」。 */
    fun emptyReason(): EmptyReason {
        prune()
        val alive = entries.values.count { it.lostAt == null }
        return when {
            entries.isEmpty() -> EmptyReason.NONE_FOUND
            alive == 0 -> EmptyReason.ALL_OFFLINE
            else -> EmptyReason.HAS_ONLINE
        }
    }

    enum class EmptyReason { NONE_FOUND, ALL_OFFLINE, HAS_ONLINE }

    fun prune() {
        val t = now()
        entries.values.removeAll { entry ->
            val lost = entry.lostAt
            lost != null && t - lost >= offlineRetentionMillis
        }
    }

    private fun strongestFirst(protocols: Collection<String>): List<String> =
        protocols.sortedBy { STRONGEST_FIRST.indexOf(it).let { rank -> if (rank < 0) STRONGEST_FIRST.size else rank } }

    private fun strongestRank(protocols: List<String>): Int {
        val index = STRONGEST_FIRST.indexOf(protocols.firstOrNull().orEmpty())
        return if (index < 0) Int.MIN_VALUE else -index
    }

    private companion object {
        const val DEFAULT_RETENTION = 10 * 60_000L

        /** 接收端能力优先级：私有通道能承载镜像，其余仅推送。 */
        val STRONGEST_FIRST = listOf("AMRC", "AirPlay", "DLNA", "Cast")
    }
}

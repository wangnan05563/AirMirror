package io.github.wangnan05563.airmirror.pairing

/**
 * FR-03 首次连接授权：可信列表上限 32 台、拒绝后同一设备 10 分钟内不再弹窗、
 * 同一时刻只允许一个活动会话、扫码/投屏码是一次性授权而不入列表（除非用户选「记住这台设备」）。
 *
 * 5 秒无操作自动允许属于界面交互，这里只给出是否应当弹窗与自动允许的截止点，
 * 由待连接页计时后回调 [allow]，避免把定时器塞进可测的判定层。
 */
class TrustRegistry(
    private val now: () -> Long,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val suppressMillis: Long = DEFAULT_SUPPRESS,
    private val autoAllowMillis: Long = DEFAULT_AUTO_ALLOW,
) {
    enum class Verdict { TRUSTED, ASK, SUPPRESSED }

    data class Decision(val verdict: Verdict, val autoAllowAt: Long)

    data class TrustedDevice(
        val fingerprint: String,
        val name: String,
        val lastUsedAt: Long,
    )

    private class Record(val fingerprint: String, var name: String, var lastUsedAt: Long)

    private val records = LinkedHashMap<String, Record>()
    private val suppressed = HashMap<String, Long>()
    private var activeSession: String? = null

    @Synchronized
    fun decide(fingerprint: String): Decision {
        val t = now()
        if (records.containsKey(fingerprint)) return Decision(Verdict.TRUSTED, t)
        suppressed[fingerprint]?.let { if (t < it) return Decision(Verdict.SUPPRESSED, t) else suppressed.remove(fingerprint) }
        return Decision(Verdict.ASK, if (autoAllowMillis > 0L) t + autoAllowMillis else Long.MAX_VALUE)
    }

    /** 用户点「允许」，或 5 秒无操作后界面按默认策略代为允许。 */
    @Synchronized
    fun allow(fingerprint: String, name: String, permanent: Boolean) {
        val t = now()
        if (permanent) {
            records.put(fingerprint, Record(fingerprint, name, t))
            trim()
        }
        suppressed.remove(fingerprint)
    }

    @Synchronized
    fun deny(fingerprint: String) {
        suppressed[fingerprint] = now() + suppressMillis
        trimSuppressed()
        records.remove(fingerprint)
        if (activeSession == fingerprint) activeSession = null
    }

    @Synchronized
    fun revoke(fingerprint: String) {
        records.remove(fingerprint)
        if (activeSession == fingerprint) activeSession = null
    }

    /** 同一时刻仅一个活动会话：已被别的设备占用时返回 false。 */
    @Synchronized
    fun acquireSession(fingerprint: String): Boolean {
        val held = activeSession
        if (held != null && held != fingerprint) return false
        activeSession = fingerprint
        records[fingerprint]?.lastUsedAt = now()
        return true
    }

    @Synchronized
    fun releaseSession(fingerprint: String) {
        if (activeSession == fingerprint) activeSession = null
    }

    @Synchronized
    fun active(): String? = activeSession

    @Synchronized
    fun trusted(): List<TrustedDevice> = records.values
        .sortedByDescending { it.lastUsedAt }
        .map { TrustedDevice(it.fingerprint, it.name, it.lastUsedAt) }

    /** 授权与拒绝的判定随时间失效，供界面刷新「已连接的设备」列表时剪除过期条目。 */
    @Synchronized
    fun prune() {
        val t = now()
        suppressed.entries.removeAll { t >= it.value }
    }

    /** 落盘格式：每行 `id<TAB>name<TAB>lastUsedAt`，字段按 [PairingLink.escape] 转义。 */
    @Synchronized
    fun snapshot(): String = records.values.joinToString("\n") { record ->
        listOf(
            PairingLink.escape(record.fingerprint),
            PairingLink.escape(record.name),
            record.lastUsedAt.toString(),
        ).joinToString("\t")
    }

    /** 读取落盘数据并按指纹合并（只增不删，半截文件不会清空列表）；损坏行与超出上限的行按最近使用顺序丢弃。 */
    @Synchronized
    fun restore(text: String) {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val parsed = lines.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@mapNotNull null
            val usedAt = parts[2].toLongOrNull() ?: return@mapNotNull null
            val fingerprint = PairingLink.unescape(parts[0])
            if (fingerprint.isEmpty()) return@mapNotNull null
            Triple(fingerprint, PairingLink.unescape(parts[1]), usedAt)
        }.sortedByDescending { it.third }
        for ((fingerprint, name, usedAt) in parsed.take(capacity)) {
            records[fingerprint] = Record(fingerprint, name, usedAt)
        }
        trim()
    }

    private fun trim() {
        while (records.size > capacity) {
            val oldest = records.values.minByOrNull { it.lastUsedAt } ?: return
            records.remove(oldest.fingerprint)
        }
    }

    /**
     * 静默期表同样要封顶：每次拒绝都按设备指纹记一条，伪造随机指纹的人能让它无限增长。
     * 淘汰最早过期的条目——它们本来也快恢复可连了，影响最小。
     */
    private fun trimSuppressed() {
        while (suppressed.size > capacity) {
            val earliest = suppressed.entries.minByOrNull { it.value } ?: return
            suppressed.remove(earliest.key)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 32
        const val DEFAULT_SUPPRESS = 10 * 60_000L
        const val DEFAULT_AUTO_ALLOW = 5_000L
    }
}

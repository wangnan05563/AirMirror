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
    enum class Verdict { TRUSTED, ASK, SUPPRESSED, ONE_TIME }

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

    fun decide(fingerprint: String, requiresExplicitGrant: Boolean = true): Decision {
        val t = now()
        if (!requiresExplicitGrant) return Decision(Verdict.ONE_TIME, t)
        if (records.containsKey(fingerprint)) return Decision(Verdict.TRUSTED, t)
        suppressed[fingerprint]?.let { if (t < it) return Decision(Verdict.SUPPRESSED, t) else suppressed.remove(fingerprint) }
        return Decision(Verdict.ASK, if (autoAllowMillis > 0L) t + autoAllowMillis else Long.MAX_VALUE)
    }

    /** 用户点「允许」，或 5 秒无操作后界面按默认策略代为允许。 */
    fun allow(fingerprint: String, name: String, permanent: Boolean) {
        val t = now()
        if (permanent) {
            records.put(fingerprint, Record(fingerprint, name, t))
            trim()
        }
        suppressed.remove(fingerprint)
    }

    fun deny(fingerprint: String) {
        suppressed[fingerprint] = now() + suppressMillis
        records.remove(fingerprint)
        if (activeSession == fingerprint) activeSession = null
    }

    fun revoke(fingerprint: String) {
        records.remove(fingerprint)
        if (activeSession == fingerprint) activeSession = null
    }

    /** 同一时刻仅一个活动会话：已被别的设备占用时返回 false。 */
    fun acquireSession(fingerprint: String): Boolean {
        val held = activeSession
        if (held != null && held != fingerprint) return false
        activeSession = fingerprint
        records[fingerprint]?.lastUsedAt = now()
        return true
    }

    fun releaseSession(fingerprint: String) {
        if (activeSession == fingerprint) activeSession = null
    }

    fun active(): String? = activeSession

    fun trusted(): List<TrustedDevice> = records.values
        .sortedByDescending { it.lastUsedAt }
        .map { TrustedDevice(it.fingerprint, it.name, it.lastUsedAt) }

    /** 授权与拒绝的判定随时间失效，供界面刷新「已连接的设备」列表时剪除过期条目。 */
    fun prune() {
        val t = now()
        suppressed.entries.removeAll { t >= it.value }
    }

    private fun trim() {
        while (records.size > capacity) {
            val oldest = records.values.minByOrNull { it.lastUsedAt } ?: return
            records.remove(oldest.fingerprint)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 32
        const val DEFAULT_SUPPRESS = 10 * 60_000L
        const val DEFAULT_AUTO_ALLOW = 5_000L
    }
}

package io.github.wangnan05563.airmirror.pairing

/**
 * FR-03 首次连接授权：可信列表上限 32 台、拒绝后同一设备 10 分钟内不再弹窗、
 * 同一时刻只允许一个活动会话、扫码/投屏码是一次性授权而不入列表（除非用户选「记住这台设备」）。
 *
 * T06112 起可信记录带 `lastUsedAt`，默认 30 天未实际使用即过期；过期只让设备回到「每次确认」，
 * 不自动续期。落盘使用带版本头的 v2 快照，旧 v1 行可迁移；版本未知或快照损坏时清空信任状态，
 * 宁可让所有设备重新确认，也不把无法解释的数据当作可信身份。
 *
 * 5 秒无操作自动允许属于界面交互，这里只给出是否应当弹窗与自动允许的截止点，
 * 由待连接页计时后回调 [allow]，避免把定时器塞进可测的判定层。
 */
class TrustRegistry(
    private val now: () -> Long,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val suppressMillis: Long = DEFAULT_SUPPRESS,
    private val autoAllowMillis: Long = DEFAULT_AUTO_ALLOW,
    private val expiryMillis: Long = DEFAULT_TRUST_TTL,
) {
    enum class Verdict { TRUSTED, ASK, SUPPRESSED }

    /** restore 的结果；调用方可据此决定是否把迁移后的快照写回。 */
    enum class RestoreResult { EMPTY, RESTORED, MIGRATED, FAILED }

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
        val record = records[fingerprint]
        if (record != null) {
            if (expired(record.lastUsedAt, t)) {
                expire(fingerprint)
            } else {
                return Decision(Verdict.TRUSTED, t)
            }
        }
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

    /**
     * 撤销一台设备的信任。返回 true 表示撤销时它正占着活动会话，
     * 调用方应立即结束该次投屏（PRD FR-2.0-05「1 秒内停止」）。
     */
    @Synchronized
    fun revoke(fingerprint: String): Boolean {
        val wasActive = records.containsKey(fingerprint) && activeSession == fingerprint
        records.remove(fingerprint)
        if (wasActive) activeSession = null
        return wasActive
    }

    /** 改名只改展示名，不刷新 `lastUsedAt`；未知设备或空名字返回 false。 */
    @Synchronized
    fun rename(fingerprint: String, name: String): Boolean {
        val record = records[fingerprint] ?: return false
        val next = name.trim()
        if (next.isEmpty()) return false
        record.name = next
        return true
    }

    /**
     * 清空全部信任与静默；受信活动会话一并释放，一次性访客会话保留。
     * 返回值只表示“被清掉的活动会话原本是否属于信任列表”：一次性访客会话不应被这里误杀。
     */
    @Synchronized
    fun clear(): Boolean {
        val activeTrusted = activeSession?.let { records.containsKey(it) } == true
        val guest = activeSession?.takeIf { !records.containsKey(it) }
        reset()
        // 清信任列表不是“踢掉所有投屏”：一次性访客会话继续，只有受信会话交给 UI 断开。
        if (!activeTrusted) activeSession = guest
        return activeTrusted
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
    fun trusted(): List<TrustedDevice> {
        val t = now()
        return records.values
            .filterNot { expired(it.lastUsedAt, t) }
            .sortedByDescending { it.lastUsedAt }
            .map { TrustedDevice(it.fingerprint, it.name, it.lastUsedAt) }
    }

    /**
     * 授权与拒绝的判定随时间失效，供界面刷新「已连接的设备」列表时剪除过期条目。
     * 只清理“曾是可信任、但已过 30 天”的活动会话；一次性凭据会话不在 records 里，不能被误杀。
     */
    @Synchronized
    fun prune() {
        val t = now()
        val expired = records.values.filter { expired(it.lastUsedAt, t) }.map { it.fingerprint }
        expired.forEach { fingerprint ->
            records.remove(fingerprint)
            if (activeSession == fingerprint) activeSession = null
        }
        suppressed.entries.removeAll { t >= it.value }
    }

    /** 落盘格式：首行固定版本头，之后每行 `id<TAB>name<TAB>lastUsedAt`；字段按 [PairingLink.escape] 转义。 */
    @Synchronized
    fun snapshot(): String {
        prune()
        val rows = records.values.joinToString("\n") { record ->
            listOf(
                PairingLink.escape(record.fingerprint),
                PairingLink.escape(record.name),
                record.lastUsedAt.toString(),
            ).joinToString("\t")
        }
        return if (rows.isEmpty()) SNAPSHOT_HEADER else "$SNAPSHOT_HEADER\n$rows"
    }

    /**
     * 读取落盘数据。
     *
     * - 无版本头 = v1 旧格式：逐行容错迁移，坏行丢弃、有效行保留；一条有效行都没有则视为迁移失败。
     * - v2 版本头：整份快照替换当前列表；任一数据行损坏都视为快照不可信，整体回退。
     * - 未知版本头：迁移失败。
     * 失败时清空可信列表、静默期与活动会话，后续连接一律回到「每次确认」。
     */
    @Synchronized
    fun restore(text: String): RestoreResult {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            prune()
            return RestoreResult.EMPTY
        }
        val first = lines.first()
        if (first.startsWith("#")) {
            if (first != SNAPSHOT_HEADER) {
                reset()
                return RestoreResult.FAILED
            }
            val parsed = parseStrict(lines.drop(1))
            if (parsed == null) {
                reset()
                return RestoreResult.FAILED
            }
            replace(parsed)
            return RestoreResult.RESTORED
        }
        val parsed = parseLegacy(lines)
        if (parsed.isNullOrEmpty()) {
            reset()
            return RestoreResult.FAILED
        }
        merge(parsed)
        return RestoreResult.MIGRATED
    }

    private fun expired(lastUsedAt: Long, t: Long): Boolean =
        expiryMillis > 0L && t >= lastUsedAt && t - lastUsedAt >= expiryMillis

    private fun expire(fingerprint: String) {
        records.remove(fingerprint)
        if (activeSession == fingerprint) activeSession = null
    }

    private fun reset() {
        records.clear()
        suppressed.clear()
        activeSession = null
    }

    /** v1 没有版本头，无法区分“正常旧数据”和“乱码”，按行容错；至少一条有效行才算迁移成功。 */
    private fun parseLegacy(lines: List<String>): List<Triple<String, String, Long>>? {
        val parsed = lines.mapNotNull { parseRow(it) }
        return parsed.takeIf { it.isNotEmpty() }
    }

    /** v2 已声明 schema，任何坏行都意味着写入损坏；整份快照不采信。 */
    private fun parseStrict(lines: List<String>): List<Triple<String, String, Long>>? {
        val parsed = ArrayList<Triple<String, String, Long>>(lines.size)
        for (line in lines) parsed.add(parseRow(line) ?: return null)
        return parsed
    }

    private fun parseRow(line: String): Triple<String, String, Long>? {
        val parts = line.split('\t')
        if (parts.size != 3) return null
        val usedAt = parts[2].toLongOrNull() ?: return null
        val fingerprint = PairingLink.unescape(parts[0])
        if (fingerprint.isEmpty()) return null
        return Triple(fingerprint, PairingLink.unescape(parts[1]), usedAt)
    }

    /** v2 快照是完整状态，恢复时替换而不是合并，避免旧进程留下的半张表盖住新文件。 */
    private fun replace(parsed: List<Triple<String, String, Long>>) {
        records.clear()
        parsed.sortedByDescending { it.third }.take(capacity).forEach { (fingerprint, name, usedAt) ->
            records[fingerprint] = Record(fingerprint, name, usedAt)
        }
        prune()
    }

    /** v1 迁移保留“只增不删”的旧语义：半截文件不会把已认得设备整张抹掉。 */
    private fun merge(parsed: List<Triple<String, String, Long>>) {
        parsed.sortedByDescending { it.third }.take(capacity).forEach { (fingerprint, name, usedAt) ->
            records[fingerprint] = Record(fingerprint, name, usedAt)
        }
        trim()
        prune()
    }

    private fun trim() {
        while (records.size > capacity) {
            val oldest = records.values.minByOrNull { it.lastUsedAt } ?: return
            records.remove(oldest.fingerprint)
            if (activeSession == oldest.fingerprint) activeSession = null
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
        const val SNAPSHOT_HEADER = "# AirMirror TrustRegistry v2"
        const val DEFAULT_CAPACITY = 32
        const val DEFAULT_SUPPRESS = 10 * 60_000L
        const val DEFAULT_AUTO_ALLOW = 5_000L
        const val DEFAULT_TRUST_TTL = 30L * 24 * 60 * 60 * 1000
    }
}

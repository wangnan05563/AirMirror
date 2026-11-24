package io.github.wangnan05563.airmirror.perf

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地性能埋点的纯逻辑口径（REQ-009 / REQ-026）。
 *
 * 只写应用私有目录的 JSONL：不含 IP、设备名、投屏码、文件名；不上传、不建网络通道。
 * 事件名固定，字段值做长度与字符清洗，避免把用户内容带进日志。
 */
object PerfTrace {
    const val DIR = "perf"
    const val RETENTION_DAYS = 7
    const val RETENTION_MS: Long = RETENTION_DAYS.toLong() * 24 * 60 * 60 * 1000
    const val MAX_BYTES: Long = 512L * 1024
    const val MAX_FILES = 8

    object Event {
        const val SESSION_START = "session_start"
        const val CLICK = "click"
        const val AUTH = "auth"
        const val CONFIG = "config"
        const val FIRST_FRAME = "first_frame"
        const val DROP = "drop"
        const val AUDIO_OPEN = "audio_open"
        const val AUDIO_GAP = "audio_gap"
        const val AUDIO_SWITCH = "audio_switch"
        const val SESSION_END = "session_end"
    }

    private val counter = AtomicLong()

    /** 会话 id 只用于把同一场投屏的事件串起来，不含设备标识。 */
    fun sessionId(nowMs: Long = System.currentTimeMillis()): String =
        "s" + nowMs.toString(36) + "-" + counter.incrementAndGet().toString(36)

    fun line(
        nowMs: Long,
        sessionId: String,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ): String = buildString {
        append("{\"t\":").append(nowMs)
        append(",\"s\":\"").append(escape(sessionId)).append('"')
        append(",\"e\":\"").append(escape(event)).append('"')
        append(",\"v\":{")
        var first = true
        for ((key, value) in fields.entries.sortedBy { it.key }) {
            val safeKey = safeKey(key)
            if (safeKey.isEmpty()) continue
            if (!first) append(',')
            first = false
            append('"').append(safeKey).append("\":\"")
            append(escape(value?.toString().orEmpty())).append('"')
        }
        append("}}")
    }

    fun isExpired(nowMs: Long, lastModifiedMs: Long): Boolean =
        lastModifiedMs <= 0L || nowMs - lastModifiedMs > RETENTION_MS

    fun keepCount(files: Int): Int = files.coerceAtMost(MAX_FILES)

    /** 键只留字母数字与下划线，避免控制字符把 JSONL 结构打破。 */
    fun safeKey(raw: String): String {
        val cleaned = raw.lowercase(Locale.US).map { c ->
            if (c in 'a'..'z' || c in '0'..'9' || c == '_') c else '_'
        }.joinToString("").trim('_').take(24)
        if (cleaned.isEmpty()) return ""
        if (cleaned in FORBIDDEN_KEYS) return "redacted"
        return cleaned
    }

    /** 字段值只保留可读字符并限长，杜绝换行/引号/反斜杠破坏行边界。 */
    fun escape(raw: String): String {
        val value = raw.take(120)
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.toString()
    }

    fun dateName(nowMs: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowMs))

    private val FORBIDDEN_KEYS = setOf(
        "ip", "peer", "code", "credential", "device", "device_name", "filename", "path", "url",
    )
}

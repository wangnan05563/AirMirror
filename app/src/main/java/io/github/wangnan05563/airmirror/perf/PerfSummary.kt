package io.github.wangnan05563.airmirror.perf

import io.github.wangnan05563.airmirror.privacy.Redactor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 最近一次投屏会话的 SLO 摘要（REQ-009 / REQ-026）。
 *
 * 只聚合本机 `files/perf` 的 JSONL，不读网络、不读设备名。字段缺省统一记 [UNKNOWN]，
 * 界面显示「未采集」，不能把没有样本的 P95 写成 0ms 达标。
 */
data class PerfSummary(
    val sessionId: String = "",
    val startedAtMs: Long = 0L,
    val endedAtMs: Long = 0L,
    val encrypted: Boolean = false,
    val received: Long = 0L,
    val decoded: Long = 0L,
    val rendered: Long = 0L,
    val audioFrames: Long = 0L,
    val firstFrameAuthMs: Long = UNKNOWN,
    val firstFrameConfigMs: Long = UNKNOWN,
    val codec: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val audioCodec: String = "",
    val p90Ms: Long = UNKNOWN,
    val p95Ms: Long = UNKNOWN,
    val driftMs: Long = 0L,
    val dropped: Long = 0L,
    val audioGaps: Int = 0,
    val silentGaps: Int = 0,
) {
    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)

    /** 没有收到帧时返回 -1，界面不能把它显示成 0% 达标。 */
    val dropRatePercent: Double
        get() = if (received <= 0L) -1.0 else dropped * 100.0 / received

    companion object {
        const val UNKNOWN = -1L
        const val FIRST_FRAME_TARGET_MS = 3_000L
        const val P95_TARGET_MS = 200L
        const val DROP_TARGET_PERCENT = 1.0
    }
}

/** 从 PerfTrace JSONL 中找最后一场会话并汇总；坏行跳过，不影响其它行。 */
object PerfSummaryReader {

    fun latest(lines: List<String>): PerfSummary? {
        // 取最后一条有效事件的会话 id，而不是只认 SESSION_START：30 分钟会话可能已把
        // 起始行滚出最近 1500 行窗口，只认起始行会让看板误报「暂无数据」。
        var target = ""
        for (line in lines) {
            val obj = parse(line) ?: continue
            val id = obj.optString("s")
            if (id.isNotEmpty()) target = id
        }
        if (target.isEmpty()) return null

        var startedAt = 0L
        var endedAt = 0L
        var summary = PerfSummary(sessionId = target)
        for (line in lines) {
            val obj = parse(line) ?: continue
            if (obj.optString("s") != target) continue
            val t = obj.optString("t").toLongOrNull() ?: 0L
            if (t > endedAt) endedAt = t
            val event = obj.optString("e")
            val values = obj.optJSONObject("v") ?: JSONObject()
            when (event) {
                PerfTrace.Event.SESSION_START -> {
                    startedAt = t
                    summary = summary.copy(encrypted = values.optString("encrypted").toBoolean())
                }
                PerfTrace.Event.SESSION_END -> summary = summary.copy(
                    received = maxOf(summary.received, values.long("received")),
                    decoded = maxOf(summary.decoded, values.long("decoded")),
                    audioFrames = maxOf(summary.audioFrames, values.long("audio_frames")),
                )
                PerfTrace.Event.CONFIG -> summary = summary.copy(
                    codec = values.optString("codec"),
                    width = values.int("width"),
                    height = values.int("height"),
                )
                PerfTrace.Event.FIRST_FRAME -> summary = summary.copy(
                    firstFrameAuthMs = values.long("auth_ms"),
                    firstFrameConfigMs = values.long("config_ms"),
                )
                PerfTrace.Event.METRICS -> summary = summary.copy(
                    p90Ms = values.long("p90_ms"),
                    p95Ms = values.long("p95_ms"),
                    driftMs = values.long("drift_ms"),
                    received = maxOf(summary.received, values.long("received")),
                    decoded = maxOf(summary.decoded, values.long("decoded")),
                    rendered = maxOf(summary.rendered, values.long("rendered")),
                    dropped = maxOf(summary.dropped, values.long("dropped")),
                    audioFrames = maxOf(summary.audioFrames, values.long("audio_frames")),
                )
                PerfTrace.Event.DROP -> {
                    val untilKey = values.long("until_key")
                    val late = values.long("late")
                    if (untilKey >= 0L && late >= 0L) {
                        summary = summary.copy(dropped = maxOf(summary.dropped, untilKey + late))
                    }
                }
                PerfTrace.Event.AUDIO_OPEN -> summary = summary.copy(
                    audioSampleRate = values.int("sr"),
                    audioChannels = values.int("ch"),
                    audioCodec = values.optString("codec"),
                )
                PerfTrace.Event.AUDIO_GAP -> summary = summary.copy(
                    audioGaps = summary.audioGaps + 1,
                    silentGaps = summary.silentGaps + if (values.optString("reason") == "silent") 1 else 0,
                )
            }
        }
        return summary.copy(startedAtMs = startedAt, endedAtMs = endedAt)
    }

    private fun parse(line: String): JSONObject? =
        runCatching { JSONObject(line) }.getOrNull()

    /** 埋点字段全是字符串；解析失败按未知处理，不把 0 冒充成有效样本。 */
    private fun JSONObject.long(key: String): Long =
        optString(key).toLongOrNull() ?: PerfSummary.UNKNOWN

    private fun JSONObject.int(key: String): Int =
        optString(key).toIntOrNull() ?: 0
}

/** 诊断看板的文案与导出脱敏；纯 JVM 可测，不碰 Android UI。 */
object DiagnosticReport {

    fun summaryText(summary: PerfSummary?): String {
        if (summary == null) {
            return "最近一次会话：暂无数据\n投屏一次后，这里会显示首帧、P95、丢帧和音频指标。"
        }
        val firstFrame = verdict(
            summary.firstFrameAuthMs,
            PerfSummary.FIRST_FRAME_TARGET_MS,
            "≤3s",
        )
        val latency = verdict(summary.p95Ms, PerfSummary.P95_TARGET_MS, "≤200ms")
        val dropRate = summary.dropRatePercent
        val drop = when {
            dropRate < 0.0 -> "未采集"
            dropRate <= PerfSummary.DROP_TARGET_PERCENT -> "达标"
            else -> "未达标"
        }
        val duration = if (summary.startedAtMs <= 0L || summary.endedAtMs <= 0L) {
            "时长未采集"
        } else {
            durationText(summary.durationMs)
        }
        val codec = when {
            summary.codec.isBlank() -> "未采集"
            summary.width > 0 && summary.height > 0 -> "${summary.codec.uppercase(Locale.US)} ${summary.width}x${summary.height}"
            else -> summary.codec.uppercase(Locale.US)
        }
        val latencyText = when {
            summary.p95Ms < 0L -> "P90 未采集 · P95 未采集"
            summary.p90Ms < 0L -> "P90 未采集 · P95 ${summary.p95Ms}ms"
            else -> "P90 ${summary.p90Ms}ms · P95 ${summary.p95Ms}ms"
        }
        val dropText = if (dropRate < 0.0) {
            "丢帧未采集"
        } else {
            "丢帧 ${summary.dropped} / 收 ${summary.received}（${"%.2f".format(Locale.US, dropRate)}%）"
        }
        val audio = if (summary.audioSampleRate <= 0) {
            "音频未采集"
        } else {
            "${summary.audioSampleRate}Hz/${summary.audioChannels}ch ${summary.audioCodec.uppercase(Locale.US)}" +
                " · ${summary.audioFrames} 帧 · 中断 ${summary.audioGaps} 次（静音 ${summary.silentGaps}）"
        }
        return buildString {
            append("会话：").append(timeText(summary.startedAtMs)).append(" · ").append(duration)
            append(if (summary.encrypted) " · 已加密" else " · 明文").append('\n')
            append("首帧：").append(msText(summary.firstFrameAuthMs)).append("（授权起） · ")
            append(msText(summary.firstFrameConfigMs)).append("（配置起） · ").append(firstFrame).append('\n')
            append("延迟：").append(latencyText)
            if (summary.p95Ms >= 0L) append(" · 偏差 ").append(summary.driftMs).append("ms")
            append(" · ").append(latency).append('\n')
            append(dropText).append(" · ").append(drop).append('\n')
            append("音频：").append(audio).append('\n')
            append("画质：").append(codec).append(" · 解码 ").append(summary.decoded)
            append(" · 渲染 ").append(summary.rendered).append('\n')
            append("数据来源：本机 perf JSONL；不含画面、文件名、IP、设备名。")
        }
    }

    /** 导出前再次过 Redactor：即使未来字段误带地址/文件名，也不会原样离开设备。 */
    fun exportText(
        summary: PerfSummary?,
        model: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String = Redactor.text(
        buildString {
            append("AirMirror 诊断摘要\n")
            append("生成时间：").append(timeText(nowMs)).append('\n')
            append("设备型号：").append(model.ifBlank { "未采集" }).append('\n')
            append(summaryText(summary))
        },
    )

    fun durationText(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
    }

    private fun timeText(ms: Long): String =
        if (ms <= 0L) "未采集" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    private fun msText(ms: Long): String = if (ms < 0L) "未采集" else "${ms}ms"

    private fun verdict(value: Long, target: Long, label: String): String = when {
        value < 0L -> "未采集"
        value <= target -> "达标（$label）"
        else -> "未达标（$label）"
    }
}

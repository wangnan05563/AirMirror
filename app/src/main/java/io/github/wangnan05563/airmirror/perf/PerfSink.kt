package io.github.wangnan05563.airmirror.perf

import java.io.File

/**
 * 埋点文件的落盘与轮转（PRD 14.4「日志本地 7 天且可清空」）。
 *
 * 单文件超过 [maxBytes] 自动换下一个序号；文件数超过 [maxFiles] 或超过保留期就删最旧的。
 * 目录只由本类创建，只碰 `perf-*.jsonl`，不会误删诊断日志或用户文件。
 */
class PerfSink(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = PerfTrace.MAX_BYTES,
    private val maxFiles: Int = PerfTrace.MAX_FILES,
) {
    private var current: File? = null
    private var currentDay: String = ""

    @Synchronized
    fun write(line: String): Boolean {
        if (!dir.exists() && !dir.mkdirs()) return false
        val now = clock()
        val day = PerfTrace.dateName(now)
        val file = selectFile(day, line.length + 1) ?: return false
        return runCatching {
            file.appendText(line + "\n", Charsets.UTF_8)
            prune(now)
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun purge(nowMs: Long = clock()): Int {
        val files = list()
        var removed = 0
        for (file in files) {
            if (PerfTrace.isExpired(nowMs, file.lastModified()) && file.delete()) removed++
        }
        val remaining = list()
        if (remaining.size > maxFiles) {
            for (file in remaining.take(remaining.size - maxFiles)) {
                if (file.delete()) removed++
            }
        }
        if (current?.exists() != true) current = null
        return removed
    }

    fun list(): List<File> {
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("perf-") && it.name.endsWith(".jsonl") }
            ?: return emptyList()
        return files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
    }

    private fun selectFile(day: String, bytes: Int): File? {
        val existing = current
        if (existing != null && currentDay == day && existing.exists() && existing.length() + bytes <= maxBytes) {
            return existing
        }
        currentDay = day
        var index = 0
        while (true) {
            val name = if (index == 0) "perf-$day.jsonl" else "perf-$day-$index.jsonl"
            val file = File(dir, name)
            if (!file.exists()) {
                current = file
                return file
            }
            if (file.length() + bytes <= maxBytes) {
                current = file
                return file
            }
            index++
            if (index > 999) return null
        }
    }

    private fun prune(now: Long) {
        val files = list()
        for (file in files) {
            if (PerfTrace.isExpired(now, file.lastModified()) && file.delete()) Unit
        }
        val remaining = list()
        if (remaining.size > maxFiles) {
            remaining.take(remaining.size - maxFiles).forEach { it.delete() }
        }
    }
}

package io.github.wangnan05563.airmirror.perf

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 埋点入口：应用私有的 `files/perf`，同步追加，失败只记一条 Logcat。
 *
 * 不申请存储权限、不写公共目录、不做任何网络调用；会话结束由调用方负责 [end]。
 */
class PerfLog private constructor(context: Context) {
    private val sink = PerfSink(File(context.applicationContext.filesDir, PerfTrace.DIR))
    private val sessionId = PerfTrace.sessionId()

    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        mark(name, sessionId, fields)
    }

    /** 供单次投屏会话用同一 session 串起事件；空 id 直接丢弃，避免写脏行。 */
    fun mark(name: String, sessionId: String, fields: Map<String, Any?> = emptyMap()) {
        if (sessionId.isEmpty()) return
        val line = PerfTrace.line(System.currentTimeMillis(), sessionId, name, fields)
        if (!sink.write(line)) Log.w(TAG, "perf write skipped")
    }

    fun clear(): Int = sink.purge(System.currentTimeMillis())

    fun files(): List<File> = sink.list()

    /**
     * 诊断看板读最近一段 JSONL；只取文件尾部 [limit] 行，避免把 8×512KB 全读进内存。
     * 解析失败的行由 [PerfSummaryReader] 跳过，不在这里中断读取。
     */
    fun lines(limit: Int = 1500): List<String> {
        val max = limit.coerceAtLeast(0)
        val out = ArrayList<String>(max)
        for (file in sink.list().takeLast(4)) {
            val rows = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            val tail = rows.takeLast((max - out.size).coerceAtLeast(0))
            out.addAll(tail)
            if (out.size >= max) break
        }
        return out
    }

    companion object {
        private const val TAG = "PerfLog"

        @Volatile
        private var instance: PerfLog? = null

        fun get(context: Context): PerfLog =
            instance ?: synchronized(this) {
                instance ?: PerfLog(context.applicationContext).also { instance = it }
            }

        /** 诊断页「清空缓存」用：删掉 7 天前的过期文件与超量文件。 */
        fun clear(context: Context): Int = get(context).clear()
    }
}

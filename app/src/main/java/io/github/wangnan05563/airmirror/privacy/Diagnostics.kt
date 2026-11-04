package io.github.wangnan05563.airmirror.privacy

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃诊断的落盘层（PRD 8.9 / 14.4 / FS-11）：默认关闭、仅本机、7 天过期、可一键清除。
 *
 * 口径边界：PRD FS-11 写的是「诊断上传」，本片**不实现任何上传通道**——项目承诺局域网零外传、
 * 无账号，也没有可用的收集端；因此只落本机文件，开关关掉就停止写入并清空。
 * 「关闭后无外发请求」这条验收在本实现下天然成立（从来没有外发请求）。
 */
class Diagnostics(context: Context) {

    private val app = context.applicationContext ?: context
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val folder: File get() = File(app.filesDir, DiagnosticPolicy.DIR)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, DiagnosticPolicy.DEFAULT_ENABLED)

    /** 关闭时顺手清空：不能出现「开关关了、本地还留着旧崩溃记录」这种半吊子状态。 */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) clear()
    }

    /**
     * 接管未捕获异常。进程内只装一次：Activity 与开机自启的服务都会调，重复安装会把
     * handler 串成链，一次崩溃写多份文件。原 handler 保留并继续传递，不改变系统崩溃行为。
     */
    fun install() {
        if (installed) return
        synchronized(Diagnostics::class.java) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { record(error) }
                previous?.uncaughtException(thread, error)
            }
            installed = true
        }
    }

    /** 返回是否真的写了文件；默认关闭时直接 false，连目录都不建。 */
    fun record(error: Throwable, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!DiagnosticPolicy.shouldRecord(enabled())) return false
        val dir = folder
        if (!dir.exists() && !dir.mkdirs()) return false
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val body = DiagnosticPolicy.render(nowMs, Build.MODEL, stack)
        return runCatching {
            File(dir, "crash-$nowMs.txt").writeText(body)
            purge(nowMs)
            true
        }.getOrDefault(false)
    }

    /** 7 天过期清理：每次写入顺带跑一次，也可由界面进入设置时触发。 */
    fun purge(nowMs: Long = System.currentTimeMillis()): Int {
        val files = folder.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            if (file.isFile && DiagnosticPolicy.isExpired(nowMs, file.lastModified()) && file.delete()) removed++
        }
        return removed
    }

    /** 只清诊断文件；返回删除条数，供界面回显「已清除 N 条」。 */
    fun clear(): Int {
        val files = folder.listFiles() ?: return 0
        var removed = 0
        for (file in files) if (file.isFile && file.delete()) removed++
        return removed
    }

    fun count(): Int = folder.listFiles()?.count { it.isFile } ?: 0

    /**
     * 设置页「清除本地日志与缓存」：诊断文件 + cacheDir 下的临时文件。
     * 保护名单里的身份/授权数据一律不碰（PRD FS-11「清空后不影响已授权设备」）。
     */
    fun clearCaches(): Int {
        var removed = clear()
        for (child in app.cacheDir.listFiles().orEmpty()) {
            if (!DiagnosticPolicy.mayDelete(child.name)) continue
            val gone = if (child.isDirectory) child.deleteRecursively() else child.delete()
            if (gone) removed++
        }
        return removed
    }

    private companion object {
        const val PREFS = "airmirror-diagnostics"
        const val KEY_ENABLED = "enabled"

        @Volatile
        var installed = false
    }
}

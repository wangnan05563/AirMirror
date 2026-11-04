package io.github.wangnan05563.airmirror.privacy

/**
 * 崩溃诊断的纯逻辑口径（PRD 8.9「崩溃日志默认关闭且可清除」、14.4「日志本地 7 天且可清空」、
 * FS-11「诊断上传默认关闭，开启后仅上报崩溃堆栈与设备型号」）。
 *
 * 抽成不依赖 Android 的一层，是为了让「默认关 / 7 天 / 只写型号与堆栈 / 不碰授权数据」
 * 这些判据能在 JVM 单测里被钉死，而不是靠真机上点一遍开关。
 */
object DiagnosticPolicy {

    /** 默认关闭：没经过用户显式开启，一次都不落盘。 */
    const val DEFAULT_ENABLED = false

    const val RETENTION_DAYS = 7
    const val RETENTION_MS: Long = RETENTION_DAYS.toLong() * 24 * 60 * 60 * 1000

    /** 诊断文件只在应用私有 filesDir 下，卸载由系统一并清除（Manifest allowBackup=false）。 */
    const val DIR = "diagnostics"

    fun shouldRecord(enabled: Boolean): Boolean = enabled

    /** 恰好 7 天仍保留，超过才清；lastModified<=0（读不到时间）按过期处理，宁可删也不长留。 */
    fun isExpired(nowMs: Long, lastModifiedMs: Long): Boolean =
        lastModifiedMs <= 0L || nowMs - lastModifiedMs > RETENTION_MS

    /**
     * 一条诊断记录只允许有：时间戳、设备型号、崩溃堆栈。
     * 三者都过脱敏——堆栈里的片源地址/文件名同样属于「不保存」范围。
     */
    fun render(nowMs: Long, model: String, stack: String): String = buildString {
        append("capturedAt=").append(nowMs).append('\n')
        append("model=").append(Redactor.text(model)).append('\n')
        append(Redactor.text(stack))
    }

    /**
     * 清缓存的保护名单：这些名字对应设备身份、可信设备列表、诊断开关本身与 SharedPreferences 目录，
     * 清掉就会「清缓存把已授权设备也清没了」（PRD FS-11 恢复列明确禁止）。
     */
    val PROTECTED_NAMES = setOf(
        "airmirror-pairing",
        "airmirror-dlna",
        "airmirror-diagnostics",
        "shared_prefs",
    )

    fun mayDelete(name: String): Boolean = name !in PROTECTED_NAMES
}

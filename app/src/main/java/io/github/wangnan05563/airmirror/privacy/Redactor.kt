package io.github.wangnan05563.airmirror.privacy

/**
 * 日志脱敏（PRD FS-11 参数「日志不含画面、文件名、URL」、14.4「不保存画面、文件名、URL」、
 * 8.9「数据留存：不保存画面、文件名、URL」）。
 *
 * 这是「进日志之前的最后一道兜底」，不是替代调用方的克制：片名整段仍不该被拼进日志，
 * 这里负责挡住异常 message、第三方 DIDL 元数据、片源地址这些意外带入的用户内容。
 * 纯 JVM 实现（不引用 android.*），便于单测在 JVM 上锁死口径。
 */
object Redactor {

    const val REDACTED = "[已省略]"
    const val URI_PLACEHOLDER = "[地址]"
    const val FILE_PLACEHOLDER = "[文件名]"

    private val MEDIA_EXT =
        "mp4|mkv|avi|mov|m4v|3gp|ts|m3u8|webm|flv|wmv|mpg|mpeg|mp3|aac|flac|wav|ogg|m4a|wma|" +
            "jpg|jpeg|png|gif|bmp|webp|srt|ass|vtt|ssa|apk|zip|pdf|txt"

    /** 带 scheme 的地址整段替换：片源路径、query、追踪参数一次性消失，不做「只抹参数」的半成品。 */
    private val URI = Regex("""(?i)\b(?:https?|rtsp|rtsps|rtmp|file|content|ftp)://[^\s"'<>\\]+""")

    /**
     * 无 scheme 但明显是片源的路径（Unix / Windows / Android 存储路径）：
     * 只在结尾带媒体扩展名时命中，不会误伤 `H.265/H.264`、`gop=1s` 这类诊断文本。
     */
    private val MEDIA_PATH = Regex("""(?i)(?:[a-z]:)?[\\/][^\s"'<>]*?\.(?:$MEDIA_EXT)(?:\?[^\s"'<>]*)?""")

    /** 裸文件名：中文片名、英文片名都算；前缀保留（分隔符/路径符不属于敏感内容）。 */
    private val FILE_NAME = Regex(
        """(?i)(^|[\s"'<>=:(,/\\])([^\s/\\"'<>=:(,]{1,120}\.(?:$MEDIA_EXT))(?=$|[\s"'<>=:),;])""",
    )

    /** 脱离地址的裸 query / 令牌（例如只打了 `?token=xxx` 或日志里的 `sid=...`）。 */
    private val SECRET_QUERY = Regex(
        """(?i)\b(utm_[a-z]+|token|access_token|refresh_token|session|sid|sig|signature|auth|key|apikey|pwd|password)=([^&\s"'<>]+)""",
    )

    /** 走日志前统一过一遍；返回脱敏后的文本。 */
    fun text(raw: String): String {
        if (raw.isEmpty()) return raw
        var out = URI.replace(raw, URI_PLACEHOLDER)
        out = MEDIA_PATH.replace(out, URI_PLACEHOLDER)
        out = FILE_NAME.replace(out) { match ->
            match.value.replace(match.groupValues[2], FILE_PLACEHOLDER)
        }
        out = SECRET_QUERY.replace(out) { match -> match.groupValues[1] + "=" + REDACTED }
        return out
    }

    /** 异常 message 常自带片源地址（MediaPlayer / OkHttp 都这么干），单独给个入口。 */
    fun throwable(error: Throwable?): String = text(error?.message.orEmpty())

    /**
     * 片名不需要「脱敏」——它整个就是用户内容，猜不出哪些字算敏感。
     * 日志里要么完全不写，要么用固定占位；UI 上展示片名走原始值，不经过这里。
     */
    fun title(raw: String?): String = if (raw.isNullOrBlank()) "" else "[片名]"

    /** 供诊断写入与守卫测试使用：脱敏前后不同即说明原文含敏感内容。 */
    fun containsSensitive(raw: String): Boolean = text(raw) != raw
}

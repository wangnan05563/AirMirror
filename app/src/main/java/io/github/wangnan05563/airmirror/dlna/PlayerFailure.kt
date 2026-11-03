package io.github.wangnan05563.airmirror.dlna

import io.github.wangnan05563.airmirror.error.ErrorNotice

/**
 * 把 MediaPlayer 的 (what, extra) 归到 [ErrorNotice.Cause]。
 *
 * 只做纯映射：这些数字常量在 API 19 上有的没有定义、有的语义换过，
 * 依赖 `android.media.MediaPlayer` 会把这段逻辑拖进 instrumented test，
 * 所以按官方 MediaError 表把值抄成常量，单测直接跑 JVM。
 */
object PlayerFailure {

    /** onError 的 what：通用错误，细节全在 extra 里 */
    const val WHAT_UNKNOWN = 1

    /** onError 的 what：media server 进程死了，与片源无关 */
    const val WHAT_SERVER_DIED = 100

    const val EXTRA_SERVER_DIED = -1000
    const val EXTRA_TIMED_OUT = -110
    const val EXTRA_IO = -1004
    const val EXTRA_AUTH_VIOLATION = -1003
    const val EXTRA_MALFORMED = -1007
    const val EXTRA_UNSUPPORTED = -1010

    /** 完全没有细节可依据时的哨兵值：NuPlayer 在很多「拿不到元数据」的场合就只给这个 */
    const val EXTRA_NONE = Int.MIN_VALUE

    /**
     * 取不到元数据时该说「放不了这个格式」还是「地址失效」？
     * 前者给了可执行的替代路径（换片源/改镜像），后者会让人去查网络。
     * 无细节的 `EXTRA_NONE` 走前者，因为本次取证里它出现的现场
     * 是「来源服务器不支持分段请求，尾部 moov 拿不到」——那是片源侧的可达性/格式问题，重推同一条地址不会变好。
     */
    fun causeFor(what: Int, extra: Int): ErrorNotice.Cause = when {
        what == WHAT_SERVER_DIED || extra == EXTRA_SERVER_DIED -> ErrorNotice.Cause.DECODE_UNSUPPORTED
        extra == EXTRA_IO || extra == EXTRA_TIMED_OUT -> ErrorNotice.Cause.SOURCE_NOT_FOUND
        extra == EXTRA_AUTH_VIOLATION -> ErrorNotice.Cause.SOURCE_FORBIDDEN
        else -> ErrorNotice.Cause.SOURCE_FORMAT
    }

    /** 界面与日志共用一行文案 */
    fun textFor(what: Int, extra: Int): String = ErrorNotice.textFor(causeFor(what, extra))
}

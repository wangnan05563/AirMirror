package io.github.wangnan05563.airmirror.sender

import io.github.wangnan05563.airmirror.error.ErrorNotice

/**
 * FS-10 重连节奏：三次尝试铺在 30 秒保留窗口内（0s / 10s / 25s），
 * 连续失败即收口；跑到 30 秒仍未续上按超时给结论，不无限转圈。
 */
object RecoverySchedule {
    const val WINDOW_MS = 30_000L
    const val ATTEMPTS = 3

    private val delaysMs = longArrayOf(0L, 10_000L, 25_000L)

    fun delayFor(attempt: Int): Long = delaysMs[attempt]

    /** 收口原因：窗口内用完三次是「重连失败」，拖满 30 秒是「超时」。 */
    fun exhaustedCause(elapsedMs: Long): ErrorNotice.Cause =
        if (elapsedMs >= WINDOW_MS) ErrorNotice.Cause.RECOVERY_TIMEOUT else ErrorNotice.Cause.RECONNECT_EXHAUSTED
}
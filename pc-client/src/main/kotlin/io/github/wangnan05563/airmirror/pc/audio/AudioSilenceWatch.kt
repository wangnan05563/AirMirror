package io.github.wangnan05563.airmirror.pc.audio

/**
 * 系统声音静音看门狗（REQ-003 / PRD v2.0「DRM/独占模式无法捕获时明确提示」）。
 *
 * WASAPI 只能告诉我们「这个采集包里没有可播的声音」，无法直接判定对方是 DRM 保护、
 * 独占模式还是用户单纯暂停了播放。因此这里只负责在**连续静音**达到阈值时给一次提示，
 * 文案必须写成条件句，不能把「静音」冒充成「已检测到 DRM」。
 *
 * 纯计时逻辑，不碰 AudioTrack/COM，便于在 JVM 上验证：
 * - 恢复任意一个有声包就清零，下一次连续静音重新计时；
 * - 每次连续静音只提示一次，避免 10 秒后每 100ms 刷一条；
 * - [reset] 用于开关声音或重连，旧会话的静音不能带进新会话。
 */
class AudioSilenceWatch(private val hintAfterMs: Long = HINT_AFTER_MS) {

    private var silentSinceMs = NO_SILENCE

    private var hinted = false

    /** 收到有声包：取消本次静音计时，并允许未来再次提示。 */
    fun onSound() {
        silentSinceMs = NO_SILENCE
        hinted = false
    }

    /**
     * 收到一个静音包。返回 true 表示「本次连续静音刚达到提示阈值」，
     * 调用方应只发一次提示；后续静音包返回 false。
     */
    fun onSilent(nowMs: Long): Boolean {
        if (silentSinceMs == NO_SILENCE) silentSinceMs = nowMs
        if (hinted || nowMs - silentSinceMs < hintAfterMs) return false
        hinted = true
        return true
    }

    /** 静音持续了多久（毫秒）；没有在静音中返回 0。 */
    fun silentForMs(nowMs: Long): Long =
        if (silentSinceMs == NO_SILENCE) 0L else (nowMs - silentSinceMs).coerceAtLeast(0L)

    fun reset() {
        silentSinceMs = NO_SILENCE
        hinted = false
    }

    companion object {
        private const val NO_SILENCE = Long.MIN_VALUE
        /**
         * 10 秒：短于这个窗口的正常停顿（换歌、切场景）不提示；
         * 真碰到 DRM/独占导致整段无声时，10 秒也足够让用户意识到并看到说明。
         */
        const val HINT_AFTER_MS = 10_000L
    }
}

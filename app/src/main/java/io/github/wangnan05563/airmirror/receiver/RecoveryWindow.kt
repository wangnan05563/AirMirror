package io.github.wangnan05563.airmirror.receiver

/**
 * FS-10 保留窗口：连接意外中断后为同一台发送端保留会话 30 秒。
 *
 * 窗口内只接受该设备带恢复标记的重新握手；其他设备一律拒绝，避免断线一瞬被抢占。
 * 过期即失效，调用方按「电视已重启 / 超时」收口，不做无限等待。
 */
class RecoveryWindow(
    private val windowMillis: Long = DEFAULT_WINDOW_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Holder(val deviceId: String, val deviceName: String, val deadline: Long)

    private var holder: Holder? = null

    @Synchronized
    fun open(deviceId: String, deviceName: String): Holder {
        val entry = Holder(deviceId, deviceName, now() + windowMillis)
        holder = entry
        return entry
    }

    /** 当前仍有效的保留；过期即清理并返回 null。 */
    @Synchronized
    fun active(): Holder? {
        val entry = peek() ?: return null
        if (now() >= entry.deadline) {
            holder = null
            return null
        }
        return entry
    }

    /** 只看不清：等待线程用它区分「被续会/主动关闭」与「到点过期」。 */
    @Synchronized
    fun peek(): Holder? = holder

    /** 到点才清空并返回 true；已被续会或关闭时返回 false。 */
    @Synchronized
    fun expire(deviceId: String): Boolean {
        val entry = holder ?: return false
        if (entry.deviceId != deviceId || now() < entry.deadline) return false
        holder = null
        return true
    }

    @Synchronized
    fun matches(deviceId: String): Boolean = active()?.deviceId == deviceId

    @Synchronized
    fun close() {
        holder = null
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 30_000L
    }
}
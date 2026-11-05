package io.github.wangnan05563.airmirror.crossnet

/**
 * 跨网失败原因与用户可见文案。
 *
 * Q-4 已裁「不自建中继」：所以这里没有任何「开启中继」的提示，
 * 打洞失败一律给「同一网络 / 手机热点」这两条用户当下就能执行的替代路径（PRD FS-04 异常表）。
 */
object CrossNetPrompts {
    const val SAME_SUBNET = "两端在同一网段，已直接局域网连接"
    const val MATCH_TIMEOUT = "网络较慢，请重试"
    const val RETRYING = "网络较慢，正在重试一次"
    const val PUNCH_FAILED = "网络限制较严，可改用同一网络或手机热点后重试"
    const val SYMMETRIC_NAT = "双方网络限制都较严，可改用同一网络或手机热点后重试"
    const val SIGNALING_DOWN = "服务暂时不可用，可改用同一网络投屏"
    const val NO_INTERNET = "当前网络无法跨网投屏，请连接同一网络"
    const val FIREWALL = "当前网络可能限制了投屏服务，建议切换到手机热点"
}

enum class FailureReason(val code: Int, val prompt: String) {
    SIGNALING_UNREACHABLE(1, CrossNetPrompts.SIGNALING_DOWN),
    MATCH_TIMEOUT(2, CrossNetPrompts.MATCH_TIMEOUT),
    PUNCH_BLOCKED(3, CrossNetPrompts.PUNCH_FAILED),
    SYMMETRIC_NAT(4, CrossNetPrompts.SYMMETRIC_NAT),
    NO_INTERNET(5, CrossNetPrompts.NO_INTERNET);

    companion object {
        fun fromCode(code: Int): FailureReason? = entries.firstOrNull { it.code == code }
    }
}

enum class CrossNetState {
    /** 刚输入码，尚未判定同网段 */
    ENTERING,

    /** 跨网：等待信令把两端配到一起 */
    MATCHING,

    /** 已交换候选，正在 UDP 打洞 */
    PUNCHING,

    /** 点对点通道已建立，等待大屏授权 */
    DIRECT,

    /** 授权通过，媒体开始传输 */
    STREAMING,

    /** 有明确原因与替代路径 */
    FAILED,

    /** 用户主动结束 */
    CLOSED,
}

/**
 * FS-04 的连接状态机（纯逻辑、时钟外置）。
 *
 * 刻意没有 RELAY 状态：Q-4 裁定不自建中继，打洞失败只能落到 FAILED 并给出替代路径。
 * 时间由调用方通过 `now` 传入，单测因此不需要 sleep；接收端 30 秒巡检之类的真实计时器在接线层负责驱动 [onTick]。
 */
class CrossNetSession(
    private val matchTimeoutMs: Long = 15_000,
    private val punchTimeoutMs: Long = 15_000,
    private val signalingFailureLimit: Int = 3,
) {
    var state: CrossNetState = CrossNetState.ENTERING
        private set
    var prompt: String = ""
        private set
    var failure: FailureReason? = null
        private set

    /** 当前状态的截止时刻；仅 MATCHING / PUNCHING 有值，0 表示没有计时器 */
    var deadlineAt: Long = 0
        private set

    var retried: Boolean = false
        private set

    private var signalingFailures = 0

    /** 返回是否还需要后台继续轮询计时器。 */
    val timed: Boolean get() = state == CrossNetState.MATCHING || state == CrossNetState.PUNCHING

    fun onCodeAccepted(now: Long, sameSubnet: Boolean): CrossNetState {
        if (state != CrossNetState.ENTERING) return state
        if (sameSubnet) {
            // 同网段直接转局域网直连：这一步不产生任何信令请求，满足 FS-04「同网不触云」
            state = CrossNetState.DIRECT
            prompt = CrossNetPrompts.SAME_SUBNET
            deadlineAt = 0
            return state
        }
        state = CrossNetState.MATCHING
        deadlineAt = now + matchTimeoutMs
        prompt = ""
        return state
    }

    /** 信令匹配成功（接收端已分配会话 id）。 */
    fun onMatched(now: Long): CrossNetState {
        if (state != CrossNetState.MATCHING) return state
        state = CrossNetState.PUNCHING
        deadlineAt = now + punchTimeoutMs
        prompt = ""
        return state
    }

    /** 候选交换完成；语义上等价于可以开始打洞，保留单独入口是为了让接线层按信令消息驱动。 */
    fun onCandidatesReady(now: Long): CrossNetState = onMatched(now)

    fun onDirectEstablished(): CrossNetState {
        if (state != CrossNetState.PUNCHING) return state
        state = CrossNetState.DIRECT
        prompt = ""
        deadlineAt = 0
        return state
    }

    fun onAuthorized(): CrossNetState {
        if (state != CrossNetState.DIRECT) return state
        state = CrossNetState.STREAMING
        deadlineAt = 0
        return state
    }

    /**
     * 打洞失败：按 PRD「通道失败回到第 3 步重试一次」先回 MATCHING，重试仍失败才落 FAILED。
     * 两端都对称 NAT 时 [reason] 传 SYMMETRIC_NAT，文案与普通打洞失败不同。
     */
    fun onPunchFailed(now: Long, reason: FailureReason = FailureReason.PUNCH_BLOCKED): CrossNetState {
        if (state != CrossNetState.PUNCHING) return state
        if (!retried) {
            retried = true
            state = CrossNetState.MATCHING
            deadlineAt = now + matchTimeoutMs
            prompt = CrossNetPrompts.RETRYING
            return state
        }
        fail(reason)
        return state
    }

    /** 信令请求连续失败；未到上限前保持现状，让调用方继续重试。 */
    fun onSignalingFailure(): CrossNetState {
        if (state != CrossNetState.MATCHING && state != CrossNetState.PUNCHING) return state
        signalingFailures++
        if (signalingFailures >= signalingFailureLimit) fail(FailureReason.SIGNALING_UNREACHABLE)
        return state
    }

    fun onNetworkLost(): CrossNetState {
        if (state == CrossNetState.CLOSED || state == CrossNetState.FAILED) return state
        fail(FailureReason.NO_INTERNET)
        return state
    }

    fun onTick(now: Long): CrossNetState {
        when (state) {
            CrossNetState.MATCHING -> if (now >= deadlineAt) {
                if (!retried) {
                    retried = true
                    deadlineAt = now + matchTimeoutMs
                    prompt = CrossNetPrompts.RETRYING
                } else {
                    fail(FailureReason.MATCH_TIMEOUT)
                }
            }
            CrossNetState.PUNCHING -> if (now >= deadlineAt) onPunchFailed(now)
            else -> Unit
        }
        return state
    }

    fun close(): CrossNetState {
        state = CrossNetState.CLOSED
        deadlineAt = 0
        return state
    }

    private fun fail(reason: FailureReason) {
        state = CrossNetState.FAILED
        failure = reason
        prompt = reason.prompt
        deadlineAt = 0
    }
}

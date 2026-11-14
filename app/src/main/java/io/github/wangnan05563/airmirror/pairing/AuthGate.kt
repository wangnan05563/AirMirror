package io.github.wangnan05563.airmirror.pairing

/**
 * 私有通道上的授权门禁（PRD 14.4「未授权设备无法直接上屏」+ FR-03）。
 *
 * 发送端在 HELLO 之后必须交一个凭据：6 位投屏码、扫码拿到的一次性令牌，或者什么都不交
 * （此时按设备指纹查可信列表，查不到就走大屏确认）。判定本身不含任何 IO。
 */
class AuthGate(
    private val trust: TrustRegistry,
    private val code: CastCode,
) {
    enum class Refused { INVALID_CODE, ALREADY_USED, TOO_MANY_ATTEMPTS, NOT_TRUSTED, SESSION_BUSY }

    sealed interface Verdict {
        data class Authorized(val deviceName: String, val permanent: Boolean) : Verdict

        /** 需要大屏确认；[autoAllowAt] 为自动放行上限时刻。空凭据会话（未递有效凭据）一律不带自动允许，必须由大屏显式确认（T04496）。 */
        data class PendingPrompt(val deviceId: String, val deviceName: String, val autoAllowAt: Long) : Verdict

        /**
         * 凭据有效但已有别的设备在投屏（PRD FS-09 步骤 4）：可向当前发送端申请接管，
         * 而不是直接拒绝。未通过凭据的设备只会拿到 [Rejected] + [Refused.SESSION_BUSY]。
         */
        data class TakeoverRequired(
            val deviceId: String,
            val deviceName: String,
            val permanent: Boolean,
        ) : Verdict

        data class Rejected(val reason: Refused, val secondsLeft: Int) : Verdict
    }

    /** 用户在屏幕上点了「允许」或 5 秒无操作后由界面代为调用。 */
    fun confirm(deviceId: String, deviceName: String, permanent: Boolean = true): Verdict.Authorized {
        trust.allow(deviceId, deviceName, permanent)
        trust.acquireSession(deviceId)
        return Verdict.Authorized(deviceName, permanent)
    }

    fun decline(deviceId: String) = trust.deny(deviceId)

    /**
     * 当前发送端允许接管后，把活动会话从旧设备换到 [deviceId]。
     * 旧会话必须先释放；仍被占用时返回 null，调用方保留原会话。
     */
    fun promote(deviceId: String, deviceName: String, permanent: Boolean): Verdict.Authorized? =
        if (trust.acquireSession(deviceId)) Verdict.Authorized(deviceName, permanent) else null

    fun authorize(deviceId: String, deviceName: String, credential: String): Verdict {
        val id = deviceId.ifBlank { "unknown" }
        val cred = credential.trim()
        // 拒绝后的静默期对凭据路径同样生效：否则刚点过「拒绝」的设备换个码就能上屏
        val decision = trust.decide(id)
        if (decision.verdict == TrustRegistry.Verdict.SUPPRESSED) {
            return Verdict.Rejected(Refused.NOT_TRUSTED, 0)
        }
        if (cred.isNotEmpty()) return authorizeByCode(id, deviceName, cred)
        if (decision.verdict == TrustRegistry.Verdict.TRUSTED) return admit(id, deviceName, permanent = true)
        val held = trust.active()
        if (held != null && held != id) return Verdict.Rejected(Refused.SESSION_BUSY, 0)
        // 走到这里的会话都没有递过有效凭据（有效凭据已在上方 CastCode 分支即时放行）：
        // 按 T04496 裁决选项1，空凭据一律不自动放行，必须由大屏显式确认。5 秒/无人表态的超时
        // 由 ReceiverSession.awaitDecision 退回拒绝（decline），不再把匿名同网设备永久写入可信列表。
        return Verdict.PendingPrompt(id, deviceName, Long.MAX_VALUE)
    }

    /** 一次性凭据：6 位码或 16 位令牌，命中即放行但只记本次会话，不写可信列表。 */
    private fun authorizeByCode(id: String, deviceName: String, cred: String): Verdict {
        val outcome = when {
            cred.matches(SIX_DIGITS) -> code.submit(cred)
            cred.matches(HEX_TOKEN) -> code.submitToken(cred)
            // 既不是 6 位码也不是 16 位令牌：属输入不合法，按「没输完」处理，不烧校验次数
            else -> return Verdict.Rejected(Refused.INVALID_CODE, 0)
        }
        return when (outcome) {
            CastCode.Outcome.Expired -> Verdict.Rejected(Refused.ALREADY_USED, 0)
            is CastCode.Outcome.Locked -> Verdict.Rejected(Refused.TOO_MANY_ATTEMPTS, outcome.secondsLeft)
            is CastCode.Outcome.Rejected -> if (outcome.remainingAttempts == 0) {
                Verdict.Rejected(Refused.TOO_MANY_ATTEMPTS, 60)
            } else {
                Verdict.Rejected(Refused.INVALID_CODE, 0)
            }
            CastCode.Outcome.Accepted -> {
                // 扫码/投屏码属一次性授权：不入可信列表，除非用户随后选「记住这台设备」
                trust.allow(id, deviceName, permanent = false)
                admit(id, deviceName, permanent = false)
            }
        }
    }

    /** 占会话成功就授权，被别的设备占着就要求用户在大屏上确认接管。 */
    private fun admit(id: String, deviceName: String, permanent: Boolean): Verdict =
        if (trust.acquireSession(id)) {
            Verdict.Authorized(deviceName, permanent)
        } else {
            Verdict.TakeoverRequired(id, deviceName, permanent)
        }

    private companion object {
        val SIX_DIGITS = Regex("^\\d{6}$")
        val HEX_TOKEN = Regex("^[0-9a-f]{16}$")
    }
}

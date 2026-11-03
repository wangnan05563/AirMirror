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

        /** 需要大屏确认；[autoAllowAt] 为界面上限时刻，到点按默认策略放行。 */
        data class PendingPrompt(val deviceId: String, val deviceName: String, val autoAllowAt: Long) : Verdict

        data class Rejected(val reason: Refused, val secondsLeft: Int) : Verdict
    }

    /** 用户在屏幕上点了「允许」或 5 秒无操作后由界面代为调用。 */
    fun confirm(deviceId: String, deviceName: String, permanent: Boolean = true): Verdict.Authorized {
        trust.allow(deviceId, deviceName, permanent)
        trust.acquireSession(deviceId)
        return Verdict.Authorized(deviceName, permanent)
    }

    fun decline(deviceId: String) = trust.deny(deviceId)

    fun authorize(deviceId: String, deviceName: String, credential: String): Verdict {
        val id = deviceId.ifBlank { "unknown" }
        val cred = credential.trim()
        // 拒绝后的静默期对凭据路径同样生效：否则刚点过「拒绝」的设备换个码就能上屏
        val decision = trust.decide(id)
        if (decision.verdict == TrustRegistry.Verdict.SUPPRESSED) {
            return Verdict.Rejected(Refused.NOT_TRUSTED, 0)
        }
        if (cred.isNotEmpty()) {
            val outcome = when {
                cred.matches(SIX_DIGITS) -> code.submit(cred)
                cred.matches(HEX_TOKEN) -> code.submitToken(cred)
                // 既不是 6 位码也不是 16 位令牌：属输入不合法，按「没输完」处理，不烧校验次数
                else -> return Verdict.Rejected(Refused.INVALID_CODE, 0)
            }
            when (outcome) {
                CastCode.Outcome.Expired -> return Verdict.Rejected(Refused.ALREADY_USED, 0)
                is CastCode.Outcome.Locked -> return Verdict.Rejected(Refused.TOO_MANY_ATTEMPTS, outcome.secondsLeft)
                is CastCode.Outcome.Rejected -> return if (outcome.remainingAttempts == 0) {
                    Verdict.Rejected(Refused.TOO_MANY_ATTEMPTS, 60)
                } else {
                    Verdict.Rejected(Refused.INVALID_CODE, 0)
                }
                CastCode.Outcome.Accepted -> {
                    // 扫码/投屏码属一次性授权：不入可信列表，除非用户随后选「记住这台设备」
                    trust.allow(id, deviceName, permanent = false)
                    return if (trust.acquireSession(id)) {
                        Verdict.Authorized(deviceName, permanent = false)
                    } else {
                        Verdict.Rejected(Refused.SESSION_BUSY, 0)
                    }
                }
            }
        }
        if (decision.verdict == TrustRegistry.Verdict.TRUSTED) {
            return if (trust.acquireSession(id)) {
                Verdict.Authorized(deviceName, permanent = true)
            } else {
                Verdict.Rejected(Refused.SESSION_BUSY, 0)
            }
        }
        val held = trust.active()
        if (held != null && held != id) return Verdict.Rejected(Refused.SESSION_BUSY, 0)
        return Verdict.PendingPrompt(id, deviceName, decision.autoAllowAt)
    }

    private companion object {
        val SIX_DIGITS = Regex("^\\d{6}$")
        val HEX_TOKEN = Regex("^[0-9a-f]{16}$")
    }
}

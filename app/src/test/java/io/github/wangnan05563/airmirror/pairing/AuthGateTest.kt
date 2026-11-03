package io.github.wangnan05563.airmirror.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权门禁：把「投屏码 / 扫码令牌 / 可信列表」三条路径与「同一时刻单会话」「拒绝后静默」
 * 的约束收在一个可测的判定层里（PRD FR-03 + 14.4「未授权设备无法直接上屏」）。
 */
class AuthGateTest {

    private var clock = 1_000_000L
    private lateinit var trust: TrustRegistry
    private lateinit var code: CastCode

    private fun gate(autoAllowMillis: Long = 5_000L, vararg codes: Int): AuthGate {
        var index = 0
        code = CastCode(
            now = { clock },
            random = {
                val value = if (index < codes.size) codes[index] else codes[codes.size - 1]
                index++
                value
            },
        )
        trust = TrustRegistry(now = { clock }, autoAllowMillis = autoAllowMillis)
        return AuthGate(trust, code)
    }

    @Test
    fun anUnknownDeviceWithoutCredentialWaitsForBigScreenConfirmation() {
        val subject = gate(codes = intArrayOf(123_456))
        assertEquals(
            AuthGate.Verdict.PendingPrompt("phone-a", "小明的手机", clock + 5_000L),
            subject.authorize("phone-a", "小明的手机", ""),
        )
    }

    @Test
    fun turningAutoAllowOffKeepsThePromptPendingUntilSomeoneAnswers() {
        val subject = gate(autoAllowMillis = 0L, codes = intArrayOf(123_456))
        val verdict = subject.authorize("phone-a", "n", "") as AuthGate.Verdict.PendingPrompt
        assertEquals(Long.MAX_VALUE, verdict.autoAllowAt)
    }

    @Test
    fun aTrustedDeviceConnectsWithoutAnyPrompt() {
        val subject = gate(codes = intArrayOf(123_456))
        subject.confirm("phone-a", "小明的手机")
        assertEquals(
            AuthGate.Verdict.Authorized("小明的手机", permanent = true),
            subject.authorize("phone-a", "小明的手机", ""),
        )
    }

    @Test
    fun aCorrectCastCodeGrantsOneTimeAccessWithoutJoiningTheTrustedList() {
        val subject = gate(codes = intArrayOf(123_456))
        assertEquals(
            AuthGate.Verdict.Authorized("小明的手机", permanent = false),
            subject.authorize("phone-b", "小明的手机", "123456"),
        )
        assertTrue(trust.trusted().isEmpty())
        assertEquals("phone-b", trust.active())
    }

    @Test
    fun aScannedTokenIsAcceptedOnceAndThenReadsAsAlreadyUsed() {
        val subject = gate(codes = intArrayOf(123_456))
        val token = code.token()
        assertEquals(
            AuthGate.Verdict.Authorized("平板", permanent = false),
            subject.authorize("pad", "平板", token),
        )
        assertEquals(
            AuthGate.Verdict.Rejected(AuthGate.Refused.ALREADY_USED, 0),
            subject.authorize("pad", "平板", token),
        )
    }

    @Test
    fun wrongCodesBurnAttemptsAndTheFifthLocksTheCode() {
        val subject = gate(codes = intArrayOf(123_456))
        repeat(4) {
            val rejected = subject.authorize("phone-d", "n", "000000") as AuthGate.Verdict.Rejected
            assertEquals(AuthGate.Refused.INVALID_CODE, rejected.reason)
        }
        val fifth = subject.authorize("phone-d", "n", "000000") as AuthGate.Verdict.Rejected
        assertEquals(AuthGate.Refused.TOO_MANY_ATTEMPTS, fifth.reason)
        assertEquals(60, fifth.secondsLeft)
        // 锁定期内连正确的码也不放行
        assertEquals(
            AuthGate.Refused.TOO_MANY_ATTEMPTS,
            (subject.authorize("phone-d", "n", "123456") as AuthGate.Verdict.Rejected).reason,
        )
        clock += 60_000
        assertTrue(subject.authorize("phone-d", "n", "") is AuthGate.Verdict.PendingPrompt)
    }

    @Test
    fun malformedInputNeverBurnsAnAttempt() {
        val subject = gate(codes = intArrayOf(123_456))
        repeat(6) {
            assertEquals(
                AuthGate.Refused.INVALID_CODE,
                (subject.authorize("phone-e", "n", "42") as AuthGate.Verdict.Rejected).reason,
            )
        }
        assertTrue(subject.authorize("phone-e", "n", "123456") is AuthGate.Verdict.Authorized)
    }

    @Test
    fun aWellFormedButWrongTokenDoesBurnAttempts() {
        // 伪造得像扫码得来的令牌要计入限流，否则刷二维码可以无限试
        val subject = gate(codes = intArrayOf(123_456))
        val fake = "0123456789abcdef"
        repeat(4) {
            assertEquals(
                AuthGate.Refused.INVALID_CODE,
                (subject.authorize("pad", "p", fake) as AuthGate.Verdict.Rejected).reason,
            )
        }
        assertEquals(
            AuthGate.Refused.TOO_MANY_ATTEMPTS,
            (subject.authorize("pad", "p", fake) as AuthGate.Verdict.Rejected).reason,
        )
    }

    @Test
    fun onlyOneDeviceMayCastAtATimeAndTheSecondValidOneMayAskToTakeOver() {
        val subject = gate(codes = intArrayOf(123_456, 654_321))
        assertTrue(subject.authorize("phone-a", "a", "123456") is AuthGate.Verdict.Authorized)
        val verdict = subject.authorize("phone-b", "b", "654321") as AuthGate.Verdict.TakeoverRequired
        assertEquals("phone-b", verdict.deviceId)
        assertEquals("b", verdict.deviceName)
        assertEquals(false, verdict.permanent)
        // 原会话未释放前不允许强插
        assertEquals("phone-a", trust.active())
        assertEquals(null, subject.promote("phone-b", "b", permanent = false))
        trust.releaseSession("phone-a")
        assertEquals(
            AuthGate.Verdict.Authorized("b", permanent = false),
            subject.promote("phone-b", "b", permanent = false),
        )
        assertEquals("phone-b", trust.active())
    }

    @Test
    fun aTrustedDeviceCanRequestTakeoverToo() {
        val subject = gate(codes = intArrayOf(123_456))
        subject.confirm("phone-a", "a")
        subject.confirm("phone-b", "b")
        trust.acquireSession("phone-a")
        val verdict = subject.authorize("phone-b", "b", "") as AuthGate.Verdict.TakeoverRequired
        assertEquals(true, verdict.permanent)
        trust.releaseSession("phone-a")
        assertEquals(
            AuthGate.Verdict.Authorized("b", permanent = true),
            subject.promote("phone-b", "b", permanent = true),
        )
    }

    @Test
    fun anUnauthenticatedDeviceStillCannotAskToTakeOver() {
        val subject = gate(codes = intArrayOf(123_456))
        assertTrue(subject.authorize("phone-a", "a", "123456") is AuthGate.Verdict.Authorized)
        // 既没有有效凭据也不在可信列表：只能被拒，不能向当前发送端申请接管
        assertEquals(
            AuthGate.Refused.SESSION_BUSY,
            (subject.authorize("phone-c", "c", "") as AuthGate.Verdict.Rejected).reason,
        )
    }

    @Test
    fun aDeclinedDeviceIsIgnoredForTenMinutesInsteadOfPromptingAgain() {
        val subject = gate(codes = intArrayOf(123_456))
        subject.decline("phone-f")
        assertEquals(
            AuthGate.Refused.NOT_TRUSTED,
            (subject.authorize("phone-f", "f", "") as AuthGate.Verdict.Rejected).reason,
        )
        clock += 10 * 60_000L - 1
        assertEquals(
            AuthGate.Refused.NOT_TRUSTED,
            (subject.authorize("phone-f", "f", "") as AuthGate.Verdict.Rejected).reason,
        )
        clock += 1
        assertTrue(subject.authorize("phone-f", "f", "") is AuthGate.Verdict.PendingPrompt)
    }

    @Test
    fun aDeclinedDeviceCannotBypassTheSilenceByGuessingTheCode() {
        val subject = gate(codes = intArrayOf(123_456))
        subject.decline("phone-g")
        assertEquals(
            AuthGate.Refused.NOT_TRUSTED,
            (subject.authorize("phone-g", "g", "123456") as AuthGate.Verdict.Rejected).reason,
        )
    }

    @Test
    fun aDeviceWithoutAnIdIsStillTrackedSoThePromptCanAddressIt() {
        val subject = gate(codes = intArrayOf(123_456))
        val verdict = subject.authorize("", "陌生设备", "  ") as AuthGate.Verdict.PendingPrompt
        assertEquals("unknown", verdict.deviceId)
        assertEquals("陌生设备", verdict.deviceName)
    }
}

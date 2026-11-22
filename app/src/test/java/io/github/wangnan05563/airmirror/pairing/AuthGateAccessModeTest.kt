package io.github.wangnan05563.airmirror.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T06114：三种访问模式必须真正决定凭据路径，6 位码/扫码令牌不能绕过 PIN 或白名单。 */
class AuthGateAccessModeTest {

    private var clock = 1_000_000L
    private var mode = AccessMode.CONFIRM
    private lateinit var trust: TrustRegistry
    private lateinit var code: CastCode
    private lateinit var pin: PinCode
    private lateinit var gate: AuthGate

    private fun setup(initial: AccessMode): AuthGate {
        mode = initial
        trust = TrustRegistry(now = { clock })
        code = CastCode(now = { clock }, random = { 123_456 })
        pin = PinCode(load = { "" }, save = { }, now = { clock }, random = { 0 })
        gate = AuthGate(trust, code, pin, accessMode = { mode })
        return gate
    }

    private fun refused(verdict: AuthGate.Verdict): AuthGate.Refused =
        (verdict as AuthGate.Verdict.Rejected).reason

    @Test
    fun pinModeOnlyAcceptsFourDigitsAndNeverConsumesTheSixDigitCode() {
        val subject = setup(AccessMode.PIN)
        val six = code.code()
        assertEquals(AuthGate.Refused.PIN_REQUIRED, refused(subject.authorize("phone-a", "手机", six)))
        assertEquals(six, code.code())
        assertTrue(trust.trusted().isEmpty())

        assertEquals(
            AuthGate.Verdict.Authorized("手机", permanent = false),
            subject.authorize("phone-a", "手机", "0000"),
        )
        assertTrue(trust.trusted().isEmpty())
    }

    @Test
    fun pinModeRejectsTheTokenPathToo() {
        val subject = setup(AccessMode.PIN)
        val token = code.token()
        assertEquals(AuthGate.Refused.PIN_REQUIRED, refused(subject.authorize("phone-a", "手机", token)))
        assertEquals(token, code.token())
    }

    @Test
    fun trustedDeviceSkipsPinInPinMode() {
        val subject = setup(AccessMode.PIN)
        subject.confirm("phone-a", "手机")
        assertEquals(
            AuthGate.Verdict.Authorized("手机", permanent = true),
            subject.authorize("phone-a", "手机", ""),
        )
    }

    @Test
    fun fiveWrongPinsLockTheGateEvenForTheCorrectPin() {
        val subject = setup(AccessMode.PIN)
        repeat(4) {
            assertEquals(AuthGate.Refused.PIN_WRONG, refused(subject.authorize("phone-a", "手机", "1111", "192.168.1.42")))
        }
        assertEquals(AuthGate.Refused.PIN_LOCKED, refused(subject.authorize("phone-a", "手机", "1111", "192.168.1.42")))
        val rejected = subject.authorize("phone-a", "手机", "0000") as AuthGate.Verdict.Rejected
        assertEquals(AuthGate.Refused.PIN_LOCKED, rejected.reason)
        assertTrue(rejected.secondsLeft > 0)
        assertEquals("192.168.1.*", pin.peerMask())
    }

    @Test
    fun lockingIsLiftedOnlyByTheCountdownEnding() {
        val subject = setup(AccessMode.PIN)
        repeat(5) { subject.authorize("phone-a", "手机", "1111") }
        val rejected = subject.authorize("phone-a", "手机", "0000") as AuthGate.Verdict.Rejected
        assertEquals(AuthGate.Refused.PIN_LOCKED, rejected.reason)
        clock += 61_000L
        assertEquals(
            AuthGate.Verdict.Authorized("手机", permanent = false),
            subject.authorize("phone-a", "手机", "0000"),
        )
    }

    @Test
    fun trustedOnlyModeRejectsUnknownDevicesEvenWithAValidCodeOrToken() {
        val subject = setup(AccessMode.TRUSTED_ONLY)
        val six = code.code()
        val token = code.token()
        assertEquals(AuthGate.Refused.TRUSTED_ONLY, refused(subject.authorize("phone-a", "手机", six)))
        assertEquals(AuthGate.Refused.TRUSTED_ONLY, refused(subject.authorize("phone-a", "手机", token)))
        // 两条一次性凭据都未被消费，切回确认模式后仍可用。
        assertEquals(six, code.code())
        mode = AccessMode.CONFIRM
        assertTrue(subject.authorize("phone-a", "手机", six) is AuthGate.Verdict.Authorized)
    }

    @Test
    fun trustedOnlyModeStillAllowsTheWhitelist() {
        val subject = setup(AccessMode.TRUSTED_ONLY)
        subject.confirm("phone-a", "电视")
        assertEquals(
            AuthGate.Verdict.Authorized("电视", permanent = true),
            subject.authorize("phone-a", "电视", ""),
        )
    }

    @Test
    fun confirmModeDoesNotAcceptARequestAsAPin() {
        val subject = setup(AccessMode.CONFIRM)
        assertEquals(AuthGate.Refused.INVALID_CODE, refused(subject.authorize("phone-a", "手机", "0000")))
        assertTrue(pin.remainingAttempts() == 5)
    }
}

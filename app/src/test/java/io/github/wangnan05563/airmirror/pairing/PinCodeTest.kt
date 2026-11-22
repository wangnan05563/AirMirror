package io.github.wangnan05563.airmirror.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T06114：4 位 PIN、5 次错误锁 60 秒、锁定跨重启、来源只存掩码。 */
class PinCodeTest {

    private var clock = 1_000_000L
    private var storage = ""

    private fun pin(
        random: (Int) -> Int = { 0 },
        lockMillis: Long = 60_000L,
        maxAttempts: Int = 5,
    ): PinCode = PinCode(
        load = { storage },
        save = { storage = it },
        now = { clock },
        random = random,
        lockMillis = lockMillis,
        maxAttempts = maxAttempts,
    )

    @Test
    fun firstUseGeneratesAndPersistsAFourDigitPinThatSurvivesRestart() {
        val first = pin()
        val value = first.pin()
        assertEquals(4, value.length)
        assertTrue(value.all(Char::isDigit))
        assertTrue(storage.startsWith(PinStateCodec.HEADER))

        val restarted = pin()
        assertEquals(value, restarted.pin())
        assertEquals(PinCode.State.SHOWING, restarted.state())
    }

    @Test
    fun wrongPinFourTimesKeepsCountThenTheFifthLocksForSixtySeconds() {
        val subject = pin()
        val pinValue = subject.pin()
        val wrong = if (pinValue == "0000") "1111" else "0000"
        repeat(4) { round ->
            val outcome = subject.submit(wrong, "192.168.1.42")
            assertTrue("第 ${round + 1} 次应仍是可重试", outcome is PinCode.Outcome.Rejected)
            assertEquals(4 - round, (outcome as PinCode.Outcome.Rejected).remainingAttempts)
        }
        val locked = subject.submit(wrong, "192.168.1.42")
        assertEquals(PinCode.Outcome.Locked(60), locked)
        assertEquals(PinCode.State.LOCKED, subject.state())
        assertEquals("192.168.1.*", subject.peerMask())
        assertFalse(storage.contains("192.168.1.42"))
    }

    @Test
    fun lockedPinRejectsEvenTheCorrectValueAndSurvivesRestart() {
        val subject = pin()
        val pinValue = subject.pin()
        val wrong = if (pinValue == "0000") "1111" else "0000"
        repeat(5) { subject.submit(wrong) }
        assertEquals(PinCode.State.LOCKED, subject.state())
        assertTrue(subject.submit(pinValue) is PinCode.Outcome.Locked)

        val restarted = pin()
        assertEquals(PinCode.State.LOCKED, restarted.state())
        assertEquals(60, restarted.secondsLeft())

        clock += 61_000L
        assertEquals(PinCode.State.SHOWING, restarted.state())
        assertEquals(PinCode.Outcome.Accepted, restarted.submit(pinValue))
        assertEquals(5, restarted.remainingAttempts())
    }

    @Test
    fun wrongLengthNeverBurnsAttempts() {
        val subject = pin()
        val pinValue = subject.pin()
        assertEquals(PinCode.Outcome.Rejected(5), subject.submit("12"))
        assertEquals(PinCode.Outcome.Rejected(5), subject.submit("12345"))
        assertEquals(PinCode.Outcome.Accepted, subject.submit(pinValue))
    }

    @Test
    fun successfulPinClearsFailuresAndTheSourceMask() {
        val subject = pin()
        val pinValue = subject.pin()
        val wrong = if (pinValue == "0000") "1111" else "0000"
        repeat(3) { subject.submit(wrong, "10.0.0.9") }
        assertEquals("10.0.0.*", subject.peerMask())
        assertEquals(PinCode.Outcome.Accepted, subject.submit(pinValue))
        assertEquals(5, subject.remainingAttempts())
        assertEquals("", subject.peerMask())
    }

    @Test
    fun ownerSetPinClearsFailuresAndUnlocksImmediately() {
        val subject = pin()
        val wrong = if (subject.pin() == "0000") "1111" else "0000"
        repeat(5) { subject.submit(wrong) }
        assertEquals(PinCode.State.LOCKED, subject.state())
        assertTrue(subject.setPin("1234"))
        assertEquals("1234", subject.pin())
        assertEquals(PinCode.State.SHOWING, subject.state())
        assertEquals(5, subject.remainingAttempts())
        assertFalse(subject.setPin("12345"))
        assertFalse(subject.setPin("12a4"))
    }

    @Test
    fun corruptSnapshotRegeneratesInsteadOfTrustingReadableGarbage() {
        storage = "not-a-pin-table"
        val subject = pin(random = { 7 })
        assertEquals("0007", subject.pin())
        assertTrue(storage.startsWith(PinStateCodec.HEADER))
    }

    @Test
    fun codecRoundTripKeepsLockAndMaskAndRejectsUnknownHeaders() {
        val record = PinCode.Record("0042", 5, 1_000_060L, "192.168.1.*")
        assertEquals(record, PinStateCodec.decode(PinStateCodec.encode(record)))
        assertNull(PinStateCodec.decode("# AirMirror PIN v2\n0042\t5\t1000060\t192.168.1.*"))
        assertNull(PinStateCodec.decode(PinStateCodec.HEADER + "\n0042\t6\t1000060\t"))
        assertNull(PinStateCodec.decode(PinStateCodec.HEADER + "\n004\t5\t1000060\t"))
    }
}

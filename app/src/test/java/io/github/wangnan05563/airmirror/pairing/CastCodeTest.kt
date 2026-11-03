package io.github.wangnan05563.airmirror.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FS-03 投屏码：6 位数字、60 秒轮换、旧码 10 秒宽限、一次即失效、5 次/60 秒锁 60 秒。
 * 时钟与随机数都由测试注入，窗口边界是逐毫秒断言的，不留「大概会过期」的余地。
 */
class CastCodeTest {

    private var clock = 1_000_000L

    /** 依次吐出给定码值；取尽后一直重复最后一个，方便只关心限流的用例。 */
    private fun newCode(vararg values: Int): CastCode {
        var index = 0
        return CastCode(
            now = { clock },
            random = {
                val value = if (index < values.size) values[index] else values[values.size - 1]
                index++
                value
            },
        )
    }

    @Test
    fun codeIsSixDigitsWithLeadingZerosPreserved() {
        assertEquals("000137", CastCode.formatCode(137))
        assertEquals("000000", CastCode.formatCode(0))
        assertEquals("999999", CastCode.formatCode(999_999))
        val subject = newCode(137)
        assertEquals("000137", subject.code())
        assertEquals(6, subject.code().length)
        assertTrue(subject.code().all(Char::isDigit))
    }

    @Test
    fun codeRotatesAfterSixtySeconds() {
        val subject = newCode(111_111, 222_222)
        assertEquals("111111", subject.code())
        clock += 59_999
        assertEquals("111111", subject.code())
        clock += 1
        assertEquals("222222", subject.code())
    }

    @Test
    fun rotationKeepsCountingWhileUserTypes() {
        val subject = newCode(111_111, 222_222)
        subject.code()
        subject.submit("000001")
        assertEquals("111111", subject.code())
        clock += 60_000
        assertEquals("222222", subject.code())
    }

    @Test
    fun previousCodeStaysValidForTenSecondGrace() {
        val subject = newCode(111_111, 222_222)
        subject.code()
        clock += 60_000
        assertEquals("222222", subject.code())
        clock += 9_999
        assertEquals(CastCode.Outcome.Accepted, subject.submit("111111"))
    }

    @Test
    fun previousCodeDiesOnceGraceElapses() {
        val subject = newCode(111_111, 222_222)
        subject.code()
        clock += 60_000
        subject.code()
        clock += 10_000
        assertEquals(CastCode.Outcome.Rejected(4), subject.submit("111111"))
    }

    @Test
    fun acceptedCodeWorksOnceAndIsThenRecognisedAsStale() {
        val subject = newCode(111_111, 222_222)
        assertEquals(CastCode.Outcome.Accepted, subject.submit(subject.code()))
        val fresh = subject.code()
        assertNotEquals("111111", fresh)
        // 宽限期内再交同一个码：提示「码已用过」，而不是算一次输错
        assertEquals(CastCode.Outcome.Expired, subject.submit("111111"))
    }

    @Test
    fun fiveWrongAttemptsInsideWindowLocksForSixtySeconds() {
        val subject = newCode(111_111, 222_222)
        subject.code()
        for (attempt in 1..4) {
            assertEquals(CastCode.Outcome.Rejected(5 - attempt), subject.submit("00000$attempt"))
        }
        assertEquals(CastCode.Outcome.Locked(60), subject.submit("000005"))
        assertEquals(CastCode.State.LOCKED, subject.state())
        clock += 59_999
        assertEquals(CastCode.Outcome.Locked(1), subject.submit("222222"))
        clock += 1
        assertEquals(CastCode.State.SHOWING, subject.state())
    }

    @Test
    fun unlockIssuesAFreshCodeAndClearsTheAttemptWindow() {
        val subject = newCode(111_111, 222_222, 333_333)
        assertEquals(CastCode.Outcome.Accepted, subject.submit(subject.code()))
        assertNotEquals("111111", subject.code())
        assertEquals(CastCode.State.SHOWING, subject.state())
        // 授权成功会把失败计数清零，不会把下一次锁定提前
        for (attempt in 1..4) subject.submit("00000$attempt")
        clock += 120_000
        for (attempt in 1..4) {
            assertEquals(CastCode.Outcome.Rejected(5 - attempt), subject.submit("00000$attempt"))
        }
    }

    @Test
    fun failuresOlderThanTheWindowNoLongerCount() {
        val subject = newCode(111_111)
        subject.code()
        repeat(4) { subject.submit("000001") }
        clock += 60_000
        assertEquals(CastCode.Outcome.Rejected(4), subject.submit("000002"))
    }

    @Test
    fun malformedInputDoesNotBurnAnAttempt() {
        val subject = newCode(111_111)
        subject.code()
        repeat(5) { assertEquals(CastCode.Outcome.Rejected(5), subject.submit("123")) }
        assertEquals(CastCode.Outcome.Rejected(4), subject.submit("000000"))
    }

    @Test
    fun secondsUntilRotationCountsDownFromSixty() {
        val subject = newCode(111_111)
        subject.code()
        assertEquals(60, subject.secondsUntilRotation())
        clock += 30_500
        assertEquals(30, subject.secondsUntilRotation())
    }

    @Test
    fun qrTokenRotatesTogetherWithTheDisplayedCode() {
        val subject = newCode(111_111, 222_222)
        val first = subject.token()
        assertEquals(16, first.length)
        assertEquals("111111", subject.code())
        clock += 60_000
        assertEquals("222222", subject.code())
        val second = subject.token()
        assertNotEquals(first, second)
        // 旧令牌沿用同一条宽限期规则
        assertEquals(CastCode.Outcome.Accepted, subject.submitToken(first))
    }

    @Test
    fun scannedTokenWorksOnceAndIsThenRecognisedAsStale() {
        val subject = newCode(111_111, 222_222)
        val token = subject.token()
        assertEquals(CastCode.Outcome.Accepted, subject.submitToken(token))
        assertEquals(CastCode.Outcome.Expired, subject.submitToken(token))
        assertNotEquals("111111", subject.code())
    }

    @Test
    fun unknownTokenBurnsAnAttemptButAnEmptyOneDoesNot() {
        val subject = newCode(111_111)
        subject.code()
        assertEquals(CastCode.Outcome.Rejected(4), subject.submitToken("0000000000000000"))
        assertEquals(CastCode.Outcome.Rejected(4), subject.submitToken(""))
    }

    @Test
    fun realTokenIsSixteenHexCharacters() {
        val subject = CastCode(now = { clock })
        assertTrue(Regex("^[0-9a-f]{16}$").matches(subject.token()))
        assertTrue(Regex("^\\d{6}$").matches(subject.code()))
    }

    @Test
    fun submitWorksBeforeTheWaitingPageHasEverReadTheCode() {
        // 服务侧可能没人先取过码；校验时按当前时刻生成条目，否则正确的码会被判成输错
        val subject = newCode(333_333, 444_444)
        assertEquals(CastCode.Outcome.Accepted, subject.submit("333333"))
        assertEquals(CastCode.Outcome.Expired, subject.submit("333333"))
    }
}

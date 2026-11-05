package io.github.wangnan05563.airmirror.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FS-07 判决：三轮全空才下结论；组播没中而广播/直连命中 = 静默降级。 */
class DiscoveryLadderTest {

    private val t0 = 100_000L

    @Test
    fun `探测节奏按拍走 命中后停发`() {
        val ladder = DiscoveryLadder()
        assertEquals(0, ladder.roundsDone(t0))
        ladder.start(t0)
        assertTrue(ladder.probeDue(t0))
        ladder.markProbed(t0)
        assertFalse("一轮内不重复发", ladder.probeDue(t0 + 100))
        assertTrue(ladder.probeDue(t0 + 750))
        ladder.noteHit(DiscoveryLadder.Channel.BROADCAST)
        assertFalse("命中之后不再敲门", ladder.probeDue(t0 + 750))
    }

    @Test
    fun `组播没中而直连命中 判为已降级但不提示`() {
        val ladder = DiscoveryLadder()
        ladder.start(t0)
        ladder.noteHit(DiscoveryLadder.Channel.DIRECT)
        val verdict = ladder.verdict(t0 + 1_000, cachedCrossSubnet = false) as DiscoveryLadder.Verdict.Found
        assertTrue(verdict.downgraded)
        assertEquals(setOf(DiscoveryLadder.Channel.DIRECT), verdict.via)
    }

    @Test
    fun `组播命中不算降级`() {
        val ladder = DiscoveryLadder()
        ladder.start(t0)
        ladder.noteHit(DiscoveryLadder.Channel.MULTICAST)
        ladder.noteHit(DiscoveryLadder.Channel.BROADCAST)
        val verdict = ladder.verdict(t0, cachedCrossSubnet = false) as DiscoveryLadder.Verdict.Found
        assertFalse(verdict.downgraded)
        assertEquals(2, verdict.via.size)
    }

    @Test
    fun `一轮未走完不下搜不到的结论`() {
        val ladder = DiscoveryLadder()
        ladder.start(t0)
        val early = ladder.verdict(t0 + 1_000, cachedCrossSubnet = false) as DiscoveryLadder.Verdict.Nothing
        assertEquals(DiscoveryLadder.Empty.SEARCHING, early.reason)
        assertEquals("", early.notice)
        assertFalse(ladder.exhausted(t0 + 1_000))
    }

    @Test
    fun `三轮全空按缓存网段给可执行原因`() {
        val ladder = DiscoveryLadder()
        ladder.start(t0)
        val done = t0 + DiscoveryLadder.DEFAULT_ROUND * DiscoveryLadder.DEFAULT_ROUNDS
        assertTrue(ladder.exhausted(done))
        assertEquals(DiscoveryLadder.DEFAULT_ROUNDS, ladder.roundsDone(done))
        val isolated = ladder.verdict(done, cachedCrossSubnet = false) as DiscoveryLadder.Verdict.Nothing
        assertEquals(DiscoveryLadder.Empty.AP_ISOLATED, isolated.reason)
        assertEquals("当前网络禁止设备互访，可关闭 AP 隔离或改用扫码", isolated.notice)
        val dual = ladder.verdict(done, cachedCrossSubnet = true) as DiscoveryLadder.Verdict.Nothing
        assertEquals(DiscoveryLadder.Empty.DUAL_BAND, dual.reason)
        assertEquals("手机与电视可能不在同一频段", dual.notice)
    }
}

package io.github.wangnan05563.airmirror.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-03 首次连接授权：可信列表长期免弹窗、上限 32 台、拒绝后 10 分钟静默、
 * 同一时刻只允许一个活动会话；扫码与投屏码属一次性授权，不入列表。
 */
class TrustRegistryTest {

    private var clock = 0L
    private fun registry(autoAllowMillis: Long = 5_000L) = TrustRegistry(now = { clock }, autoAllowMillis = autoAllowMillis)

    @Test
    fun unknownDeviceMustBeConfirmedButAutoAllowsAfterFiveSeconds() {
        val subject = registry()
        val decision = subject.decide("tv-hall")
        assertEquals(TrustRegistry.Verdict.ASK, decision.verdict)
        assertEquals(5_000L, decision.autoAllowAt)
        clock = 5_000
        subject.allow("tv-hall", "小明的手机", permanent = true)
        assertEquals(TrustRegistry.Verdict.TRUSTED, subject.decide("tv-hall").verdict)
    }

    @Test
    fun autoAllowCanBeSwitchedOffInSettings() {
        val subject = registry(autoAllowMillis = 0L)
        assertEquals(Long.MAX_VALUE, subject.decide("tv-hall").autoAllowAt)
    }

    @Test
    fun deniedDeviceGetsTenMinutesOfSilenceThenIsAskedAgain() {
        val subject = registry()
        subject.deny("tv-hall")
        clock = 10 * 60_000L - 1
        assertEquals(TrustRegistry.Verdict.SUPPRESSED, subject.decide("tv-hall").verdict)
        clock = 10 * 60_000L
        assertEquals(TrustRegistry.Verdict.ASK, subject.decide("tv-hall").verdict)
    }

    @Test
    fun oneTimeGrantFromQrOrCastCodeDoesNotEnterTheTrustedList() {
        val subject = registry()
        assertEquals(TrustRegistry.Verdict.ONE_TIME, subject.decide("guest-pad", requiresExplicitGrant = false).verdict)
        subject.allow("guest-pad", "访客平板", permanent = false)
        assertTrue(subject.trusted().isEmpty())
        // 下次再来仍按首次连接处理
        assertEquals(TrustRegistry.Verdict.ASK, subject.decide("guest-pad").verdict)
    }

    @Test
    fun onlyOneActiveSessionAtATime() {
        val subject = registry()
        assertTrue(subject.acquireSession("phone-a"))
        assertFalse(subject.acquireSession("phone-b"))
        assertTrue(subject.acquireSession("phone-a"))
        assertEquals("phone-a", subject.active())
        subject.releaseSession("phone-a")
        assertNull(subject.active())
        assertTrue(subject.acquireSession("phone-b"))
    }

    @Test
    fun revokingOrDenyingFreesTheSessionItHeld() {
        val subject = registry()
        subject.allow("phone-a", "A", permanent = true)
        subject.acquireSession("phone-a")
        subject.revoke("phone-a")
        assertNull(subject.active())
        assertTrue(subject.trusted().isEmpty())
        subject.allow("phone-b", "B", permanent = true)
        subject.acquireSession("phone-b")
        subject.deny("phone-b")
        assertNull(subject.active())
    }

    @Test
    fun trustedListCapsAt32DevicesAndEvictsTheLeastRecentlyUsed() {
        val subject = registry(capacityOf = 3)
        subject.allow("a", "A", permanent = true)
        clock += 1
        subject.allow("b", "B", permanent = true)
        clock += 1
        subject.allow("c", "C", permanent = true)
        clock += 1
        subject.allow("d", "D", permanent = true)
        assertEquals(listOf("d", "c", "b"), subject.trusted().map { it.fingerprint })
        assertEquals(TrustRegistry.Verdict.ASK, subject.decide("a").verdict)
    }

    @Test
    fun usingATrustedDeviceKeepsItOffTheEvictionEnd() {
        val subject = registry(capacityOf = 2)
        subject.allow("a", "A", permanent = true)
        clock += 1
        subject.allow("b", "B", permanent = true)
        clock += 1
        assertTrue(subject.acquireSession("a"))
        clock += 1
        subject.allow("c", "C", permanent = true)
        assertEquals(listOf("c", "a"), subject.trusted().map { it.fingerprint })
    }

    @Test
    fun defaultCapacityIsThirtyTwo() {
        val subject = registry()
        repeat(35) { index -> subject.allow("d$index", "D$index", permanent = true); clock += 1 }
        assertEquals(32, subject.trusted().size)
        assertFalse(subject.trusted().any { it.fingerprint == "d0" })
    }

    @Test
    fun pruneDropsExpiredSuppressions() {
        val subject = registry()
        subject.deny("phone-a")
        clock = 10 * 60_000L
        subject.prune()
        assertEquals(TrustRegistry.Verdict.ASK, subject.decide("phone-a").verdict)
    }

    private fun registry(capacityOf: Int, autoAllowMillis: Long = 5_000L) =
        TrustRegistry(now = { clock }, capacity = capacityOf, autoAllowMillis = autoAllowMillis)
}

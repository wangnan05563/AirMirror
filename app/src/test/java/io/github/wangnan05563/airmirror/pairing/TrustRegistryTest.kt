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
    fun `海量拒绝不会让静默表无限增长`() {
        val subject = registry()
        // 每次拒绝都按设备指纹记一条静默：伪造随机指纹的人能让它随连接次数一直涨
        repeat(200) { i ->
            clock = i.toLong()
            subject.deny("forged-$i")
        }
        // 容量满后按「最早过期」淘汰，最新那条的静默不受影响
        assertEquals(TrustRegistry.Verdict.ASK, subject.decide("forged-0").verdict)
        assertEquals(TrustRegistry.Verdict.SUPPRESSED, subject.decide("forged-199").verdict)
    }

    @Test
    fun oneTimeGrantFromQrOrCastCodeDoesNotEnterTheTrustedList() {
        val subject = registry()
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

    @Test
    fun snapshotAndRestoreSurviveARestart() {
        val subject = registry()
        subject.allow("phone-a", "小明的手机", permanent = true)
        clock += 5
        subject.allow("tv-hall", "客厅 TV", permanent = true)
        val restored = registry()
        restored.restore(subject.snapshot())
        assertEquals(TrustRegistry.Verdict.TRUSTED, restored.decide("phone-a").verdict)
        assertEquals(listOf("tv-hall", "phone-a"), restored.trusted().map { it.fingerprint })
        assertEquals(listOf("客厅 TV", "小明的手机"), restored.trusted().map { it.name })
        // 落盘只带列表，拒绝后的静默期与活动会话不跨重启
        assertNull(restored.active())
    }

    @Test
    fun restoreMergesInsteadOfClearingEntriesItDoesNotRecognise() {
        val subject = registry()
        subject.allow("phone-a", "小明的手机", permanent = true)
        clock += 1
        subject.allow("phone-b", "客厅 TV", permanent = true)
        // 落盘文件只剩半截时，恢复不该把内存里认得的设备整张抹掉
        subject.restore("phone-c\t访客平板\t${Long.MAX_VALUE}\nphone-a\t改名后的手机\t0\n")
        assertEquals(listOf("phone-c", "phone-b", "phone-a"), subject.trusted().map { it.fingerprint })
        assertEquals("改名后的手机", subject.trusted().last().name)
    }

    @Test
    fun namesWithTabsAndNewlinesStayOnASingleLine() {
        val subject = registry()
        subject.allow("p", "坏\t名字\n第二行 = & %", permanent = true)
        val saved = subject.snapshot()
        assertEquals(1, saved.split('\n').size)
        val restored = registry()
        restored.restore(saved)
        assertEquals("坏\t名字\n第二行 = & %", restored.trusted().single().name)
    }

    @Test
    fun restoreIgnoresDamagedLinesAndCapsAtCapacity() {
        val subject = registry(capacityOf = 2)
        subject.restore(
            """
            |a	A	1
            |这行没有分好的字段
            |b	B	不是数字
            |	空标识	9
            |c	C	3
            |d	D	4
            """.trimMargin()
        )
        assertEquals(listOf("d", "c"), subject.trusted().map { it.fingerprint })
    }

    private fun registry(capacityOf: Int, autoAllowMillis: Long = 5_000L) =
        TrustRegistry(now = { clock }, capacity = capacityOf, autoAllowMillis = autoAllowMillis)
}

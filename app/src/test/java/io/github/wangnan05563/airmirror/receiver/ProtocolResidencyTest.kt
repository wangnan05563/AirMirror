package io.github.wangnan05563.airmirror.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议运行时的闲置释放判据（PRD 1152：A/B 常驻，C 级按需加载、闲置 5 分钟释放）。
 *
 * 这里锁的是「什么时候能放」与「忙的时候绝不放」；监听入口该不该停属于设计口径（不该），
 * 写在这份判据的类注释里，由 DlnaServer.releaseIdle 只释放播放器来保证。
 */
class ProtocolResidencyTest {

    private var clock = 1_000_000L
    private val subject = ProtocolResidency(now = { clock })
    private val dlna = ProtocolModule.DLNA

    @Test
    fun aModuleIsEligibleOnlyAfterFiveIdleMinutes() {
        subject.activity(dlna)
        clock += ProtocolResidency.IDLE_RELEASE_MILLIS - 1
        assertEquals(emptyList<ProtocolModule>(), subject.releaseCandidates())
        clock += 1
        assertEquals(listOf(dlna), subject.releaseCandidates())
    }

    @Test
    fun anyRequestPushesTheIdleWindowBack() {
        // 控制点还在请求（描述文件/SOAP/订阅）就不算闲置：这正是 DlnaServer.onActivity 的口径
        subject.activity(dlna)
        clock += ProtocolResidency.IDLE_RELEASE_MILLIS - 1
        subject.activity(dlna)
        clock += ProtocolResidency.IDLE_RELEASE_MILLIS - 1
        assertEquals(emptyList<ProtocolModule>(), subject.releaseCandidates())
        clock += 1
        assertEquals(listOf(dlna), subject.releaseCandidates())
    }

    @Test
    fun aPlayingModuleIsNeverReleased() {
        subject.activity(dlna)
        subject.hold(dlna, true)
        // 播一部两小时的片子，中间没有新的「活动」也要一直钉住
        clock += 2 * 60 * 60_000L
        assertEquals(emptyList<ProtocolModule>(), subject.releaseCandidates())
        subject.hold(dlna, false)
        clock += ProtocolResidency.IDLE_RELEASE_MILLIS - 1
        assertEquals(emptyList<ProtocolModule>(), subject.releaseCandidates())
        clock += 1
        assertEquals("播完再闲置满 5 分钟才放", listOf(dlna), subject.releaseCandidates())
    }

    @Test
    fun aReleasedModuleIsTrackedAgainOnTheNextDemand() {
        subject.activity(dlna)
        clock += ProtocolResidency.IDLE_RELEASE_MILLIS
        assertTrue(subject.releaseCandidates().contains(dlna))
        subject.markReleased(dlna)
        assertFalse(subject.isTracked(dlna))
        // 下一次真正用到（SetAVTransportURI）再记账，计时从这一刻重新开始
        subject.activity(dlna)
        assertTrue(subject.isTracked(dlna))
        clock += ProtocolResidency.IDLE_RELEASE_MILLIS - 1
        assertEquals(emptyList<ProtocolModule>(), subject.releaseCandidates())
    }

    @Test
    fun nothingIsEligibleBeforeAnyModuleWasLoaded() {
        clock += 10 * 60_000L
        assertEquals(emptyList<ProtocolModule>(), subject.releaseCandidates())
        assertFalse(subject.isTracked(ProtocolModule.PRIVATE_CHANNEL))
        assertEquals(0L, subject.idleMillis(ProtocolModule.PRIVATE_CHANNEL))
    }
}

package io.github.wangnan05563.airmirror.crossnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossNetSessionTest {

    private val t0 = 1_000L

    @Test
    fun `同网段直接直连 不进入信令状态 也不留计时器`() {
        val session = CrossNetSession()
        assertEquals(CrossNetState.DIRECT, session.onCodeAccepted(t0, sameSubnet = true))
        assertEquals(CrossNetPrompts.SAME_SUBNET, session.prompt)
        assertEquals(0L, session.deadlineAt)
        assertFalse(session.timed)
        session.onTick(t0 + 60_000)
        assertEquals("同网段不能因为计时器被拖进失败", CrossNetState.DIRECT, session.state)
    }

    @Test
    fun `跨网进入匹配 15 秒后超时重试一次再失败`() {
        val session = CrossNetSession(matchTimeoutMs = 15_000)
        session.onCodeAccepted(t0, sameSubnet = false)
        assertEquals(CrossNetState.MATCHING, session.state)
        assertEquals(t0 + 15_000, session.deadlineAt)
        session.onTick(t0 + 14_999)
        assertEquals(CrossNetState.MATCHING, session.state)
        session.onTick(t0 + 15_000)
        assertEquals(CrossNetState.MATCHING, session.state)
        assertTrue(session.retried)
        assertEquals(CrossNetPrompts.RETRYING, session.prompt)
        session.onTick(t0 + 30_000)
        assertEquals(CrossNetState.FAILED, session.state)
        assertEquals(FailureReason.MATCH_TIMEOUT, session.failure)
        assertEquals(CrossNetPrompts.MATCH_TIMEOUT, session.prompt)
    }

    @Test
    fun `匹配成功后进入打洞 打洞超时先回匹配重试`() {
        val session = CrossNetSession()
        session.onCodeAccepted(t0, sameSubnet = false)
        session.onMatched(t0 + 100)
        assertEquals(CrossNetState.PUNCHING, session.state)
        assertEquals(t0 + 15_100, session.deadlineAt)
        session.onDirectEstablished()
        assertEquals(CrossNetState.DIRECT, session.state)
        session.onAuthorized()
        assertEquals(CrossNetState.STREAMING, session.state)
    }

    @Test
    fun `打洞失败一次回匹配 第二次失败落 FAILED 并给替代路径`() {
        val session = CrossNetSession()
        session.onCodeAccepted(t0, sameSubnet = false)
        session.onMatched(t0 + 100)
        val first = session.onPunchFailed(t0 + 200)
        assertEquals(CrossNetState.MATCHING, first)
        assertTrue(session.retried)
        assertEquals(CrossNetPrompts.RETRYING, session.prompt)
        session.onMatched(t0 + 300)
        session.onPunchFailed(t0 + 400, FailureReason.SYMMETRIC_NAT)
        assertEquals(CrossNetState.FAILED, session.state)
        assertEquals(FailureReason.SYMMETRIC_NAT, session.failure)
        assertEquals(CrossNetPrompts.SYMMETRIC_NAT, session.prompt)
        assertFalse("Q-4 不自建中继，失败态不应还有转圈计时", session.timed)
    }

    @Test
    fun `信令连续失败 3 次才判服务不可用`() {
        val session = CrossNetSession(signalingFailureLimit = 3)
        session.onCodeAccepted(t0, sameSubnet = false)
        session.onSignalingFailure()
        session.onSignalingFailure()
        assertEquals(CrossNetState.MATCHING, session.state)
        session.onSignalingFailure()
        assertEquals(CrossNetState.FAILED, session.state)
        assertEquals(FailureReason.SIGNALING_UNREACHABLE, session.failure)
        assertEquals(CrossNetPrompts.SIGNALING_DOWN, session.prompt)
    }

    @Test
    fun `网络丢失立即失败 关闭后不再被事件拉回`() {
        val session = CrossNetSession()
        session.onCodeAccepted(t0, sameSubnet = false)
        session.onNetworkLost()
        assertEquals(CrossNetState.FAILED, session.state)
        assertEquals(FailureReason.NO_INTERNET, session.failure)
        session.close()
        assertEquals(CrossNetState.CLOSED, session.state)
        session.onMatched(t0 + 100)
        assertEquals(CrossNetState.CLOSED, session.state)
        assertEquals(0L, session.deadlineAt)
    }
}

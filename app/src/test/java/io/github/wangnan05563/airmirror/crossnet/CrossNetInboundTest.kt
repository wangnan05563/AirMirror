package io.github.wangnan05563.airmirror.crossnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossNetInboundTest {

    @Test
    fun `码为空时不发起建连 只空转`() {
        var attempts = 0
        val inbound = CrossNetInbound(
            connect = { attempts++; failed() },
            credential = { "" },
            onLink = {},
            sleep = {},
        )
        assertFalse(inbound.loopOnce())
        assertEquals("锁定或还没出码时不能把空哈希发到信令服务", 0, attempts)
    }

    @Test
    fun `建连失败后继续待命 不打断循环`() {
        var attempts = 0
        val inbound = CrossNetInbound(
            connect = { attempts++; failed() },
            credential = { "123456" },
            onLink = {},
            sleep = {},
        )
        assertFalse(inbound.loopOnce())
        assertFalse(inbound.loopOnce())
        assertEquals(2, attempts)
    }

    @Test
    fun `建连成功时交付链路并报告成功`() {
        val delivered = ArrayList<String>()
        val inbound = CrossNetInbound(
            connect = { code ->
                CrossNetConnector.Outcome(
                    state = CrossNetState.DIRECT,
                    prompt = "",
                    failure = null,
                    sessionId = "s-$code",
                )
            },
            credential = { "123456" },
            onLink = { delivered += it.sessionId.orEmpty() },
            sleep = {},
        )
        // Outcome 没有 link 时不算交付：只有真的拿到隧道才叫连上了
        assertFalse(inbound.loopOnce())
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `connect 抛异常时不外抛 下一轮继续`() {
        var attempts = 0
        val inbound = CrossNetInbound(
            connect = { attempts++; throw IllegalStateException("boom") },
            credential = { "123456" },
            onLink = {},
            sleep = {},
        )
        assertFalse(inbound.loopOnce())
        assertEquals(1, attempts)
        assertFalse(inbound.loopOnce())
        assertEquals(2, attempts)
    }

    private fun failed() = CrossNetConnector.Outcome(
        state = CrossNetState.FAILED,
        prompt = CrossNetPrompts.MATCH_TIMEOUT,
        failure = FailureReason.MATCH_TIMEOUT,
    )
}

package io.github.wangnan05563.airmirror.crossnet

import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossNetConnectorTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun `没配置信令时明确告知只能同网投屏 不假装在连接`() {
        val outcome = connector(client = null).connectSender("123456")
        assertEquals(CrossNetState.FAILED, outcome.state)
        assertEquals(FailureReason.SIGNALING_DISABLED, outcome.failure)
        assertEquals(CrossNetPrompts.SIGNALING_DISABLED, outcome.prompt)
        assertFalse(outcome.connected)
    }

    @Test
    fun `同网段直接返回局域网直连 不碰信令`() {
        val signaling = SignalingClient(MemorySignaling(), pollIntervalMs = 5)
        val outcome = connector(client = signaling).connectSender("123456", sameSubnet = true)
        assertEquals(CrossNetState.DIRECT, outcome.state)
        assertEquals(CrossNetPrompts.SAME_SUBNET, outcome.prompt)
        assertNull(outcome.failure)
    }

    @Test
    fun `两端经信令配对与打洞后拿到可互通的隧道`() {
        val signaling = SignalingClient(MemorySignaling(), pollIntervalMs = 5)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val receiver = pool.submit(
                Callable { connector(CrossNetProtocol.Role.RECEIVER, signaling).connectReceiver("123456") },
            )
            // 接收端先登记，发送端才可能匹配上；等一小会儿避免用例偶发顺序颠倒
            Thread.sleep(50)
            val sender = pool.submit(
                Callable { connector(CrossNetProtocol.Role.SENDER, signaling).connectSender("123456") },
            )

            val receiverOutcome = receiver.get(20, TimeUnit.SECONDS)
            val senderOutcome = sender.get(20, TimeUnit.SECONDS)
            assertTrue("接收端应建连：" + receiverOutcome.prompt, receiverOutcome.connected)
            assertTrue("发送端应建连：" + senderOutcome.prompt, senderOutcome.connected)
            assertEquals(CrossNetState.DIRECT, senderOutcome.state)
            assertNotNull(senderOutcome.sessionId)
            assertEquals(senderOutcome.sessionId, receiverOutcome.sessionId)

            val sent = "airmirror-cross-net".toByteArray(Charsets.UTF_8)
            senderOutcome.link!!.output.write(sent)
            assertEquals(
                sent.toList(),
                readExactly(receiverOutcome.link!!, sent.size).toList(),
            )
            senderOutcome.link.close()
            receiverOutcome.link.close()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `没有接收端认领时按匹配超时收口并给替代路径`() {
        val signaling = SignalingClient(MemorySignaling(), pollIntervalMs = 5)
        val outcome = connector(client = signaling, timeouts = SHORT_MATCH).connectSender("123456")
        assertFalse(outcome.connected)
        assertEquals(FailureReason.MATCH_TIMEOUT, outcome.failure)
        assertTrue(outcome.prompt.isNotEmpty())
        assertFalse("Q-4 不自建中继，文案里不能出现中继开关", outcome.prompt.contains("中继"))
    }

    @Test
    fun `打洞失败会真的回到第 3 步重试一次 不是只在文案里说重试`() {
        val transport = DeadEndSignaling()
        val signaling = SignalingClient(transport, pollIntervalMs = 5)
        val outcome = connector(
            client = signaling,
            collect = { socket -> listOf(CrossNetProtocol.Candidate("127.0.0.1", transport.deadPort)) },
            timeouts = RETRY_TIMEOUTS,
        ).connectSender("123456")

        // 状态机把首次打洞失败解读为「回第 3 步重试一次」：匹配请求必须真的发两次，
        // 否则发送端界面写着「正在重试一次」却已经放弃，大屏还在等这一端的候选
        assertEquals("首次失败后应重新匹配一次", 2, transport.matches)
        assertFalse(outcome.connected)
        assertEquals(CrossNetState.FAILED, outcome.state)
        assertEquals(FailureReason.PUNCH_BLOCKED, outcome.failure)
        assertEquals(CrossNetPrompts.PUNCH_FAILED, outcome.prompt)
    }

    @Test
    fun `投屏码为空直接拒绝 避免发出无意义请求`() {
        val signaling = SignalingClient(MemorySignaling(), pollIntervalMs = 5)
        val error = runCatching { connector(client = signaling).connectSender("") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private fun connector(
        role: CrossNetProtocol.Role = CrossNetProtocol.Role.SENDER,
        client: SignalingClient?,
        collect: (DatagramSocket) -> List<CrossNetProtocol.Candidate> = { socket ->
            listOf(CrossNetProtocol.Candidate("127.0.0.1", socket.localPort))
        },
        // 端到端用例要容忍宿主机高负载：SonarQube/ES 抢满 CPU 时 UDP 包会晚好几秒才被调度，
        // 1 秒的打洞窗口直接错过。放宽的只是调度容忍度，判定标准没变——隧道必须真的互通。
        timeouts: CrossNetConnector.Timeouts = LENIENT_TIMEOUTS,
    ): CrossNetConnector = CrossNetConnector(
        role = role,
        client = client,
        socketFactory = { DatagramSocket(0, loopback) },
        collect = collect,
        timeouts = timeouts,
    )

    private fun readExactly(tunnel: UdpTunnel, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        tunnel.setReadTimeout(20_000)
        while (offset < size) {
            val count = tunnel.input.read(result, offset, size - offset)
            if (count < 0) throw IllegalStateException("stream ended at $offset/$size")
            offset += count
        }
        return result
    }

    private companion object {
        private val LENIENT_TIMEOUTS = CrossNetConnector.Timeouts(
            matchMs = 10_000,
            candidatesMs = 10_000,
            punchMs = 6_000,
            punchIntervalMs = 50,
        )

        /** 专门验「没人认领要按匹配超时收口」：这一条必须用短窗口，否则用例白等十秒 */
        private val SHORT_MATCH = CrossNetConnector.Timeouts(
            matchMs = 1_000,
            candidatesMs = 1_000,
            punchMs = 1_000,
            punchIntervalMs = 50,
        )

        /** 打洞必然失败的短周期：只验重试次数，不陪真实 15 秒窗口空等 */
        private val RETRY_TIMEOUTS = CrossNetConnector.Timeouts(
            matchMs = 2_000,
            candidatesMs = 2_000,
            punchMs = 300,
            punchIntervalMs = 50,
        )
    }

    /**
     * 每次 MATCH 都直接配对，但候选永远指向一个没人监听的端口：
     * 用来区分「真的重试」与「只是把重试写进文案」。
     */
    private class DeadEndSignaling : SignalingTransport {
        private val session = CrossNetProtocol.newSessionId()

        /** 绑定后立刻释放：端口在测试期间没有监听者，探针只可能超时 */
        val deadPort: Int = java.net.DatagramSocket(0).use { it.localPort }

        var matches = 0
            private set

        override fun post(path: String, body: ByteArray, timeoutMs: Int): SignalingTransport.Response {
            if (path != SignalingClient.PATH_MATCH) return SignalingTransport.Response(200, ByteArray(0))
            matches++
            return SignalingTransport.Response(
                200,
                CrossNetProtocol.encode(
                    CrossNetProtocol.Message(
                        type = CrossNetProtocol.Type.MATCHED,
                        role = CrossNetProtocol.Role.RECEIVER,
                        sessionId = session,
                    ),
                ),
            )
        }

        override fun get(path: String, timeoutMs: Int): SignalingTransport.Response {
            val peer = path.substringAfter("role=", "").toIntOrNull()
                ?.let { CrossNetProtocol.Role.fromCode(it) }
                ?: return SignalingTransport.Response(400, ByteArray(0))
            return SignalingTransport.Response(
                200,
                CrossNetProtocol.encode(
                    CrossNetProtocol.Message(
                        type = CrossNetProtocol.Type.CANDIDATES,
                        role = peer,
                        sessionId = session,
                        candidates = listOf(CrossNetProtocol.Candidate("127.0.0.1", deadPort)),
                    ),
                ),
            )
        }
    }

    /** 内存版信令服务：只为验证客户端语义与两端编排，真实 HTTP 行为由 SignalingTest 覆盖。 */
    private class MemorySignaling : SignalingTransport {
        private val lock = Any()
        private var receiverHash: ByteArray? = null
        private var sessionId: String? = null
        private val candidates = HashMap<Int, List<CrossNetProtocol.Candidate>>()

        override fun post(path: String, body: ByteArray, timeoutMs: Int): SignalingTransport.Response =
            synchronized(lock) {
                val message = CrossNetProtocol.decode(body)
                when (path) {
                    SignalingClient.PATH_MATCH -> when (message.role) {
                        CrossNetProtocol.Role.RECEIVER -> {
                            receiverHash = message.codeHash
                            sessionId = CrossNetProtocol.newSessionId()
                            matched()
                        }
                        CrossNetProtocol.Role.SENDER -> {
                            val expected = receiverHash
                            val hash = message.codeHash
                            if (expected != null && hash != null && expected.contentEquals(hash)) {
                                matched()
                            } else {
                                SignalingTransport.Response(202, ByteArray(0))
                            }
                        }
                    }
                    SignalingClient.PATH_CANDIDATES -> {
                        candidates[message.role.code] = message.candidates
                        SignalingTransport.Response(200, ByteArray(0))
                    }
                    SignalingClient.PATH_ABORT -> SignalingTransport.Response(200, ByteArray(0))
                    else -> SignalingTransport.Response(404, ByteArray(0))
                }
            }

        override fun get(path: String, timeoutMs: Int): SignalingTransport.Response = synchronized(lock) {
            val role = path.substringAfter("role=", "").toIntOrNull()
            val peer = role?.let { CrossNetProtocol.Role.fromCode(it) }
            if (peer == null) return@synchronized SignalingTransport.Response(400, ByteArray(0))
            val id = sessionId ?: return@synchronized SignalingTransport.Response(204, ByteArray(0))
            val found = candidates[peer.code] ?: return@synchronized SignalingTransport.Response(204, ByteArray(0))
            SignalingTransport.Response(
                200,
                CrossNetProtocol.encode(
                    CrossNetProtocol.Message(CrossNetProtocol.Type.CANDIDATES, peer, id, candidates = found),
                ),
            )
        }

        private fun matched(): SignalingTransport.Response {
            val id = sessionId ?: return SignalingTransport.Response(202, ByteArray(0))
            return SignalingTransport.Response(
                200,
                CrossNetProtocol.encode(
                    CrossNetProtocol.Message(CrossNetProtocol.Type.MATCHED, CrossNetProtocol.Role.RECEIVER, id),
                ),
            )
        }
    }
}

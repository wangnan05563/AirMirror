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
        val outcome = connector(client = signaling).connectSender("123456")
        assertFalse(outcome.connected)
        assertEquals(FailureReason.MATCH_TIMEOUT, outcome.failure)
        assertTrue(outcome.prompt.isNotEmpty())
        assertFalse("Q-4 不自建中继，文案里不能出现中继开关", outcome.prompt.contains("中继"))
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
    ): CrossNetConnector = CrossNetConnector(
        role = role,
        client = client,
        socketFactory = { DatagramSocket(0, loopback) },
        collect = collect,
        timeouts = CrossNetConnector.Timeouts(
            matchMs = 3_000,
            candidatesMs = 3_000,
            punchMs = 1_000,
            punchIntervalMs = 50,
        ),
    )

    private fun readExactly(tunnel: UdpTunnel, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        tunnel.setReadTimeout(8_000)
        while (offset < size) {
            val count = tunnel.input.read(result, offset, size - offset)
            if (count < 0) throw IllegalStateException("stream ended at $offset/$size")
            offset += count
        }
        return result
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

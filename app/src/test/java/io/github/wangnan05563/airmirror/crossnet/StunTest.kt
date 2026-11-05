package io.github.wangnan05563.airmirror.crossnet

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StunTest {

    @Test
    fun `成功响应能解出 XOR-MAPPED-ADDRESS`() {
        val transaction = StunCodec.newTransactionId()
        val mapped = StunCodec.MappedAddress("203.0.113.7", 45678)
        val response = StunCodec.bindingSuccess(transaction, mapped)
        assertEquals(mapped, StunCodec.decode(response, transaction))
        assertNull("事务 id 不匹配必须丢弃，防止旧包把新查询打成已连接", StunCodec.decode(response, StunCodec.newTransactionId()))
    }

    @Test
    fun `请求包不是成功响应 半截包与坏 cookie 都不解析`() {
        val transaction = StunCodec.newTransactionId()
        assertNull(StunCodec.decode(StunCodec.bindingRequest(transaction), transaction))
        val response = StunCodec.bindingSuccess(transaction, StunCodec.MappedAddress("203.0.113.7", 45678))
        assertNull(StunCodec.decode(response.copyOf(12), transaction))
        val badCookie = response.copyOf()
        badCookie[4] = 0
        assertNull(StunCodec.decode(badCookie, transaction))
        assertNull(StunCodec.decode(response.copyOf(response.size - 2), transaction))
    }

    @Test
    fun `STUN 客户端能从假服务器取回公网映射`() {
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val responder = Thread {
            try {
                val buffer = ByteArray(StunCodec.MAX_SIZE)
                val request = DatagramPacket(buffer, buffer.size)
                server.receive(request)
                val transaction = request.data.copyOfRange(8, 20)
                val reply = StunCodec.bindingSuccess(
                    transaction,
                    StunCodec.MappedAddress("203.0.113.7", 45678),
                )
                server.send(DatagramPacket(reply, reply.size, request.address, request.port))
            } catch (ignored: Exception) {
                // 测试收尾时 socket 被关闭是预期路径
            }
        }
        responder.isDaemon = true
        responder.start()
        try {
            val mapped = StunClient.query("127.0.0.1", server.localPort, timeoutMs = 2_000)
            assertEquals(StunCodec.MappedAddress("203.0.113.7", 45678), mapped)
        } finally {
            server.close()
            responder.join(1_000)
        }
    }

    @Test
    fun `NAT 分类 同一映射可打洞 端口相关且两端都对称才判不可打`() {
        val a = StunCodec.MappedAddress("203.0.113.7", 45678)
        val b = StunCodec.MappedAddress("203.0.113.7", 45679)
        assertEquals(NatType.BLOCKED, NatClassifier.classify(null, null))
        assertEquals(NatType.UNKNOWN, NatClassifier.classify(a, null))
        assertEquals(NatType.ENDPOINT_INDEPENDENT, NatClassifier.classify(a, a))
        assertEquals(NatType.PORT_DEPENDENT, NatClassifier.classify(a, b))
        assertEquals(
            NatType.PORT_DEPENDENT,
            NatClassifier.classify(a, StunCodec.MappedAddress("198.51.100.9", 45678)),
        )

        assertFalse(NatClassifier.punchable(NatType.BLOCKED, NatType.ENDPOINT_INDEPENDENT))
        assertFalse(NatClassifier.punchable(NatType.PORT_DEPENDENT, NatType.PORT_DEPENDENT))
        assertTrue(NatClassifier.punchable(NatType.PORT_DEPENDENT, NatType.ENDPOINT_INDEPENDENT))
        assertTrue(NatClassifier.punchable(NatType.OPEN, NatType.PORT_DEPENDENT))
    }
}

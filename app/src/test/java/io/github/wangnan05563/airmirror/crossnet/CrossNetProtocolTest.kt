package io.github.wangnan05563.airmirror.crossnet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossNetProtocolTest {

    @Test
    fun `MATCH 不带会话 id 也能回环 码哈希只有 8 字节`() {
        val hash = CrossNetProtocol.codeHash("123456")
        assertEquals(CrossNetProtocol.CODE_HASH_BYTES, hash.size)
        val message = CrossNetProtocol.Message(
            type = CrossNetProtocol.Type.MATCH,
            role = CrossNetProtocol.Role.SENDER,
            codeHash = hash,
        )
        val decoded = CrossNetProtocol.decode(CrossNetProtocol.encode(message))
        assertEquals(message, decoded)
        assertArrayEquals(hash, decoded.codeHash)
    }

    @Test
    fun `MATCHED 必须带会话 id 候选按原顺序回环`() {
        val session = CrossNetProtocol.newSessionId()
        assertEquals(32, session.length)
        assertTrue(session.all { it in '0'..'9' || it in 'a'..'f' })
        val candidates = listOf(
            CrossNetProtocol.Candidate("192.168.1.5", 41523, CrossNetProtocol.CandidateKind.HOST, 0),
            CrossNetProtocol.Candidate("203.0.113.9", 51234, CrossNetProtocol.CandidateKind.SRFLX, 1),
        )
        val message = CrossNetProtocol.Message(
            type = CrossNetProtocol.Type.CANDIDATES,
            role = CrossNetProtocol.Role.RECEIVER,
            sessionId = session,
            candidates = candidates,
        )
        val decoded = CrossNetProtocol.decode(CrossNetProtocol.encode(message))
        assertEquals(message, decoded)
        assertEquals(candidates, decoded.candidates)
    }

    @Test
    fun `ABORT 带原因码 其它消息不带原因`() {
        val message = CrossNetProtocol.Message(
            type = CrossNetProtocol.Type.ABORT,
            role = CrossNetProtocol.Role.RECEIVER,
            sessionId = CrossNetProtocol.newSessionId(),
            reason = FailureReason.SYMMETRIC_NAT,
        )
        assertEquals(FailureReason.SYMMETRIC_NAT, CrossNetProtocol.decode(CrossNetProtocol.encode(message)).reason)
        assertNull(
            CrossNetProtocol.decode(
                CrossNetProtocol.encode(
                    CrossNetProtocol.Message(
                        CrossNetProtocol.Type.READY,
                        CrossNetProtocol.Role.SENDER,
                        CrossNetProtocol.newSessionId(),
                    ),
                ),
            ).reason,
        )
    }

    @Test
    fun `非 MATCH 消息缺会话 id 直接拒绝`() {
        val message = CrossNetProtocol.Message(
            type = CrossNetProtocol.Type.CANDIDATES,
            role = CrossNetProtocol.Role.SENDER,
        )
        try {
            CrossNetProtocol.encode(message)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("session"))
        }
    }

    @Test
    fun `坏 magic 版本与非法候选地址都拒绝`() {
        val good = CrossNetProtocol.encode(
            CrossNetProtocol.Message(
                type = CrossNetProtocol.Type.MATCH,
                role = CrossNetProtocol.Role.SENDER,
                codeHash = CrossNetProtocol.codeHash("123456"),
            ),
        )
        val badMagic = good.copyOf()
        badMagic[0] = 0x00
        try {
            CrossNetProtocol.decode(badMagic)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("magic"))
        }
        val badVersion = good.copyOf()
        badVersion[4] = 2
        try {
            CrossNetProtocol.decode(badVersion)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("version"))
        }
        try {
            CrossNetProtocol.Candidate("not-an-ip", 41523)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            Unit
        }
        try {
            CrossNetProtocol.Candidate("192.168.1.5", 0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            Unit
        }
    }

    @Test
    fun `候选数量上限 8 条`() {
        val many = (1..9).map {
            CrossNetProtocol.Candidate("192.168.1.$it", 41523)
        }
        try {
            CrossNetProtocol.encode(
                CrossNetProtocol.Message(
                    CrossNetProtocol.Type.CANDIDATES,
                    CrossNetProtocol.Role.SENDER,
                    CrossNetProtocol.newSessionId(),
                    candidates = many,
                ),
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("candidates"))
        }
    }

    @Test
    fun `打洞包回环 错码与坏包都解不开`() {
        val session = CrossNetProtocol.newSessionId()
        val packet = PunchWire.encode(session, CrossNetProtocol.Role.SENDER, false, "123456")
        val decoded = PunchWire.decode(packet, "123456")
        assertNotNull(decoded)
        assertEquals(session, decoded!!.sessionId)
        assertEquals(CrossNetProtocol.Role.SENDER, decoded.role)
        assertFalse(decoded.ack)
        assertNull(PunchWire.decode(packet, "654321"))
        assertNull(PunchWire.decode(packet.copyOf().also { it[4] = 2 }, "123456"))
        assertNull(PunchWire.decode(packet.copyOf(packet.size - 1), "123456"))
    }

    @Test
    fun `同网段判定与分流`() {
        assertTrue(Subnet.sameSubnet("192.168.1.5", "192.168.1.9", 24))
        assertFalse(Subnet.sameSubnet("192.168.1.5", "192.168.2.9", 24))
        assertTrue(Subnet.sameSubnet("10.0.0.5", "10.0.0.9", 8))
        assertFalse(Subnet.sameSubnet("10.1.0.5", "172.16.0.9", 8))
        // /28 下 .5 与 .20 不在同一段，避免把跨网误判成同网
        assertFalse(Subnet.sameSubnet("192.168.1.5", "192.168.1.20", 28))
        assertFalse(Subnet.sameSubnet("not-an-ip", "192.168.1.5", 24))
        assertEquals(CrossNetPlanner.Route.LOCAL_DIRECT, CrossNetPlanner.route("192.168.1.5", "192.168.1.9"))
        assertEquals(CrossNetPlanner.Route.SIGNALING, CrossNetPlanner.route("192.168.1.5", "192.168.2.9"))
    }

    @Test
    fun `码哈希只随投屏码变化`() {
        assertArrayEquals(CrossNetProtocol.codeHash("123456"), CrossNetProtocol.codeHash("123456"))
        assertFalse(CrossNetProtocol.codeHash("123456").contentEquals(CrossNetProtocol.codeHash("123457")))
    }
}

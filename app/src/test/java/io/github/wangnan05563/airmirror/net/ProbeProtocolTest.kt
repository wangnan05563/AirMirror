package io.github.wangnan05563.airmirror.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-02 探测报文层：编解码回环、中文名转义、坏包一律丢。 */
class ProbeProtocolTest {

    @Test
    fun `probe 回环保留 nonce 与中文发送端名`() {
        val packet = ProbeProtocol.parse(ProbeProtocol.probe("abc123", "客厅的手机")) as? ProbeProtocol.Packet.Probe
        assertTrue(packet != null)
        assertEquals("abc123", packet!!.nonce)
        assertEquals("客厅的手机", packet.senderName)
    }

    @Test
    fun `ack 回环带出身份 名字 端口与协议`() {
        val bytes = ProbeProtocol.ack("n1", "am-123", "客厅电视", 41523, listOf("AMRC", "DLNA"))
        val reply = ProbeProtocol.parse(bytes) as? ProbeProtocol.Packet.Reply
        assertTrue(reply != null)
        assertEquals(ProbeProtocol.KIND_ACK, reply!!.kind)
        assertEquals("n1", reply.nonce)
        assertEquals("am-123", reply.fingerprint)
        assertEquals("客厅电视", reply.name)
        assertEquals(41523, reply.port)
        assertEquals(listOf("AMRC", "DLNA"), reply.protocols)
    }

    @Test
    fun `hello 无人提问也带完整身份`() {
        val reply = ProbeProtocol.parse(ProbeProtocol.hello("am-9", "卧室盒子", 41523, listOf("AMRC")))
            as? ProbeProtocol.Packet.Reply
        assertTrue(reply != null)
        assertEquals(ProbeProtocol.KIND_HELLO, reply!!.kind)
        assertEquals("", reply.nonce)
        assertEquals("am-9", reply.fingerprint)
    }

    @Test
    fun `坏包一律丢掉不抛异常`() {
        val bad = listOf(
            ByteArray(0),
            "OTHER/1|probe|a|b".toByteArray(),
            "AMDP/1|probe|a".toByteArray(),
            "AMDP/1|probe|a|zz".toByteArray(), // 奇数长度/非十六进制
            "AMDP/1|ack|a|fp|414243|99999|AMRC".toByteArray(), // 端口越界
            ByteArray(ProbeProtocol.MAX_PACKET + 1),
        )
        bad.forEach { assertNull(ProbeProtocol.parse(it)) }
    }

    @Test
    fun `分隔符与控制字符不会破坏帧结构`() {
        val reply = ProbeProtocol.parse(ProbeProtocol.ack("a|b\nc", "fp|1", "电视", 41523, listOf("AM|RC")))
            as? ProbeProtocol.Packet.Reply
        assertTrue(reply != null)
        assertTrue(!reply!!.nonce.contains('|'))
        assertTrue(!reply.fingerprint.contains('|'))
        assertEquals(7, String(ProbeProtocol.ack("a", "fp", "电视", 41523, listOf("AMRC")), Charsets.US_ASCII).split('|').size)
    }
}

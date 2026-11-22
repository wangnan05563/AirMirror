package io.github.wangnan05563.airmirror.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/** T06109：不依赖真实网卡的 mDNS 编解码与服务记录合成测试。 */
class MdnsPacketTest {

    private val service = "_airmirror._tcp.local"
    private val instance = "客厅电视._airmirror._tcp.local"

    @Test
    fun `query 使用 PTR 且包含服务类型`() {
        val bytes = MdnsPacket.query(service)
        assertEquals(12, bytes.size - encodedNameLength(service) - 4)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.contains("_airmirror"))
        assertTrue(text.contains("_tcp"))
        assertTrue(text.contains("local"))
    }

    @Test
    fun `解析 PTR SRV TXT A 并合成设备`() {
        val raw = response()
        val message = MdnsPacket.parse(raw)
        assertEquals(4, message.records.size)
        val devices = MdnsDevices.from(message.records, service)
        assertEquals(1, devices.size)
        val device = devices.single()
        assertEquals("客厅电视", device.name)
        assertEquals("192.168.50.223", device.host)
        assertEquals(41523, device.port)
        assertEquals("fp-abc", device.fingerprint)
    }

    @Test
    fun `没有 TXT 时退回实例名作为指纹`() {
        val message = MdnsPacket.parse(response(withTxt = false))
        val device = MdnsDevices.from(message.records, service).single()
        assertEquals(instance, device.fingerprint)
    }

    @Test
    fun `截断报文不抛异常`() {
        val bytes = response()
        val truncated = bytes.copyOf(bytes.size - 8)
        MdnsPacket.parse(truncated)
    }

    @Test
    fun `本机假接收端可被查询并返回设备`() {
        val startedAt = System.nanoTime()
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true, name = "mdns-test-responder") {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            server.receive(packet)
            val reply = response()
            server.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
        }
        try {
            val client = DatagramSocket()
            client.soTimeout = 2_000
            val query = MdnsPacket.query(service)
            client.send(DatagramPacket(query, query.size, InetAddress.getByName("127.0.0.1"), server.localPort))
            val inbound = DatagramPacket(ByteArray(4096), 4096)
            client.receive(inbound)
            val devices = MdnsDevices.from(MdnsPacket.parse(inbound.data.copyOf(inbound.length)).records, service)
            assertEquals("客厅电视", devices.single().name)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("AC-04 首台设备应 ≤3s，实际 ${elapsedMs}ms", elapsedMs <= 3_000)
            responder.join(1_000)
        } finally {
            server.close()
        }
    }

    private fun response(withTxt: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, 0)
        writeU16(out, 0x8400)
        writeU16(out, 0)
        writeU16(out, 1)
        writeU16(out, 1)
        writeU16(out, if (withTxt) 2 else 1)
        record(out, service, 12, name(instance))
        record(out, instance, 33, srv(41523, "receiver.local"))
        if (withTxt) record(out, instance, 16, txt("fp=fp-abc"))
        record(out, "receiver.local", 1, ip("192.168.50.223"))
        return out.toByteArray()
    }

    private fun record(out: ByteArrayOutputStream, owner: String, type: Int, data: ByteArray) {
        name(out, owner)
        writeU16(out, type)
        writeU16(out, 1)
        writeU32(out, 120)
        writeU16(out, data.size)
        out.write(data)
    }

    private fun name(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        name(out, value)
        return out.toByteArray()
    }

    private fun name(out: ByteArrayOutputStream, value: String) {
        value.trim('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    private fun srv(port: Int, target: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, port)
        name(out, target)
        return out.toByteArray()
    }

    private fun txt(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(bytes.size)
        out.write(bytes)
        return out.toByteArray()
    }

    private fun ip(value: String): ByteArray = value.split('.').map { it.toInt().toByte() }.toByteArray()

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    private fun encodedNameLength(value: String): Int = value.trim('.').split('.').sumOf { it.toByteArray().size + 1 } + 1
}

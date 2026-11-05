package io.github.wangnan05563.airmirror.crossnet

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpTunnelTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun `双向大流量按序到达`() {
        val left = DatagramSocket(0, loopback)
        val right = DatagramSocket(0, loopback)
        val leftTunnel = UdpTunnel(left, InetSocketAddress(loopback, right.localPort)).start()
        val rightTunnel = UdpTunnel(right, InetSocketAddress(loopback, left.localPort)).start()
        try {
            val payload = ByteArray(9_000) { (it % 251).toByte() }
            val echo = ByteArray(4_321) { (it % 97).toByte() }
            leftTunnel.output.write(payload)
            assertEquals(payload.toList(), readExactly(rightTunnel, payload.size).toList())

            rightTunnel.output.write(echo)
            assertEquals(echo.toList(), readExactly(leftTunnel, echo.size).toList())
        } finally {
            leftTunnel.close()
            rightTunnel.close()
        }
    }

    @Test
    fun `对端关闭后读到流末尾`() {
        val left = DatagramSocket(0, loopback)
        val right = DatagramSocket(0, loopback)
        val leftTunnel = UdpTunnel(left, InetSocketAddress(loopback, right.localPort)).start()
        val rightTunnel = UdpTunnel(right, InetSocketAddress(loopback, left.localPort)).start()
        rightTunnel.setReadTimeout(5_000)
        try {
            leftTunnel.output.write("bye".toByteArray())
            leftTunnel.close()
            assertEquals("bye", String(readExactly(rightTunnel, 3), Charsets.UTF_8))
            assertEquals(-1, rightTunnel.input.read())
        } finally {
            leftTunnel.close()
            rightTunnel.close()
        }
    }

    @Test
    fun `中间丢包时靠重传补齐 不乱序`() {
        val left = DatagramSocket(0, loopback)
        val right = DatagramSocket(0, loopback)
        val leftTunnel: UdpTunnel
        val rightTunnel: UdpTunnel
        val relay = UdpRelay(
            a = InetSocketAddress(loopback, left.localPort),
            b = InetSocketAddress(loopback, right.localPort),
            dropFirst = 4,
        )
        leftTunnel = UdpTunnel(left, InetSocketAddress(loopback, relay.port)).start()
        rightTunnel = UdpTunnel(right, InetSocketAddress(loopback, relay.port)).start()
        rightTunnel.setReadTimeout(8_000)
        try {
            val payload = ByteArray(6_000) { (it % 113).toByte() }
            leftTunnel.output.write(payload)
            val actual = try {
                readExactly(rightTunnel, payload.size)
            } catch (e: java.net.SocketTimeoutException) {
                throw AssertionError(
                    "丢包恢复失败：forwarded=${relay.forwarded.get()} dropped=${relay.dropped.get()}",
                    e,
                )
            }
            assertEquals(payload.toList(), actual.toList())
            assertTrue("必须真的丢过包，否则这条用例没证明重传", relay.dropped.get() >= 4)
        } finally {
            leftTunnel.close()
            rightTunnel.close()
            relay.close()
        }
    }

    @Test
    fun `只接受打洞确定的那个对端 无关来源被丢弃`() {
        val left = DatagramSocket(0, loopback)
        val right = DatagramSocket(0, loopback)
        val stranger = DatagramSocket(0, loopback)
        val rightTunnel = UdpTunnel(right, InetSocketAddress(loopback, left.localPort)).start()
        rightTunnel.setReadTimeout(500)
        try {
            // 陌生来源直接向隧道端口发一个格式正确的数据帧：源地址不符，必须被忽略
            val frame = ByteArray(14 + 4)
            frame[0] = 0x41
            frame[1] = 0x4D
            frame[2] = 1
            frame[3] = 1
            frame[12] = 0
            frame[13] = 4
            frame[14] = 'x'.code.toByte()
            frame[15] = 'y'.code.toByte()
            frame[16] = 'z'.code.toByte()
            frame[17] = 'w'.code.toByte()
            stranger.send(DatagramPacket(frame, frame.size, loopback, right.localPort))
            val error = runCatching { rightTunnel.input.read() }.exceptionOrNull()
            assertTrue("无关来源不能喂进流", error is java.net.SocketTimeoutException)
        } finally {
            rightTunnel.close()
            left.close()
            stranger.close()
        }
    }

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

    /** 双向 UDP 中继，用于在单机环回上制造真实丢包。 */
    private class UdpRelay(a: InetSocketAddress, b: InetSocketAddress, private val dropFirst: Int) : Closeable {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        private val portA = a.port
        private val portB = b.port
        private val addressA = a.address
        private val addressB = b.address
        val dropped = AtomicInteger()
        val forwarded = AtomicInteger()
        val port: Int get() = socket.localPort
        private val thread = Thread({ loop() }, "udp-relay").apply { isDaemon = true }

        init {
            thread.start()
        }

        private fun loop() {
            val buffer = ByteArray(2048)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                if (runCatching { socket.receive(packet) }.isFailure) return
                if (dropped.get() < dropFirst) {
                    dropped.incrementAndGet()
                    continue
                }
                val target = when (packet.port) {
                    portA -> InetSocketAddress(addressB, portB)
                    portB -> InetSocketAddress(addressA, portA)
                    else -> continue
                }
                val copy = packet.data.copyOf(packet.length)
                runCatching {
                    socket.send(DatagramPacket(copy, copy.size, target.address, target.port))
                }.onSuccess { forwarded.incrementAndGet() }
            }
        }

        override fun close() {
            runCatching { socket.close() }
            thread.join(500)
        }
    }
}

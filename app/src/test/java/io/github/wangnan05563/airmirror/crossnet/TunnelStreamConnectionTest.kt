package io.github.wangnan05563.airmirror.crossnet

import io.github.wangnan05563.airmirror.net.StreamConnection
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.Protocol
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨网链路验收：隧道上必须能跑通既有的私有通道（HELLO/HELLO_ACK + 投屏码身份锚 + 端到端加密 + 帧收发）。
 * 这条用例证明「跨网不需要另造媒体协议」，也证明隧道不是只能搬裸字节。
 */
class TunnelStreamConnectionTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun `隧道上能完成握手 身份锚校验与加密帧收发`() {
        val left = DatagramSocket(0, loopback)
        val right = DatagramSocket(0, loopback)
        val leftTunnel = UdpTunnel(left, InetSocketAddress(loopback, right.localPort)).start()
        val rightTunnel = UdpTunnel(right, InetSocketAddress(loopback, left.localPort)).start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val server = pool.submit(
                Callable {
                    StreamConnection.overTunnel(rightTunnel).use { conn ->
                        val hello = conn.acceptAsServer(
                            deviceName = "电视",
                            capabilities = Handshake.Capability.ALL,
                            credentials = listOf("123456"),
                        )
                        assertTrue("跨网也必须启用端到端加密", conn.secure)
                        val frame = conn.receive()
                        assertEquals("手机", hello.deviceName)
                        assertEquals(Protocol.Type.CONTROL, frame?.type)
                        assertEquals("cross-net-ok", String(frame!!.payload, Charsets.UTF_8))
                    }
                },
            )
            // 让服务端先进入 acceptAsServer，避免客户端 HELLO 先于服务端等待而多等一个 RTO
            Thread.sleep(100)
            StreamConnection.overTunnel(leftTunnel).use { conn ->
                val ack = conn.connectAsClient(
                    deviceName = "手机",
                    capabilities = Handshake.Capability.ALL,
                    credential = "123456",
                )
                assertTrue("跨网也必须启用端到端加密", conn.secure)
                assertEquals("电视", ack.deviceName)
                conn.send(Protocol.Frame(Protocol.Type.CONTROL, payload = "cross-net-ok".toByteArray()))
            }
            server.get(15, TimeUnit.SECONDS)
        } finally {
            leftTunnel.close()
            rightTunnel.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `投屏码对不上时隧道上用身份锚拒绝 不建立假连接`() {
        val left = DatagramSocket(0, loopback)
        val right = DatagramSocket(0, loopback)
        val leftTunnel = UdpTunnel(left, InetSocketAddress(loopback, right.localPort)).start()
        val rightTunnel = UdpTunnel(right, InetSocketAddress(loopback, left.localPort)).start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val server = pool.submit(
                Callable {
                    runCatching {
                        StreamConnection.overTunnel(rightTunnel).use { conn ->
                            conn.acceptAsServer(
                                deviceName = "电视",
                                capabilities = Handshake.Capability.ALL,
                                credentials = listOf("654321"),
                            )
                        }
                    }.exceptionOrNull()
                },
            )
            Thread.sleep(100)
            val error = runCatching {
                StreamConnection.overTunnel(leftTunnel).use { conn ->
                    conn.connectAsClient(
                        deviceName = "手机",
                        capabilities = Handshake.Capability.ALL,
                        credential = "123456",
                    )
                }
            }.exceptionOrNull()
            assertTrue("错码必须在握手期被拒", error != null)
            assertTrue("服务端同样不能建立连接", server.get(15, TimeUnit.SECONDS) != null)
        } finally {
            leftTunnel.close()
            rightTunnel.close()
            pool.shutdownNow()
        }
    }
}

package io.github.wangnan05563.airmirror.net

import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.KeyExchange
import io.github.wangnan05563.airmirror.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 一条真 TCP 连接上的加密协商：两端都升级就加密，任一端没升级就整条会话保持明文。
 * PRD 14.5「新旧端可协商降级」在这里判定 —— 加密不是开关，是握手谈出来的。
 */
class SecureLinkTest {

    private class Link(val server: StreamConnection, val client: StreamConnection)

    private fun negotiated(clientKey: KeyExchange.Ephemeral?, serverKey: KeyExchange.Ephemeral?): Link {
        val serverSocket = ServerSocket(0)
        val accepted = AtomicReference<StreamConnection>()
        val failure = AtomicReference<Throwable>()
        val ready = CountDownLatch(1)
        val serverThread = thread(isDaemon = true) {
            try {
                accepted.set(StreamConnection(serverSocket.accept()))
                accepted.get().acceptAsServer("电视", Handshake.Capability.ALL, serverKey)
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                ready.countDown()
            }
        }
        val client = StreamConnection(Socket("127.0.0.1", serverSocket.localPort))
        client.connectAsClient("手机", Handshake.Capability.ALL, clientKey)
        ready.await()
        serverThread.join(2_000)
        serverSocket.close()
        failure.get()?.let { throw it }
        return Link(accepted.get() ?: throw IllegalStateException("server handshake never finished"), client)
    }

    @Test
    fun upgradedEndsEncryptBothWays() {
        val link = negotiated(KeyExchange.generate(), KeyExchange.generate())
        assertTrue(link.client.secure)
        assertTrue(link.server.secure)

        val config = byteArrayOf(0, 0, 0, 1, 67, 30, 120, 50)
        link.client.send(Protocol.Frame(Protocol.Type.VIDEO, flags = Protocol.Flags.CONFIG, ptsUs = 1_000L, payload = config))
        val received = link.server.receive()!!
        assertEquals(Protocol.Type.VIDEO, received.type)
        assertEquals("解出来的帧不该带着加密位", Protocol.Flags.CONFIG, received.flags)
        assertEquals(1_000L, received.ptsUs)
        assertEquals(config.toList(), received.payload.toList())

        link.server.send(Protocol.Frame(Protocol.Type.CONTROL, payload = "tier=720p30".toByteArray()))
        assertEquals("tier=720p30", String(link.client.receive()!!.payload, Charsets.UTF_8))
    }

    @Test
    fun legacySenderKeepsTheWholeSessionInClear() {
        val link = negotiated(clientKey = null, serverKey = KeyExchange.generate())
        assertFalse(link.client.secure)
        assertFalse(link.server.secure)
        link.client.send(Protocol.Frame(Protocol.Type.AUTH, payload = "id=dev&cred=123456".toByteArray()))
        assertEquals("id=dev&cred=123456", String(link.server.receive()!!.payload, Charsets.UTF_8))
    }

    @Test
    fun receiverWithoutKeySupportKeepsTheWholeSessionInClear() {
        val link = negotiated(clientKey = KeyExchange.generate(), serverKey = null)
        assertFalse(link.client.secure)
        assertFalse(link.server.secure)
        link.client.send(Protocol.Frame(Protocol.Type.VIDEO, payload = ByteArray(64) { it.toByte() }))
        assertEquals(64, link.server.receive()!!.payload.size)
    }

    @Test
    fun encryptedFrameOnAnUnencryptedSessionIsRefused() {
        // 对端没递公钥却送来密文帧：要么是在骗我们，要么是中段被动过，不能当明文交给解码器
        val link = negotiated(clientKey = null, serverKey = KeyExchange.generate())
        link.client.send(Protocol.Frame(Protocol.Type.VIDEO, flags = Protocol.Flags.ENCRYPTED, ptsUs = 5L, payload = ByteArray(40)))
        assertThrows(Protocol.ProtocolException::class.java) { link.server.receive() }
    }

    @Test
    fun plainFrameStillFlowsOnAnEncryptedSession() {
        // 握手帧本身在密钥建立之前就要发得出去；加密位缺失的帧在已加密会话上按明文收下
        val link = negotiated(KeyExchange.generate(), KeyExchange.generate())
        link.client.send(Protocol.Frame(Protocol.Type.HELLO, payload = "not really a hello".toByteArray()))
        assertEquals("not really a hello", String(link.server.receive()!!.payload, Charsets.UTF_8))
    }
}

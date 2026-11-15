package io.github.wangnan05563.airmirror.pc

import io.github.wangnan05563.airmirror.protocol.Auth
import io.github.wangnan05563.airmirror.protocol.CodeAnchor
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.KeyExchange
import io.github.wangnan05563.airmirror.protocol.MediaCipher
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PcConnectionTest {

    @Test
    fun handshakeAndAuthUseSameWireProtocol() {
        val server = ServerSocket(0)
        val failure = AtomicReference<Throwable>()
        val ready = CountDownLatch(1)
        val code = "123456"
        val serverThread = Thread {
            try {
                ready.countDown()
                val socket = server.accept()
                socket.use {
                    val serverKey = KeyExchange.generate()
                    val hello = Handshake.Hello.decode(
                        Protocol.read(it.getInputStream(), Protocol.HANDSHAKE_MAX_PAYLOAD)!!.payload,
                    )
                    assertTrue(hello.keyShare != null)
                    val proof = CodeAnchor.serverProof(code, hello.keyShare!!, serverKey.publicSpki)
                    val ack = Handshake.HelloAck(
                        Version.CURRENT,
                        Handshake.Capability.VIDEO_H264,
                        "FakeTV",
                        serverKey.publicSpki,
                        proof,
                    )
                    Protocol.write(
                        it.getOutputStream(),
                        Protocol.Frame(Protocol.Type.HELLO_ACK, payload = ack.encode()),
                    )
                    val secret = KeyExchange.sharedSecret(
                        serverKey.privateKey,
                        KeyExchange.decodePublic(hello.keyShare!!),
                    )
                    val keys = KeyExchange.sessionKeys(
                        secret,
                        hello.keyShare!!,
                        serverKey.publicSpki,
                        Version.CURRENT,
                        CodeAnchor.anchorTag(code),
                    )
                    val readCipher = MediaCipher(keys.clientToServer)
                    val writeCipher = MediaCipher(keys.serverToClient)
                    val authFrame = Protocol.read(it.getInputStream())!!
                    val plain = authFrame.copy(
                        flags = authFrame.flags and Protocol.Flags.ENCRYPTED.inv(),
                        payload = readCipher.open(authFrame),
                    )
                    val request = Auth.parseRequest(plain.payload)
                    assertEquals("pc-test", request.deviceId)
                    assertEquals(code, request.credential)
                    val resultFrame = Protocol.Frame(Protocol.Type.AUTH_RESULT, payload = Auth.result(true))
                    Protocol.write(
                        it.getOutputStream(),
                        resultFrame.copy(
                            flags = Protocol.Flags.ENCRYPTED,
                            payload = writeCipher.seal(resultFrame),
                        ),
                    )
                    ready.await(1, TimeUnit.SECONDS)
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }.apply { start() }

        ready.await(2, TimeUnit.SECONDS)
        Socket("127.0.0.1", server.localPort).use { socket ->
            val conn = PcConnection(socket)
            val ack = conn.connectAsClient("PC-Test", Handshake.Capability.VIDEO_H264, code)
            assertEquals("FakeTV", ack.deviceName)
            assertTrue("ECDH should enable encryption", conn.secure)
            val result = conn.authorize("pc-test", code, timeoutMs = 2000)
            assertTrue(result.accepted)
            conn.send(Protocol.Frame(Protocol.Type.VIDEO, payload = byteArrayOf(1, 2, 3)))
        }
        serverThread.join(3000)
        server.close()
        failure.get()?.let { throw it }
    }
}

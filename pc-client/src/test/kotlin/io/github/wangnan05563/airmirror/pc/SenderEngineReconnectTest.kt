package io.github.wangnan05563.airmirror.pc

import io.github.wangnan05563.airmirror.protocol.Auth
import io.github.wangnan05563.airmirror.protocol.CodeAnchor
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.KeyExchange
import io.github.wangnan05563.airmirror.protocol.MediaCipher
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.Version
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SenderEngineReconnectTest {

    @Test
    fun reconnectsAfterReceiverDropsConnection() {
        val code = "135790"
        val server = ServerSocket(0)
        val secondAuthed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val statuses = CopyOnWriteArrayList<String>()

        val receiver = Thread {
            try {
                repeat(2) { round ->
                    server.accept().use { socket ->
                        val serverKey = KeyExchange.generate()
                        val hello = Handshake.Hello.decode(
                            Protocol.read(socket.getInputStream(), Protocol.HANDSHAKE_MAX_PAYLOAD)!!.payload,
                        )
                        val proof = CodeAnchor.serverProof(code, hello.keyShare!!, serverKey.publicSpki)
                        Protocol.write(
                            socket.getOutputStream(),
                            Protocol.Frame(
                                Protocol.Type.HELLO_ACK,
                                payload = Handshake.HelloAck(
                                    Version.CURRENT,
                                    Handshake.Capability.VIDEO_H264,
                                    "ReconnectTV",
                                    serverKey.publicSpki,
                                    proof,
                                ).encode(),
                            ),
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
                        val authFrame = Protocol.read(socket.getInputStream())!!
                        val request = Auth.parseRequest(
                            authFrame.copy(
                                flags = authFrame.flags and Protocol.Flags.ENCRYPTED.inv(),
                                payload = readCipher.open(authFrame),
                            ).payload,
                        )
                        assertTrue(request.credential == code)
                        val resultFrame = Protocol.Frame(Protocol.Type.AUTH_RESULT, payload = Auth.result(true))
                        Protocol.write(
                            socket.getOutputStream(),
                            resultFrame.copy(
                                flags = Protocol.Flags.ENCRYPTED,
                                payload = writeCipher.seal(resultFrame),
                            ),
                        )
                        if (round == 1) secondAuthed.countDown()
                        // 第一轮授权后立即断开，验证发送端会在 3 秒后重连
                    }
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }.apply { isDaemon = true; start() }

        val sender = SenderEngine(
            SenderEngine.Config("127.0.0.1", server.localPort, code, "PC-Test", 320, 180, 5),
            onStatus = { statuses += it },
        )
        try {
            sender.start()
            assertTrue("sender did not reconnect", secondAuthed.await(15, TimeUnit.SECONDS))
            assertTrue("reconnect status missing: $statuses", statuses.any { it.contains("重连") })
        } finally {
            sender.stop()
            server.close()
            receiver.join(2000)
        }
        failure.get()?.let { throw it }
    }
}

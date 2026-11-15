package io.github.wangnan05563.airmirror.pc

import io.github.wangnan05563.airmirror.protocol.Auth
import io.github.wangnan05563.airmirror.protocol.CodeAnchor
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.KeyExchange
import io.github.wangnan05563.airmirror.protocol.MediaCipher
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.Version
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SenderEngineTest {

    @Test
    fun sendsConfigThenKeyFrameToFakeReceiver() {
        assumeFalse("needs a desktop session for AWT Robot", GraphicsEnvironment.isHeadless())
        val code = "246810"
        val server = ServerSocket(0)
        val gotConfig = CountDownLatch(1)
        val gotKeyFrame = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()

        val receiver = Thread {
            try {
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
                                "FakeTV",
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

                    while (gotKeyFrame.count > 0) {
                        val frame = Protocol.read(socket.getInputStream()) ?: break
                        val plain = frame.copy(
                            flags = frame.flags and Protocol.Flags.ENCRYPTED.inv(),
                            payload = readCipher.open(frame),
                        )
                        when (plain.type) {
                            Protocol.Type.VIDEO -> {
                                if (plain.flags and Protocol.Flags.CONFIG != 0) gotConfig.countDown()
                                if (plain.flags and Protocol.Flags.KEY_FRAME != 0 &&
                                    plain.payload.isNotEmpty()
                                ) {
                                    gotKeyFrame.countDown()
                                }
                            }
                            Protocol.Type.BYE -> break
                            else -> Unit
                        }
                    }
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }.apply { isDaemon = true; start() }

        val sender = SenderEngine(
            SenderEngine.Config("127.0.0.1", server.localPort, code, "PC-Test", 320, 180, 5),
            onStatus = {},
        )
        try {
            sender.start()
            assertTrue("receiver did not get CONFIG frame", gotConfig.await(10, TimeUnit.SECONDS))
            assertTrue("receiver did not get IDR frame", gotKeyFrame.await(10, TimeUnit.SECONDS))
        } finally {
            sender.stop()
            server.close()
            receiver.join(2000)
        }
        failure.get()?.let { throw it }
    }
}

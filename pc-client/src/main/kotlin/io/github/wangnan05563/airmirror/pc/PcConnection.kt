package io.github.wangnan05563.airmirror.pc

import io.github.wangnan05563.airmirror.protocol.Auth
import io.github.wangnan05563.airmirror.protocol.CodeAnchor
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.KeyExchange
import io.github.wangnan05563.airmirror.protocol.MediaCipher
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.Version
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Socket

/**
 * PC 端私有通道连接：复用 protocol-core 的帧/握手/加密，仅补 PC 侧没有的 socket 生命周期。
 *
 * 与 Android StreamConnection.connectAsClient 同口径：
 * HELLO（临时公钥 + 投屏码签名）→ HELLO_ACK（验服务端签名）→ AUTH → AUTH_RESULT。
 */
class PcConnection(private val socket: Socket) : Closeable {

    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private var writeCipher: MediaCipher? = null
    private var readCipher: MediaCipher? = null

    val secure: Boolean get() = writeCipher != null

    init {
        runCatching { socket.tcpNoDelay = true }
    }

    fun connectAsClient(
        deviceName: String,
        capabilities: Int,
        credential: String,
        key: KeyExchange.Ephemeral = KeyExchange.generate(),
    ): Handshake.HelloAck {
        val proof = if (CodeAnchor.usable(credential)) {
            CodeAnchor.clientProof(credential, key.publicSpki)
        } else {
            null
        }
        val hello = Handshake.Hello(
            Version.MIN_SUPPORTED,
            Version.CURRENT,
            capabilities,
            deviceName,
            key.publicSpki,
            proof,
        )
        Protocol.write(output, Protocol.Frame(Protocol.Type.HELLO, payload = hello.encode()))
        val reply = Protocol.read(input, Protocol.HANDSHAKE_MAX_PAYLOAD)
            ?: throw IOException("connection closed during handshake")
        if (reply.type == Protocol.Type.ERROR) {
            throw Handshake.HandshakeException(String(reply.payload, Charsets.UTF_8))
        }
        if (reply.type != Protocol.Type.HELLO_ACK) {
            throw Handshake.HandshakeException("expected HELLO_ACK, got ${reply.type}")
        }
        val ack = Handshake.HelloAck.decode(reply.payload)
        val peerKey = ack.keyShare
        val anchor = if (peerKey != null && CodeAnchor.usable(credential) && ack.proof != null) {
            val expected = CodeAnchor.serverProof(credential, key.publicSpki, peerKey)
            if (!CodeAnchor.matches(expected, ack.proof)) throw Handshake.HandshakeException(CodeAnchor.REFUSED)
            credential
        } else {
            null
        }
        if (peerKey != null) {
            enableEncryption(
                myPrivate = key.privateKey,
                peerPublicSpki = peerKey,
                clientPublicSpki = key.publicSpki,
                serverPublicSpki = peerKey,
                anchor = anchor,
            )
        }
        return ack
    }

    /** 授权；接收端可能先回 PING，忽略后继续等 AUTH_RESULT。 */
    fun authorize(deviceId: String, credential: String, timeoutMs: Int = 90_000): Auth.Result {
        send(Protocol.Frame(Protocol.Type.AUTH, payload = Auth.request(deviceId, credential)))
        socket.soTimeout = timeoutMs
        try {
            while (true) {
                val frame = receive() ?: throw IOException("connection closed during auth")
                when (frame.type) {
                    Protocol.Type.AUTH_RESULT -> return Auth.parseResult(frame.payload)
                    Protocol.Type.PING -> send(Protocol.Frame(Protocol.Type.PONG, payload = frame.payload))
                    Protocol.Type.ERROR -> throw Handshake.HandshakeException(String(frame.payload, Charsets.UTF_8))
                    else -> Unit
                }
            }
        } finally {
            runCatching { socket.soTimeout = 0 }
        }
    }

    @Synchronized
    fun send(frame: Protocol.Frame) {
        val cipher = writeCipher
        if (cipher == null || frame.type in PLAIN_TYPES) {
            Protocol.write(output, frame)
            return
        }
        require(frame.payload.size + cipher.overhead <= Protocol.MAX_PAYLOAD) {
            "payload too large: ${frame.payload.size}"
        }
        Protocol.write(
            output,
            frame.copy(flags = frame.flags or Protocol.Flags.ENCRYPTED, payload = cipher.seal(frame)),
        )
    }

    fun receive(): Protocol.Frame? {
        val frame = Protocol.read(input) ?: return null
        val cipher = readCipher
        if (frame.flags and Protocol.Flags.ENCRYPTED != 0) {
            if (cipher == null) throw Protocol.ProtocolException("encrypted frame on an unencrypted session")
            return frame.copy(
                flags = frame.flags and Protocol.Flags.ENCRYPTED.inv(),
                payload = cipher.open(frame),
            )
        }
        if (cipher != null && frame.type !in PLAIN_TYPES) {
            throw Protocol.ProtocolException("plain ${frame.type} on an encrypted session")
        }
        return frame
    }

    fun setReadTimeout(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun enableEncryption(
        myPrivate: java.security.PrivateKey,
        peerPublicSpki: ByteArray,
        clientPublicSpki: ByteArray,
        serverPublicSpki: ByteArray,
        anchor: String?,
    ) {
        val secret = KeyExchange.sharedSecret(myPrivate, KeyExchange.decodePublic(peerPublicSpki))
        val keys = KeyExchange.sessionKeys(
            secret,
            clientPublicSpki,
            serverPublicSpki,
            Version.CURRENT,
            anchor?.let(CodeAnchor::anchorTag),
        )
        secret.fill(0)
        val fromClient = MediaCipher(keys.clientToServer)
        val fromServer = MediaCipher(keys.serverToClient)
        keys.clientToServer.fill(0)
        keys.serverToClient.fill(0)
        writeCipher = fromClient
        readCipher = fromServer
    }

    private companion object {
        val PLAIN_TYPES = setOf(Protocol.Type.HELLO, Protocol.Type.HELLO_ACK, Protocol.Type.ERROR)
    }
}

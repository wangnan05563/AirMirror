package io.github.wangnan05563.airmirror.net

import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.KeyExchange
import io.github.wangnan05563.airmirror.protocol.MediaCipher
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.Version
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.Socket

/**
 * 一条私有通道连接：TCP + 版本握手 + 帧收发。
 * 客户端先发 HELLO，服务端回 HELLO_ACK；握手失败抛 [IOException]。
 *
 * 两端都在握手时递上临时公钥的话，之后的载荷一律端到端加密（PRD 8.9）；
 * 任何一端没递就整条会话保持明文，好让没升级的对端照旧能连。
 */
class StreamConnection(private val socket: Socket) : Closeable {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())

    private var writeCipher: MediaCipher? = null
    private var readCipher: MediaCipher? = null

    /** 这一条连接是否已加密；老对端为 false，界面与日志都按这个口径说话。 */
    val secure: Boolean get() = writeCipher != null

    /**
     * 客户端：发 HELLO（带本端临时公钥），收 HELLO_ACK。
     * [key] 为 null 表示这一端不启用加密，用于对照取证。
     */
    fun connectAsClient(deviceName: String, capabilities: Int, key: KeyExchange.Ephemeral? = KeyExchange.generate()): Handshake.HelloAck {
        socket.tcpNoDelay = true
        val hello = Handshake.Hello(Version.MIN_SUPPORTED, Version.CURRENT, capabilities, deviceName, key?.publicSpki)
        Protocol.write(output, Protocol.Frame(Protocol.Type.HELLO, payload = hello.encode()))
        val reply = Protocol.read(input) ?: throw java.io.IOException("connection closed during handshake")
        if (reply.type == Protocol.Type.ERROR) {
            throw Handshake.HandshakeException(String(reply.payload, Charsets.UTF_8))
        }
        if (reply.type != Protocol.Type.HELLO_ACK) {
            throw Handshake.HandshakeException("expected HELLO_ACK, got ${reply.type}")
        }
        val ack = Handshake.HelloAck.decode(reply.payload)
        val peerKey = ack.keyShare
        if (key != null && peerKey != null) {
            enableEncryption(
                myPrivate = key.privateKey,
                peerPublicSpki = peerKey,
                clientPublicSpki = key.publicSpki,
                serverPublicSpki = peerKey,
                protocolVersion = ack.chosenVersion,
                iAmClient = true,
            )
        }
        return ack
    }

    /** 服务端：收 HELLO，回 HELLO_ACK（带本端临时公钥）。 */
    fun acceptAsServer(
        deviceName: String,
        capabilities: Int,
        key: KeyExchange.Ephemeral? = KeyExchange.generate(),
    ): Handshake.Hello {
        socket.tcpNoDelay = true
        val helloFrame = Protocol.read(input) ?: throw java.io.IOException("connection closed before HELLO")
        if (helloFrame.type != Protocol.Type.HELLO) {
            throw Handshake.HandshakeException("expected HELLO, got ${helloFrame.type}")
        }
        val hello = Handshake.Hello.decode(helloFrame.payload)
        val ack = try {
            Handshake.accept(hello, deviceName)
        } catch (e: Handshake.HandshakeException) {
            Protocol.write(
                output,
                Protocol.Frame(Protocol.Type.ERROR, payload = e.message.orEmpty().toByteArray(Charsets.UTF_8)),
            )
            throw e
        }
        val ackFrame = Handshake.HelloAck(
            ack.chosenVersion,
            capabilities and hello.capabilities,
            deviceName,
            if (hello.keyShare != null) key?.publicSpki else null,
        )
        Protocol.write(output, Protocol.Frame(Protocol.Type.HELLO_ACK, payload = ackFrame.encode()))
        val peerKey = hello.keyShare
        val myKey = key
        if (peerKey != null && myKey != null && ackFrame.keyShare != null) {
            enableEncryption(
                myPrivate = myKey.privateKey,
                peerPublicSpki = peerKey,
                clientPublicSpki = peerKey,
                serverPublicSpki = myKey.publicSpki,
                protocolVersion = ackFrame.chosenVersion,
                iAmClient = false,
            )
        }
        return hello
    }

    /** 接管流程会从别的连接线程往当前会话写信令，串行化避免帧头与载荷交错。 */
    @Synchronized
    fun send(frame: Protocol.Frame) {
        val cipher = writeCipher
        // 握手帧与握手失败的 ERROR 必须在密钥建立之前就发得出去，保持明文
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
        if (frame.flags and Protocol.Flags.ENCRYPTED == 0) return frame
        val cipher = readCipher ?: throw Protocol.ProtocolException("encrypted frame on an unencrypted session")
        return frame.copy(flags = frame.flags and Protocol.Flags.ENCRYPTED.inv(), payload = cipher.open(frame))
    }

    /** 读超时：等待对端某一条特定帧时用，0 表示不超时 */
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
        protocolVersion: Int,
        iAmClient: Boolean,
    ) {
        val secret = KeyExchange.sharedSecret(myPrivate, KeyExchange.decodePublic(peerPublicSpki))
        val keys = KeyExchange.sessionKeys(secret, clientPublicSpki, serverPublicSpki, protocolVersion)
        secret.fill(0)
        val fromClient = MediaCipher(keys.clientToServer)
        val fromServer = MediaCipher(keys.serverToClient)
        keys.clientToServer.fill(0)
        keys.serverToClient.fill(0)
        writeCipher = if (iAmClient) fromClient else fromServer
        readCipher = if (iAmClient) fromServer else fromClient
    }

    companion object {
        private val PLAIN_TYPES = setOf(Protocol.Type.HELLO, Protocol.Type.HELLO_ACK, Protocol.Type.ERROR)
    }
}

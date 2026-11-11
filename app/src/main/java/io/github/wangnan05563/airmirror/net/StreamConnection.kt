package io.github.wangnan05563.airmirror.net

import io.github.wangnan05563.airmirror.protocol.CodeAnchor
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
 * 一条私有通道连接：字节流 + 版本握手 + 帧收发。
 * 客户端先发 HELLO，服务端回 HELLO_ACK；握手失败抛 [IOException]。
 *
 * 两端都在握手时递上临时公钥的话，之后的载荷一律端到端加密（PRD 8.9）；
 * 任何一端没递就整条会话保持明文，好让没升级的对端照旧能连。
 *
 * 传输层是可替换的：局域网走 TCP，跨网走 [UdpTunnel]。
 * 正因为握手、身份锚、加密、帧协议都只依赖字节流，跨网不需要另造一套私有协议。
 */
class StreamConnection private constructor(private val channel: FrameChannel) : Closeable {
    private val input = channel.input
    private val output = channel.output

    constructor(socket: Socket) : this(SocketChannel(socket))

    private var writeCipher: MediaCipher? = null
    private var readCipher: MediaCipher? = null

    /** 这一条连接是否已加密；老对端为 false，界面与日志都按这个口径说话。 */
    val secure: Boolean get() = writeCipher != null

    /**
     * 客户端：发 HELLO（带本端临时公钥），收 HELLO_ACK。
     * [key] 为 null 表示这一端不启用加密，用于对照取证。
     * [credential] 是本次要交的投屏码/扫码令牌：非空时用它对公钥签名，并把它混进密钥派生（T04493 身份锚）；
     * 空串（可信设备、断线续会）不产生锚，老接收端也不认这个尾字段，照旧能连。
     */
    fun connectAsClient(
        deviceName: String,
        capabilities: Int,
        key: KeyExchange.Ephemeral? = KeyExchange.generate(),
        credential: String = "",
    ): Handshake.HelloAck {
        val proof = if (key != null && CodeAnchor.usable(credential)) {
            CodeAnchor.clientProof(credential, key.publicSpki)
        } else {
            null
        }
        val hello = Handshake.Hello(
            Version.MIN_SUPPORTED,
            Version.CURRENT,
            capabilities,
            deviceName,
            key?.publicSpki,
            proof,
        )
        Protocol.write(output, Protocol.Frame(Protocol.Type.HELLO, payload = hello.encode()))
        // 握手帧只用小上限：此时还没验证任何凭据，不该让对端用一个帧头换来大块堆分配
        val reply = Protocol.read(input, Protocol.HANDSHAKE_MAX_PAYLOAD)
            ?: throw java.io.IOException("connection closed during handshake")
        if (reply.type == Protocol.Type.ERROR) {
            throw Handshake.HandshakeException(String(reply.payload, Charsets.UTF_8))
        }
        if (reply.type != Protocol.Type.HELLO_ACK) {
            throw Handshake.HandshakeException("expected HELLO_ACK, got ${reply.type}")
        }
        val ack = Handshake.HelloAck.decode(reply.payload)
        val peerKey = ack.keyShare
        // 只有大屏回了校验值才说明它也认锚：老接收端不回，这一版就按无锚派生，不让它因此连不上
        val anchor = if (key != null && peerKey != null && CodeAnchor.usable(credential) && ack.proof != null) {
            val expected = CodeAnchor.serverProof(credential, key.publicSpki, peerKey)
            if (!CodeAnchor.matches(expected, ack.proof)) throw Handshake.HandshakeException(CodeAnchor.REFUSED)
            credential
        } else {
            null
        }
        if (key != null && peerKey != null) {
            enableEncryption(
                myPrivate = key.privateKey,
                peerPublicSpki = peerKey,
                clientPublicSpki = key.publicSpki,
                serverPublicSpki = peerKey,
                protocolVersion = ack.chosenVersion,
                iAmClient = true,
                anchor = anchor,
            )
        }
        return ack
    }

    /**
     * 服务端：收 HELLO，回 HELLO_ACK（带本端临时公钥）。
     * [credentials] 是当前仍可受理的一组凭据（当前码/令牌 + 宽限期内的上一组）：
     * 发送端带了签名时必须对得上其中一条，否则在 HELLO_ACK 之前就回 ERROR 关掉——绝不先建立一条「可解密的假连接」。
     * 列表为空（锁定中/还没出码）时不做锚校验，让 AUTH 那条路给出真实原因（锁定、过期），不误报成码错。
     */
    fun acceptAsServer(
        deviceName: String,
        capabilities: Int,
        key: KeyExchange.Ephemeral? = KeyExchange.generate(),
        credentials: List<String> = emptyList(),
    ): Handshake.Hello {
        // HELLO 到达时还没有任何授权：上限按握手帧收紧，避免未认证连接申请 8MB 缓冲
        val helloFrame = Protocol.read(input, Protocol.HANDSHAKE_MAX_PAYLOAD)
            ?: throw java.io.IOException("connection closed before HELLO")
        if (helloFrame.type != Protocol.Type.HELLO) {
            throw Handshake.HandshakeException("expected HELLO, got ${helloFrame.type}")
        }
        val hello = Handshake.Hello.decode(helloFrame.payload)
        val matched = matchedCredential(hello, credentials)
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
            if (matched != null && hello.keyShare != null && key != null) {
                CodeAnchor.serverProof(matched, hello.keyShare, key.publicSpki)
            } else {
                null
            },
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
                anchor = matched,
            )
        }
        return hello
    }

    /**
     * 身份锚校验。返回匹配上的那条凭据（两端必须用它派生同一套密钥）；null 表示这条连接没有锚。
     * 签名覆盖发送端自己的公钥：中间人换公钥后签名立刻对不上，这里直接回 ERROR 并断开。
     */
    private fun matchedCredential(hello: Handshake.Hello, credentials: List<String>): String? {
        val proof = hello.proof ?: return null
        val peerKey = hello.keyShare ?: return null
        if (credentials.isEmpty()) return null
        val matched = credentials.firstOrNull { CodeAnchor.matches(CodeAnchor.clientProof(it, peerKey), proof) }
        if (matched == null) {
            Protocol.write(
                output,
                Protocol.Frame(Protocol.Type.ERROR, payload = CodeAnchor.REFUSED.toByteArray(Charsets.UTF_8)),
            )
            throw Handshake.HandshakeException(CodeAnchor.REFUSED)
        }
        return matched
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
        val cipher = readCipher
        if (frame.flags and Protocol.Flags.ENCRYPTED != 0) {
            if (cipher == null) throw Protocol.ProtocolException("encrypted frame on an unencrypted session")
            return frame.copy(flags = frame.flags and Protocol.Flags.ENCRYPTED.inv(), payload = cipher.open(frame))
        }
        // 已加密的会话里，只有握手类帧可以合法地保持明文（密钥建立前发出，或握手失败的 ERROR）。
        // 媒体、控制、BYE 出现在明文里说明线路被人插了帧：照单全收等于让中间人注入伪造画面、
        // 假暂停或假结束，端到端加密的完整性承诺就没了。
        if (cipher != null && frame.type !in PLAIN_TYPES) {
            throw Protocol.ProtocolException("plain ${frame.type} on an encrypted session")
        }
        return frame
    }

    /** 读超时：等待对端某一条特定帧时用，0 表示不超时 */
    fun setReadTimeout(timeoutMs: Int) {
        channel.setReadTimeout(timeoutMs)
    }

    /**
     * 只关发送方向。用于「说完 BYE 就走」的收尾：接收缓冲里还留着对端推来的帧时，
     * 直接 close 会发 RST 把 BYE 一起丢掉，对端就把它当成意外断链。
     */
    fun shutdownOutput() {
        channel.shutdownOutput()
    }

    override fun close() {
        channel.close()
    }

    private fun enableEncryption(
        myPrivate: java.security.PrivateKey,
        peerPublicSpki: ByteArray,
        clientPublicSpki: ByteArray,
        serverPublicSpki: ByteArray,
        protocolVersion: Int,
        iAmClient: Boolean,
        anchor: String? = null,
    ) {
        val secret = KeyExchange.sharedSecret(myPrivate, KeyExchange.decodePublic(peerPublicSpki))
        val keys = KeyExchange.sessionKeys(
            secret,
            clientPublicSpki,
            serverPublicSpki,
            protocolVersion,
            anchor?.let(CodeAnchor::anchorTag),
        )
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

        /**
         * 跨网：在已打通的隧道上跑同一条私有通道。
         * 握手、投屏码身份锚、端到端加密、帧协议全部复用，跨网因此不需要第二套私有协议——
         * 差异只在「字节流从哪来」，而这一层已经被隧道收敛掉了。
         */
        fun overTunnel(
            tunnel: io.github.wangnan05563.airmirror.crossnet.UdpTunnel,
        ): StreamConnection = StreamConnection(TunnelChannel(tunnel))
    }
}

/** 私有通道只依赖字节流与读超时，传输方式的差异全部收敛在这里。 */
internal interface FrameChannel : Closeable {
    val input: java.io.InputStream
    val output: java.io.OutputStream
    fun setReadTimeout(timeoutMs: Int)

    /**
     * 有序收尾：只关发送方向，先让对端读到 EOF。
     * 接收缓冲里还留着对端推来的帧时直接 close 会发 RST，RST 会把本端刚写的 BYE 一起丢掉。
     */
    fun shutdownOutput()
}

private class SocketChannel(private val socket: Socket) : FrameChannel {
    override val input = BufferedInputStream(socket.getInputStream())
    override val output = BufferedOutputStream(socket.getOutputStream())

    init {
        runCatching { socket.tcpNoDelay = true }
    }

    override fun setReadTimeout(timeoutMs: Int) {
        runCatching { socket.soTimeout = timeoutMs }
    }

    override fun shutdownOutput() {
        runCatching { socket.shutdownOutput() }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

private class TunnelChannel(
    private val tunnel: io.github.wangnan05563.airmirror.crossnet.UdpTunnel,
) : FrameChannel {
    override val input: java.io.InputStream get() = tunnel.input
    override val output: java.io.OutputStream get() = tunnel.output

    override fun setReadTimeout(timeoutMs: Int) = tunnel.setReadTimeout(timeoutMs)

    /** 隧道没有半关概念：它的 close 自带 FIN 与重传，收尾语义与半关一致。 */
    override fun shutdownOutput() = Unit

    override fun close() = tunnel.close()
}

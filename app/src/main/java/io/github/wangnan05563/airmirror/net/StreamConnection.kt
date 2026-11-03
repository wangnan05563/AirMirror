package io.github.wangnan05563.airmirror.net

import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.Version
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.Socket

/**
 * 一条私有通道连接：TCP + 版本握手 + 帧收发。
 * 客户端先发 HELLO，服务端回 HELLO_ACK；握手失败抛 [IOException]。
 */
class StreamConnection(private val socket: Socket) : Closeable {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())

    fun connectAsClient(deviceName: String, capabilities: Int): Handshake.HelloAck {
        socket.tcpNoDelay = true
        val hello = Handshake.Hello(Version.MIN_SUPPORTED, Version.CURRENT, capabilities, deviceName)
        Protocol.write(output, Protocol.Frame(Protocol.Type.HELLO, payload = hello.encode()))
        val reply = Protocol.read(input) ?: throw java.io.IOException("connection closed during handshake")
        if (reply.type == Protocol.Type.ERROR) {
            throw Handshake.HandshakeException(String(reply.payload, Charsets.UTF_8))
        }
        if (reply.type != Protocol.Type.HELLO_ACK) {
            throw Handshake.HandshakeException("expected HELLO_ACK, got ${reply.type}")
        }
        return Handshake.HelloAck.decode(reply.payload)
    }

    fun acceptAsServer(deviceName: String, capabilities: Int): Handshake.Hello {
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
        val ackFrame = Handshake.HelloAck(ack.chosenVersion, capabilities and hello.capabilities, deviceName)
        Protocol.write(output, Protocol.Frame(Protocol.Type.HELLO_ACK, payload = ackFrame.encode()))
        return hello
    }

    fun send(frame: Protocol.Frame) = Protocol.write(output, frame)

    fun receive(): Protocol.Frame? = Protocol.read(input)

    /** 读超时：等待对端某一条特定帧时用，0 表示不超时 */
    fun setReadTimeout(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

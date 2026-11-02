package io.github.wangnan05563.airmirror.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 握手载荷编解码。双方交换版本区间与能力位，按 [Version.negotiate] 取最高公共版本。
 */
object Handshake {
    object Capability {
        const val VIDEO_H264 = 1 shl 0
        const val AUDIO_AAC = 1 shl 1
        const val TOUCH_INPUT = 1 shl 2
        const val ALL = VIDEO_H264 or AUDIO_AAC or TOUCH_INPUT
    }

    data class Hello(
        val minVersion: Int,
        val maxVersion: Int,
        val capabilities: Int,
        val deviceName: String,
    ) {
        fun encode(): ByteArray = ByteArrayOutputStream().use { bytes ->
            val out = java.io.DataOutputStream(bytes)
            out.writeShort(minVersion)
            out.writeShort(maxVersion)
            out.writeInt(capabilities)
            val name = deviceName.toByteArray(Charsets.UTF_8)
            out.writeShort(name.size)
            out.write(name)
            out.flush()
            bytes.toByteArray()
        }

        companion object {
            fun decode(payload: ByteArray): Hello {
                val input = java.io.DataInputStream(ByteArrayInputStream(payload))
                val min = input.readUnsignedShort()
                val max = input.readUnsignedShort()
                val caps = input.readInt()
                val nameLength = input.readUnsignedShort()
                require(nameLength <= input.available()) { "truncated device name" }
                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                return Hello(min, max, caps, String(nameBytes, Charsets.UTF_8))
            }
        }
    }

    data class HelloAck(
        val chosenVersion: Int,
        val capabilities: Int,
        val deviceName: String,
    ) {
        fun encode(): ByteArray = ByteArrayOutputStream().use { bytes ->
            val out = java.io.DataOutputStream(bytes)
            out.writeShort(chosenVersion)
            out.writeInt(capabilities)
            val name = deviceName.toByteArray(Charsets.UTF_8)
            out.writeShort(name.size)
            out.write(name)
            out.flush()
            bytes.toByteArray()
        }

        companion object {
            fun decode(payload: ByteArray): HelloAck {
                val input = java.io.DataInputStream(ByteArrayInputStream(payload))
                val version = input.readUnsignedShort()
                val caps = input.readInt()
                val nameLength = input.readUnsignedShort()
                require(nameLength <= input.available()) { "truncated device name" }
                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                return HelloAck(version, caps, String(nameBytes, Charsets.UTF_8))
            }
        }
    }

    class HandshakeException(message: String) : java.io.IOException(message)

    /** 服务端视角：收到 HELLO 后决定回什么。 */
    fun accept(hello: Hello, deviceName: String): HelloAck {
        val chosen = Version.negotiate(
            clientMin = hello.minVersion,
            clientMax = hello.maxVersion,
            serverMin = Version.MIN_SUPPORTED,
            serverMax = Version.CURRENT,
        ) ?: throw HandshakeException(
            "no common protocol version: client=${hello.minVersion}..${hello.maxVersion}, " +
                "server=${Version.MIN_SUPPORTED}..${Version.CURRENT}",
        )
        return HelloAck(chosen, Capability.ALL and hello.capabilities, deviceName)
    }
}

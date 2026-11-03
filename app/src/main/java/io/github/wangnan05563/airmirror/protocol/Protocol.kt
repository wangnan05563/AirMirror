package io.github.wangnan05563.airmirror.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 私有通道帧格式（PRD 8.4 私有通道 + 14.5 协议版本化）。
 *
 * 帧头固定 20 字节，大端：
 *   magic(4) version(1) type(1) flags(1) reserved(1) ptsUs(8) payloadLen(4)
 */
object Protocol {
    const val MAGIC = 0x414D5243 // "AMRC"
    const val HEADER_SIZE = 20
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    enum class Type(val code: Int) {
        HELLO(1),
        HELLO_ACK(2),
        VIDEO(3),
        AUDIO(4),
        CONTROL(5),
        PING(6),
        PONG(7),
        BYE(8),
        ERROR(9),
        AUTH(10),
        AUTH_RESULT(11);

        companion object {
            fun fromCode(code: Int): Type? = entries.firstOrNull { it.code == code }
        }
    }

    object Flags {
        const val KEY_FRAME = 0x01
        const val CONFIG = 0x02
        const val END_OF_STREAM = 0x04
    }

    class ProtocolException(message: String) : IOException(message)

    data class Frame(
        val type: Type,
        val version: Int = Version.CURRENT,
        val flags: Int = 0,
        val ptsUs: Long = 0L,
        val payload: ByteArray = EMPTY,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return type == other.type && version == other.version && flags == other.flags &&
                ptsUs == other.ptsUs && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + version
            result = 31 * result + flags
            result = 31 * result + ptsUs.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }

        companion object {
            val EMPTY = ByteArray(0)
        }
    }

    fun write(out: OutputStream, frame: Frame) {
        require(frame.payload.size <= MAX_PAYLOAD) { "payload too large: ${frame.payload.size}" }
        require(frame.version in 0..0xFF) { "version out of range: ${frame.version}" }
        val data = DataOutputStream(out)
        data.writeInt(MAGIC)
        data.writeByte(frame.version)
        data.writeByte(frame.type.code)
        data.writeByte(frame.flags)
        data.writeByte(0)
        data.writeLong(frame.ptsUs)
        data.writeInt(frame.payload.size)
        data.write(frame.payload)
        data.flush()
    }

    /** 读取一帧；对端正常关闭返回 null，读到半帧则抛 EOFException。 */
    fun read(input: InputStream): Frame? {
        val data = DataInputStream(input)
        val magic = try {
            data.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (magic != MAGIC) throw ProtocolException("bad magic: 0x%08X".format(magic))
        val version = data.readUnsignedByte()
        val typeCode = data.readUnsignedByte()
        val flags = data.readUnsignedByte()
        data.readUnsignedByte() // reserved
        val ptsUs = data.readLong()
        val length = data.readInt()
        if (version !in Version.MIN_SUPPORTED..Version.CURRENT) {
            throw ProtocolException("unsupported version: $version")
        }
        val type = Type.fromCode(typeCode) ?: throw ProtocolException("unknown frame type: $typeCode")
        if (length < 0 || length > MAX_PAYLOAD) throw ProtocolException("bad payload length: $length")
        val payload = ByteArray(length)
        data.readFully(payload)
        return Frame(type, version, flags, ptsUs, payload)
    }
}

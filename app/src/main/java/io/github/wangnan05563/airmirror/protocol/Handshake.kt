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
        /** 本端临时公钥（SPKI DER）；不带表示这一版还不做端到端加密，见 [KeyExchange] */
        val keyShare: ByteArray? = null,
        /** 用投屏码对 [keyShare] 的签名（[CodeAnchor] 身份锚）；不带表示这一端没有码，老接收端也读不懂它 */
        val proof: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Hello) return false
            return minVersion == other.minVersion && maxVersion == other.maxVersion &&
                capabilities == other.capabilities && deviceName == other.deviceName &&
                sameKeyShare(keyShare, other.keyShare) && sameKeyShare(proof, other.proof)
        }

        override fun hashCode(): Int {
            var result = minVersion
            result = 31 * result + maxVersion
            result = 31 * result + capabilities
            result = 31 * result + deviceName.hashCode()
            result = 31 * result + (keyShare?.contentHashCode() ?: 0)
            result = 31 * result + (proof?.contentHashCode() ?: 0)
            return result
        }

        fun encode(): ByteArray = ByteArrayOutputStream().use { bytes ->
            val out = java.io.DataOutputStream(bytes)
            out.writeShort(minVersion)
            out.writeShort(maxVersion)
            out.writeInt(capabilities)
            val name = deviceName.toByteArray(Charsets.UTF_8)
            out.writeShort(name.size)
            out.write(name)
            // 公钥与校验值都追加在末尾：老接收端读到名字就返回，多余字节直接忽略，所以老版本照旧能握手
            keyShare?.let {
                out.writeShort(it.size)
                out.write(it)
                proof?.let { tag ->
                    out.writeShort(tag.size)
                    out.write(tag)
                }
            }
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
                val share = keyShare(input)
                return Hello(min, max, caps, String(nameBytes, Charsets.UTF_8), share, if (share == null) null else anchorProof(input))
            }
        }
    }

    /** 名字之后还留着两字节以上才认为带公钥；长度非法一律当作「这一端不懂加密」。 */
    private fun keyShare(input: java.io.DataInputStream): ByteArray? {
        if (input.available() < 2) return null
        val length = input.readUnsignedShort()
        if (length !in 1..KeyExchange.MAX_PUBLIC_KEY || length > input.available()) return null
        val key = ByteArray(length)
        input.readFully(key)
        return key
    }

    /** 公钥之后的两字节+载荷：没有公钥就不可能有校验值，老端的载荷到这里就是尽头。 */
    private fun anchorProof(input: java.io.DataInputStream): ByteArray? {
        if (input.available() < 2) return null
        val length = input.readUnsignedShort()
        if (length !in 1..CodeAnchor.MAX_PROOF || length > input.available()) return null
        val proof = ByteArray(length)
        input.readFully(proof)
        return proof
    }

    data class HelloAck(
        val chosenVersion: Int,
        val capabilities: Int,
        val deviceName: String,
        /** 接收端自己的临时公钥；不带表示这一版还不做端到端加密 */
        val keyShare: ByteArray? = null,
        /** 用同一个投屏码对双方公钥的签名（[CodeAnchor]）；不带表示大屏没有可对的码或对面是老端 */
        val proof: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HelloAck) return false
            return chosenVersion == other.chosenVersion && capabilities == other.capabilities &&
                deviceName == other.deviceName && sameKeyShare(keyShare, other.keyShare) &&
                sameKeyShare(proof, other.proof)
        }

        override fun hashCode(): Int {
            var result = chosenVersion
            result = 31 * result + capabilities
            result = 31 * result + deviceName.hashCode()
            result = 31 * result + (keyShare?.contentHashCode() ?: 0)
            result = 31 * result + (proof?.contentHashCode() ?: 0)
            return result
        }

        fun encode(): ByteArray = ByteArrayOutputStream().use { bytes ->
            val out = java.io.DataOutputStream(bytes)
            out.writeShort(chosenVersion)
            out.writeInt(capabilities)
            val name = deviceName.toByteArray(Charsets.UTF_8)
            out.writeShort(name.size)
            out.write(name)
            keyShare?.let {
                out.writeShort(it.size)
                out.write(it)
                proof?.let { tag ->
                    out.writeShort(tag.size)
                    out.write(tag)
                }
            }
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
                val share = keyShare(input)
                return HelloAck(
                    version,
                    caps,
                    String(nameBytes, Charsets.UTF_8),
                    share,
                    if (share == null) null else anchorProof(input),
                )
            }
        }
    }

    private fun sameKeyShare(a: ByteArray?, b: ByteArray?): Boolean =
        a == null && b == null || a != null && b != null && a.contentEquals(b)

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

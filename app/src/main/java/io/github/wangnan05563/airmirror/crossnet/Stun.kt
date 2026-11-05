package io.github.wangnan05563.airmirror.crossnet

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * STUN 绑定（RFC 5389 的最小子集）：只做「问一次自己的公网映射」，用于枚举跨网候选与判断 NAT 类型。
 *
 * 为什么不用现成库：项目无 AndroidX、无第三方网络库；这里只需要 BINDING_REQUEST /
 * XOR-MAPPED-ADDRESS 两个属性，自己实现比引入一个库更容易锁死行为（也能在 JVM 单测里用假服务器回放）。
 * 不支持 MESSAGE_INTEGRITY / FINGERPRINT：它们只影响与需要鉴权的 STUN 服务互操作，
 * 公网 STUN 的匿名绑定请求不需要。
 */
object StunCodec {
    const val MAGIC_COOKIE = 0x2112A442
    const val BINDING_REQUEST = 0x0001
    const val BINDING_SUCCESS = 0x0101
    const val ATTR_MAPPED_ADDRESS = 0x0001
    const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    const val ATTR_SOFTWARE = 0x8022
    const val HEADER_SIZE = 20
    const val MAX_SIZE = 1024

    data class MappedAddress(val address: String, val port: Int)

    fun newTransactionId(): ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }

    fun bindingRequest(transactionId: ByteArray, software: String = "AirMirror/1"): ByteArray {
        require(transactionId.size == 12) { "transaction id must be 12 bytes" }
        val out = ByteArrayOutputStream(HEADER_SIZE + 4 + software.length)
        writeHeader(out, BINDING_REQUEST, transactionId)
        writeAttribute(out, ATTR_SOFTWARE, software.toByteArray(Charsets.UTF_8))
        patchLength(out)
        return out.toByteArray()
    }

    /**
     * 供自测/假服务器构造成功响应。生产链路只解不构，接收端不需要扮演 STUN 服务。
     */
    fun bindingSuccess(transactionId: ByteArray, mapped: MappedAddress): ByteArray {
        require(transactionId.size == 12) { "transaction id must be 12 bytes" }
        val out = ByteArrayOutputStream()
        writeHeader(out, BINDING_SUCCESS, transactionId)
        val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
        val address = ipv4(mapped.address)
        val value = ByteArray(8)
        value[0] = 0 // reserved
        value[1] = 0x01 // family IPv4
        val xorPort = mapped.port xor 0x2112
        value[2] = ((xorPort shr 8) and 0xFF).toByte()
        value[3] = (xorPort and 0xFF).toByte()
        for (i in 0..3) value[4 + i] = (address[i].toInt() xor cookie[i].toInt()).toByte()
        writeAttribute(out, ATTR_XOR_MAPPED_ADDRESS, value)
        patchLength(out)
        return out.toByteArray()
    }

    /** 返回 null 表示不是「对本次事务的成功绑定响应」；错误响应、半截包、未知属性都按 null 处理。 */
    fun decode(payload: ByteArray, expectedTransactionId: ByteArray? = null): MappedAddress? {
        if (payload.size < HEADER_SIZE || payload.size > MAX_SIZE) return null
        val type = readU16(payload, 0)
        if (type != BINDING_SUCCESS) return null
        val length = readU16(payload, 2)
        if (length + HEADER_SIZE > payload.size) return null
        if (readInt(payload, 4) != MAGIC_COOKIE) return null
        val transaction = payload.copyOfRange(8, 20)
        if (expectedTransactionId != null && !transaction.contentEquals(expectedTransactionId)) return null

        var offset = HEADER_SIZE
        val end = HEADER_SIZE + length
        var mapped: MappedAddress? = null
        while (offset + 4 <= end) {
            val attributeType = readU16(payload, offset)
            val attributeLength = readU16(payload, offset + 2)
            val valueStart = offset + 4
            if (valueStart + attributeLength > end) return null
            if (attributeType == ATTR_XOR_MAPPED_ADDRESS) {
                mapped = decodeAddress(payload, valueStart, attributeLength, xor = true)
            } else if (attributeType == ATTR_MAPPED_ADDRESS) {
                mapped = decodeAddress(payload, valueStart, attributeLength, xor = false)
            }
            // 属性按 4 字节对齐，末尾补齐字节不参与语义
            offset = valueStart + attributeLength + ((4 - attributeLength % 4) % 4)
        }
        return mapped
    }

    private fun decodeAddress(payload: ByteArray, start: Int, length: Int, xor: Boolean): MappedAddress? {
        if (length < 8) return null
        val family = payload[start + 1].toInt() and 0xFF
        if (family != 0x01) return null
        var port = readU16(payload, start + 2)
        if (xor) port = port xor 0x2112
        val raw = payload.copyOfRange(start + 4, start + 8)
        val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
        val address = ByteArray(4) { i -> (raw[i].toInt() xor (if (xor) cookie[i].toInt() else 0)).toByte() }
        return MappedAddress(address.joinToString(".") { (it.toInt() and 0xFF).toString() }, port)
    }

    private fun writeHeader(out: ByteArrayOutputStream, type: Int, transactionId: ByteArray) {
        writeU16(out, type)
        writeU16(out, 0) // length 先占位，写完属性再回填
        out.write(intBytes(MAGIC_COOKIE))
        out.write(transactionId)
    }

    private fun patchLength(out: ByteArrayOutputStream) {
        val bytes = out.toByteArray()
        val length = bytes.size - HEADER_SIZE
        val patched = ByteArray(bytes.size)
        for (i in 0 until 2) patched[i] = bytes[i]
        patched[2] = ((length shr 8) and 0xFF).toByte()
        patched[3] = (length and 0xFF).toByte()
        for (i in 4 until bytes.size) patched[i] = bytes[i]
        out.reset()
        out.write(patched)
    }

    private fun writeAttribute(out: ByteArrayOutputStream, type: Int, value: ByteArray) {
        writeU16(out, type)
        writeU16(out, value.size)
        out.write(value)
        val padding = (4 - value.size % 4) % 4
        repeat(padding) { out.write(0) }
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun ipv4(address: String): ByteArray {
        val parts = address.split('.')
        require(parts.size == 4) { "not IPv4: $address" }
        return ByteArray(4) { i ->
            val octet = parts[i].toIntOrNull()
            require(octet != null && octet in 0..255) { "bad IPv4 octet: $address" }
            octet.toByte()
        }
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}

/**
 * 一次阻塞式 STUN 绑定查询。调用方必须在后台线程使用（Android 主线程不允许网络 IO）。
 */
object StunClient {
    fun query(host: String, port: Int, timeoutMs: Int = 2_000): StunCodec.MappedAddress? {
        require(host.isNotBlank()) { "STUN host is blank" }
        require(port in 1..65535) { "STUN port out of range: $port" }
        require(timeoutMs > 0) { "STUN timeout must be positive" }
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val transaction = StunCodec.newTransactionId()
            val request = StunCodec.bindingRequest(transaction)
            val address = InetAddress.getByName(host)
            socket.send(DatagramPacket(request, request.size, address, port))
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(StunCodec.MAX_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    return null
                }
                val reply = packet.data.copyOf(packet.length)
                val mapped = StunCodec.decode(reply, transaction) ?: continue
                return mapped
            }
            return null
        }
    }
}

/**
 * 用两个本地端口的映射结果粗分 NAT 形态：同一公网 ip:port 说明端点无关（好打洞）；
 * 端口或地址随本地端口变化即视为端口相关（对称 NAT），两端都这样时直接给替代路径，
 * 不把 15 秒耗在注定失败的打洞上（PRD FS-04「双方均处于对称 NAT」）。
 */
enum class NatType {
    OPEN,
    ENDPOINT_INDEPENDENT,
    PORT_DEPENDENT,
    BLOCKED,
    UNKNOWN,
}

object NatClassifier {
    fun classify(first: StunCodec.MappedAddress?, second: StunCodec.MappedAddress?): NatType {
        if (first == null && second == null) return NatType.BLOCKED
        if (first == null || second == null) return NatType.UNKNOWN
        return when {
            first.address == second.address && first.port == second.port -> NatType.ENDPOINT_INDEPENDENT
            else -> NatType.PORT_DEPENDENT
        }
    }

    /** 只有两端都端口相关（对称 NAT × 对称 NAT）才判定打洞不可行；其余组合仍值得试一次。 */
    fun punchable(self: NatType, peer: NatType): Boolean {
        if (self == NatType.BLOCKED || peer == NatType.BLOCKED) return false
        return !(self == NatType.PORT_DEPENDENT && peer == NatType.PORT_DEPENDENT)
    }
}

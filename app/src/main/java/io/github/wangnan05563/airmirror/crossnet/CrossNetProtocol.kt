package io.github.wangnan05563.airmirror.crossnet

import io.github.wangnan05563.airmirror.protocol.CodeAnchor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 跨网信令与打洞的线格式（PRD FS-04 第 3/4 步）。
 *
 * 为什么单独一套协议、不复用私有通道帧：信令要经过第三方信令服务转发，是无连接、可重放的一跳，
 * 只该携带「建立连接所需的候选地址与会话标识」；媒体帧的语义（画面、文件名、pts）一点都不能出现在信令里
 * （PRD FS-04 安全约束「信令只交换建立连接所需的信息」）。
 *
 * Q-4 已裁「不自建中继，只做信令 + 打洞」：因此候选类型只有 HOST / SRFLX，没有 RELAY，
 * 打洞失败只能给同网/热点替代路径，不出现「开启中继」的文案。
 */
object CrossNetProtocol {
    /** "AMCX"：与媒体帧的 "AMRC" 区分，防止信令包被误喂进私有通道解析器 */
    const val MAGIC = 0x414D4358
    const val VERSION = 1
    const val MAX_CANDIDATES = 8
    const val SESSION_BYTES = 16
    const val CODE_HASH_BYTES = 8
    const val MAX_MESSAGE = 4096

    enum class Role(val code: Int) {
        SENDER(1),
        RECEIVER(2);

        companion object {
            fun fromCode(code: Int): Role? = entries.firstOrNull { it.code == code }
        }
    }

    enum class Type(val code: Int) {
        /** 发送端提交码哈希，请求信令匹配 */
        MATCH(1),

        /** 接收端分配会话 id 并回给发送端 */
        MATCHED(2),

        /** 双方交换 HOST / SRFLX 候选地址 */
        CANDIDATES(3),

        /** 双方候选齐了，可以开始打洞 */
        READY(4),

        /** 放弃并携带原因码 */
        ABORT(5);

        companion object {
            fun fromCode(code: Int): Type? = entries.firstOrNull { it.code == code }
        }
    }

    enum class CandidateKind(val code: Int) {
        HOST(0),
        SRFLX(1);

        companion object {
            fun fromCode(code: Int): CandidateKind? = entries.firstOrNull { it.code == code }
        }
    }

    data class Candidate(
        val address: String,
        val port: Int,
        val kind: CandidateKind = CandidateKind.HOST,
        /** 越小越先尝试；HOST 默认优先于 SRFLX */
        val priority: Int = 0,
    ) {
        init {
            require(Subnet.isIpv4(address)) { "candidate address is not IPv4: $address" }
            require(port in 1..65535) { "candidate port out of range: $port" }
        }

        val id: String get() = "$address:$port"
    }

    data class Message(
        val type: Type,
        val role: Role,
        /** MATCH 阶段由发送端留空，接收端在 MATCHED 里分配 */
        val sessionId: String = "",
        val codeHash: ByteArray? = null,
        val candidates: List<Candidate> = emptyList(),
        val reason: FailureReason? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Message) return false
            return type == other.type && role == other.role && sessionId == other.sessionId &&
                sameBytes(codeHash, other.codeHash) && candidates == other.candidates &&
                reason == other.reason
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + role.hashCode()
            result = 31 * result + sessionId.hashCode()
            result = 31 * result + (codeHash?.contentHashCode() ?: 0)
            result = 31 * result + candidates.hashCode()
            result = 31 * result + (reason?.hashCode() ?: 0)
            return result
        }
    }

    fun newSessionId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(SESSION_BYTES)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    /** 服务端只看得到码的哈希，拿不到投屏码原文；哈希也不参与信令转发之外的用途。 */
    fun codeHash(credential: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest((CODE_HASH_PREFIX + credential).toByteArray(Charsets.UTF_8))
            .copyOf(CODE_HASH_BYTES)

    fun encode(message: Message): ByteArray = ByteArrayOutputStream().use { bytes ->
        val out = DataOutputStream(bytes)
        out.writeInt(MAGIC)
        out.writeByte(VERSION)
        out.writeByte(message.type.code)
        out.writeByte(message.role.code)
        out.writeByte(0)
        out.write(sessionBytes(message))
        val hash = message.codeHash
        if (hash == null) {
            out.writeByte(0)
        } else {
            require(hash.size == CODE_HASH_BYTES) { "code hash must be $CODE_HASH_BYTES bytes" }
            out.writeByte(hash.size)
            out.write(hash)
        }
        require(message.candidates.size <= MAX_CANDIDATES) {
            "too many candidates: ${message.candidates.size} > $MAX_CANDIDATES"
        }
        out.writeByte(message.candidates.size)
        message.candidates.forEach { candidate ->
            out.writeByte(candidate.kind.code)
            val address = candidate.address.toByteArray(Charsets.US_ASCII)
            out.writeByte(address.size)
            out.write(address)
            out.writeShort(candidate.port)
            out.writeInt(candidate.priority)
        }
        out.writeByte(message.reason?.code ?: 0)
        out.flush()
        val encoded = bytes.toByteArray()
        require(encoded.size <= MAX_MESSAGE) { "signal message too large: ${encoded.size}" }
        encoded
    }

    fun decode(payload: ByteArray): Message {
        require(payload.size <= MAX_MESSAGE) { "signal message too large: ${payload.size}" }
        val input = DataInputStream(ByteArrayInputStream(payload))
        val magic = input.readInt()
        require(magic == MAGIC) { "bad cross-net magic: 0x%08X".format(magic) }
        val version = input.readUnsignedByte()
        require(version == VERSION) { "unsupported cross-net version: $version" }
        val type = Type.fromCode(input.readUnsignedByte())
            ?: throw IllegalArgumentException("unknown cross-net message type")
        val role = Role.fromCode(input.readUnsignedByte())
            ?: throw IllegalArgumentException("unknown cross-net role")
        input.readUnsignedByte() // reserved
        val session = readSessionId(input)
        require(type == Type.MATCH || session.isNotEmpty()) { "${type.name} requires a session id" }
        val hashLength = input.readUnsignedByte()
        val hash = if (hashLength == 0) {
            null
        } else {
            require(hashLength == CODE_HASH_BYTES) { "bad code hash length: $hashLength" }
            ByteArray(hashLength).also { input.readFully(it) }
        }
        val count = input.readUnsignedByte()
        require(count <= MAX_CANDIDATES) { "too many candidates: $count" }
        val candidates = ArrayList<Candidate>(count)
        repeat(count) {
            val kind = CandidateKind.fromCode(input.readUnsignedByte())
                ?: throw IllegalArgumentException("unknown candidate kind")
            val addressLength = input.readUnsignedByte()
            require(addressLength in 1..15) { "bad candidate address length: $addressLength" }
            val address = String(ByteArray(addressLength).also { input.readFully(it) }, Charsets.US_ASCII)
            val port = input.readUnsignedShort()
            val priority = input.readInt()
            candidates += Candidate(address, port, kind, priority)
        }
        val reasonCode = input.readUnsignedByte()
        val reason = if (reasonCode == 0) null else FailureReason.fromCode(reasonCode)
        return Message(type, role, session, hash, candidates, reason)
    }

    private fun sessionBytes(message: Message): ByteArray {
        if (message.sessionId.isEmpty()) {
            require(message.type == Type.MATCH) { "empty session id only allowed in MATCH" }
            return ByteArray(SESSION_BYTES)
        }
        return message.sessionId.hexToBytes(SESSION_BYTES)
    }

    private fun readSessionId(input: DataInputStream): String {
        val bytes = ByteArray(SESSION_BYTES)
        input.readFully(bytes)
        return if (bytes.all { it == 0.toByte() }) "" else bytes.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(expected: Int): ByteArray {
        require(length == expected * 2) { "session id must be ${expected * 2} hex chars" }
        val out = ByteArray(expected)
        for (i in out.indices) {
            val hi = Character.digit(this[i * 2], 16)
            val lo = Character.digit(this[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "session id is not hex: $this" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun sameBytes(a: ByteArray?, b: ByteArray?): Boolean =
        a == null && b == null || a != null && b != null && a.contentEquals(b)

    private const val CODE_HASH_PREFIX = "AMCX-CODE/v1|"
}

/**
 * 打洞探针。它不是信令消息：两个端点在拿到候选后直接向对方候选地址发 UDP，
 * 探针用投屏码派生的令牌自证，避免同一端口上的任意 UDP 包把会话「打」成已连接。
 */
object PunchWire {
    /** "AMP1" */
    const val MAGIC = 0x414D5031
    const val VERSION = 1
    const val TOKEN_BYTES = 16
    const val PACKET_SIZE = 4 + 1 + 1 + CrossNetProtocol.SESSION_BYTES + 1 + TOKEN_BYTES

    data class Packet(
        val sessionId: String,
        val role: CrossNetProtocol.Role,
        val ack: Boolean,
        val token: ByteArray,
    )

    fun encode(
        sessionId: String,
        role: CrossNetProtocol.Role,
        ack: Boolean,
        credential: String,
    ): ByteArray {
        val session = sessionId.hexBytes()
        return ByteArrayOutputStream(PACKET_SIZE).use { bytes ->
            val out = DataOutputStream(bytes)
            out.writeInt(MAGIC)
            out.writeByte(VERSION)
            out.writeByte(if (ack) 1 else 0)
            out.write(session)
            out.writeByte(role.code)
            out.write(token(credential, sessionId, role, ack))
            out.flush()
            bytes.toByteArray()
        }
    }

    /** 返回 null 表示不是本会话的打洞包（格式、会话或令牌任一不符）。 */
    fun decode(payload: ByteArray, credential: String): Packet? {
        if (payload.size != PACKET_SIZE) return null
        return try {
            val input = DataInputStream(ByteArrayInputStream(payload))
            if (input.readInt() != MAGIC) return null
            if (input.readUnsignedByte() != VERSION) return null
            val ack = input.readUnsignedByte() == 1
            val sessionBytes = ByteArray(CrossNetProtocol.SESSION_BYTES).also { input.readFully(it) }
            val session = sessionBytes.hexString()
            val role = CrossNetProtocol.Role.fromCode(input.readUnsignedByte()) ?: return null
            val expected = token(credential, session, role, ack)
            val actual = ByteArray(TOKEN_BYTES).also { input.readFully(it) }
            if (!MessageDigest.isEqual(expected, actual)) return null
            Packet(session, role, ack, actual)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun token(
        credential: String,
        sessionId: String,
        role: CrossNetProtocol.Role,
        ack: Boolean,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(CodeAnchor.anchorTag(credential), "HmacSHA256"))
        val data = "punch|" + (if (ack) 1 else 0) + "|" + sessionId + "|" + role.code
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).copyOf(TOKEN_BYTES)
    }

    private fun String.hexBytes(): ByteArray {
        val expected = CrossNetProtocol.SESSION_BYTES
        require(length == expected * 2) { "session id must be ${expected * 2} hex chars" }
        val out = ByteArray(expected)
        for (i in out.indices) {
            val hi = Character.digit(this[i * 2], 16)
            val lo = Character.digit(this[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "session id is not hex: $this" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun ByteArray.hexString(): String = joinToString("") { "%02x".format(it) }
}

/**
 * IPv4 子网判定。跨网流程的第一步是「是否同网段」：同网段必须直接走局域网发现/直连，
 * 一个信令请求都不能发（PRD FS-04 验收「同网不触云」）。
 */
object Subnet {
    fun isIpv4(address: String): Boolean = octets(address) != null

    fun sameSubnet(a: String, b: String, prefixLength: Int): Boolean {
        require(prefixLength in 0..32) { "prefix length out of range: $prefixLength" }
        val left = octets(a) ?: return false
        val right = octets(b) ?: return false
        if (prefixLength == 0) return true
        val mask = (-1 shl (32 - prefixLength))
        return (left and mask) == (right and mask)
    }

    private fun octets(address: String): Int? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        var value = 0
        parts.forEach { part ->
            if (part.isEmpty() || part.length > 3) return null
            val number = part.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            value = (value shl 8) or number
        }
        return value
    }
}

/** 信令前的分流决策：同网段直接本地直连，只有跨网段才允许触碰信令服务。 */
object CrossNetPlanner {
    enum class Route { LOCAL_DIRECT, SIGNALING }

    fun route(localAddress: String, peerAddress: String, prefixLength: Int = 24): Route =
        if (Subnet.sameSubnet(localAddress, peerAddress, prefixLength)) Route.LOCAL_DIRECT else Route.SIGNALING
}

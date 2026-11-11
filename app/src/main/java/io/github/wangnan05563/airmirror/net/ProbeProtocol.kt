package io.github.wangnan05563.airmirror.net

/**
 * FR-02 异常「组播被禁用：自动降级为受控广播/直连探测」的报文层（PRD FS-07 状态与异常表）。
 *
 * 走 UDP 而不是复用 41523：探测如果去碰媒体端口，接收端看到的是一个半途关闭的 TCP 连接，
 * 而 8.9 的防滥用闸门把「握手没走完就撤」也记成一笔被拒——几轮探测下来，机主自己真投屏时
 * 反倒被自己人限进静默期。发现与数据分端口，互不记账。
 *
 * 纯 JVM 实现（不引用 android.*），报文编解码与判定都能在单测里锁死。
 */
object ProbeProtocol {
    /** 接收端在这里收探测、回应答。 */
    const val PROBE_PORT = 415_24

    /** 发送端在这里收探测应答，也收没人问就自己冒出来的周期公告。 */
    const val ANNOUNCE_PORT = 415_25

    const val MAGIC = "AMDP/1"

    /** 一条包最远距离：正常包不到两百字节，超了就是别人发坏了。 */
    const val MAX_PACKET = 2_048

    const val KIND_PROBE = "probe"
    const val KIND_ACK = "ack"
    const val KIND_HELLO = "hello"

    /** 名字与标识里出现分隔符或控制字符会破坏帧结构，出门前先按同一套规则削平。 */
    private const val MAX_TEXT = 64

    /** 一条应答里最多认几个协议：已知协议只有四个，8 足够宽松 */
    private const val MAX_PROTOCOLS = 8

    /** 单个协议名的上限：只会是 AMRC/DLNA 这类短标识 */
    private const val MAX_PROTOCOL_LENGTH = 32

    sealed class Packet {
        /** 发送端问一句「这儿有大屏吗」。nonce 用来把应答对上自己发出去的那一问。 */
        data class Probe(val nonce: String, val senderName: String) : Packet()

        /** 接收端的回答。fingerprint 是它本机的长期身份，多条通道命中靠它合并成一条。 */
        data class Reply(
            val kind: String,
            val nonce: String,
            val fingerprint: String,
            val name: String,
            val port: Int,
            val protocols: List<String>,
        ) : Packet()
    }

    fun probe(nonce: String, senderName: String): ByteArray =
        encode(listOf(MAGIC, KIND_PROBE, sanitize(nonce), hexEncode(senderName.toByteArray(Charsets.UTF_8))))

    fun ack(nonce: String, fingerprint: String, name: String, port: Int, protocols: List<String>): ByteArray =
        reply(KIND_ACK, nonce, fingerprint, name, port, protocols)

    /** 无人提问也定期发一条：路由器把组播掐了但广播还通时，发送端坐着就能收到大屏。 */
    fun hello(fingerprint: String, name: String, port: Int, protocols: List<String>): ByteArray =
        reply(KIND_HELLO, "", fingerprint, name, port, protocols)

    private fun reply(kind: String, nonce: String, fingerprint: String, name: String, port: Int, protocols: List<String>): ByteArray =
        encode(
            listOf(
                MAGIC,
                kind,
                sanitize(nonce),
                sanitize(fingerprint),
                hexEncode(name.toByteArray(Charsets.UTF_8)),
                port.toString(),
                protocols.map { sanitize(it) }.filter { it.isNotEmpty() }.joinToString(","),
            ),
        )

    /** 认不出来的一律返回 null 直接丢掉：这一路是 UDP，没有「报错让对方重试」的余地。 */
    fun parse(bytes: ByteArray): Packet? {
        if (bytes.isEmpty() || bytes.size > MAX_PACKET) return null
        val fields = String(bytes, Charsets.US_ASCII).split('|')
        if (fields.firstOrNull() != MAGIC) return null
        return when (fields.getOrNull(1)) {
            KIND_PROBE -> {
                if (fields.size != 4) return null
                val name = hexDecodeOrNull(fields[3]) ?: return null
                Packet.Probe(nonce = fields[2], senderName = name)
            }

            KIND_ACK, KIND_HELLO -> {
                if (fields.size != 7) return null
                val name = hexDecodeOrNull(fields[4]) ?: return null
                val port = fields[5].toIntOrNull() ?: return null
                val fingerprint = fields[3]
                if (fingerprint.isEmpty() || port !in 1..65_535) return null
                Packet.Reply(
                    kind = fields[1],
                    nonce = fields[2],
                    fingerprint = fingerprint,
                    name = name,
                    port = port,
                    // 协议串来自任意 UDP 包：数量与单项长度都要封顶，否则一条包就能塞进上千条，
                    // 而它们会被并进设备列表并长期保留
                    protocols = fields[6].split(',')
                        .filter { it.isNotEmpty() }
                        .take(MAX_PROTOCOLS)
                        .map { it.take(MAX_PROTOCOL_LENGTH) },
                )
            }

            else -> null
        }
    }

    private fun sanitize(value: String): String =
        value.filter { !it.isWhitespace() && it != '|' && it.code >= 32 && it.code != 127 }.take(MAX_TEXT)

    private fun encode(fields: List<String>): ByteArray =
        fields.joinToString("|").toByteArray(Charsets.US_ASCII)

    internal fun hexEncode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(HEX[(b.toInt() shr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
        }
        return out.toString()
    }

    internal fun hexDecodeOrNull(value: String): String? {
        if (value.length % 2 != 0) return null
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val high = HEX.indexOf(value[i * 2])
            val low = HEX.indexOf(value[i * 2 + 1])
            if (high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    private const val HEX = "0123456789abcdef"
}

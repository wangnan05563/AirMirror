package io.github.wangnan05563.airmirror.protocol

import io.github.wangnan05563.airmirror.pairing.PairingLink

/**
 * CONTROL 帧里的会话指令（PRD FS-09）：与画质上限共用帧类型，用 `op` 区分。
 * 老版本收到不认识的 `op` 直接忽略，缺字段按空/否处理，不会误判成上限。
 */
object SessionControl {
    const val OP_PAUSE = "pause"
    const val OP_RESUME = "resume"
    const val OP_TAKEOVER = "takeover"
    const val OP_TAKEOVER_RESULT = "takeover_result"

    private const val KEY_OP = "op"
    private const val KEY_FROM = "from"
    private const val KEY_ALLOW = "allow"

    data class Signal(val op: String, val from: String, val allow: Boolean) {
        val isEmpty: Boolean get() = op.isEmpty()
    }

    fun pause(): ByteArray = encode(Signal(OP_PAUSE, "", false))

    fun resume(): ByteArray = encode(Signal(OP_RESUME, "", false))

    fun takeover(from: String): ByteArray = encode(Signal(OP_TAKEOVER, from, false))

    fun takeoverResult(allow: Boolean): ByteArray = encode(Signal(OP_TAKEOVER_RESULT, "", allow))

    fun encode(signal: Signal): ByteArray = buildString {
        append(KEY_OP).append('=').append(PairingLink.escape(signal.op))
        append('&').append(KEY_FROM).append('=').append(PairingLink.escape(signal.from))
        append('&').append(KEY_ALLOW).append('=').append(if (signal.allow) '1' else '0')
    }.toByteArray(Charsets.UTF_8)

    fun decode(payload: ByteArray): Signal {
        val text = String(payload, Charsets.UTF_8)
        val fields = HashMap<String, String>(3)
        for (part in text.split('&')) {
            if (!part.contains('=')) continue
            fields[part.substringBefore('=')] = PairingLink.unescape(part.substringAfter('='))
        }
        return Signal(
            op = fields[KEY_OP].orEmpty(),
            from = fields[KEY_FROM].orEmpty(),
            allow = fields[KEY_ALLOW] == "1",
        )
    }
}

package io.github.wangnan05563.airmirror.protocol

import io.github.wangnan05563.airmirror.pairing.PairingLink

/**
 * CONTROL 帧里的「接收端画质上限」：授权通过后由接收端主动下发，发送端据此选档，
 * 而不是按本机屏幕原样硬推（PRD 8.4 能力协商 + 8.7 档位表）。
 *
 * 载荷沿用 `k=v&k=v` 口径，缺字段按 0 处理，老版本发端收到不认识的控制内容直接忽略。
 */
object Limit {
    private const val KEY_WIDTH = "w"
    private const val KEY_HEIGHT = "h"
    private const val KEY_FPS = "fps"
    private const val KEY_BITRATE = "br"
    private const val KEY_TIER = "tier"

    data class Ceiling(val width: Int, val height: Int, val fps: Int, val bitRateKbps: Int, val tierLabel: String) {
        val isEmpty: Boolean get() = width <= 0 || height <= 0

        /** 源画面能否原样送进这个上限（长边比较，竖屏同理） */
        fun covers(sourceWidth: Int, sourceHeight: Int): Boolean =
            maxOf(sourceWidth, sourceHeight) <= maxOf(width, height)
    }

    fun empty(): Ceiling = Ceiling(0, 0, 0, 0, "")

    fun encode(ceiling: Ceiling): ByteArray = buildString {
        append(KEY_WIDTH).append('=').append(ceiling.width)
        append('&').append(KEY_HEIGHT).append('=').append(ceiling.height)
        append('&').append(KEY_FPS).append('=').append(ceiling.fps)
        append('&').append(KEY_BITRATE).append('=').append(ceiling.bitRateKbps)
        append('&').append(KEY_TIER).append('=').append(PairingLink.escape(ceiling.tierLabel))
    }.toByteArray(Charsets.UTF_8)

    fun decode(payload: ByteArray): Ceiling {
        val text = String(payload, Charsets.UTF_8)
        val fields = HashMap<String, String>(5)
        for (part in text.split('&')) {
            val key = part.substringBefore('=', "")
            if (part.contains('=')) fields[key] = PairingLink.unescape(part.substringAfter('='))
        }
        return Ceiling(
            fields[KEY_WIDTH].parseInt(),
            fields[KEY_HEIGHT].parseInt(),
            fields[KEY_FPS].parseInt(),
            fields[KEY_BITRATE].parseInt(),
            fields[KEY_TIER].orEmpty(),
        )
    }

    private fun String?.parseInt(): Int = this?.toIntOrNull() ?: 0
}

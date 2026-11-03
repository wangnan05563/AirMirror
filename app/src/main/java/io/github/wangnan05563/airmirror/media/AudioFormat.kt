package io.github.wangnan05563.airmirror.media

import io.github.wangnan05563.airmirror.pairing.PairingLink

/**
 * 镜像音频的线上格式（PRD FR-09「音频单独编码并与视频时间戳对齐」、8.4 私有通道）。
 *
 * 载荷沿用 [io.github.wangnan05563.airmirror.protocol.Limit] 的 `k=v&k=v` 口径：
 * 老接收端认不出这条 CONFIG 就整条忽略，画面不受影响，不会把镜像会话带崩。
 */
object AudioFormat {
    /** 48kHz 立体声：系统音频采集的原生水位，重采样只会给同步添误差 */
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BIT_RATE_KBPS = 128

    /** 一个 AAC 访问单元固定 1024 个样本，48kHz 下约 21.3ms，是时间戳与追帧计量的最小格子 */
    const val SAMPLES_PER_FRAME = 1024

    const val CODEC_AAC_LC = "aac-lc"

    /** 一帧 PCM 占多少字节（16-bit 交织采样） */
    fun pcmBytes(samples: Int, channels: Int = CHANNELS): Int = samples * channels * 2

    /** 一帧 AAC 的时长（微秒），发送端与接收端都按它推时间戳 */
    fun frameUs(sampleRate: Int = SAMPLE_RATE): Long = SAMPLES_PER_FRAME * 1_000_000L / sampleRate

    /**
     * 按已喂进去的样本数算时间戳（与画面同一 nanoTime 基准，FR-09「与视频时间戳对齐」的前提）。
     * 用采集时刻逐个打反而不准：一次 read 拿到的是几百毫秒的样本，帧内每一帧都得按样本数偏移。
     */
    fun ptsUs(startUs: Long, samplesFed: Long, sampleRate: Int = SAMPLE_RATE): Long =
        startUs + samplesFed * 1_000_000L / sampleRate

    data class Spec(val sampleRate: Int, val channels: Int, val bitRateKbps: Int, val codec: String) {
        val isUsable: Boolean get() = sampleRate > 0 && channels in 1..2 && codec == CODEC_AAC_LC && csd() != null

        /** 解码器要的 AudioSpecificConfig；解不出来说明这套参数我们建不起解码器。 */
        fun csd(): ByteArray? = audioSpecificConfig(sampleRate, channels)
    }

    /**
     * AAC-LC 的 2 字节 AudioSpecificConfig：`[5b objectType][4b 采样率索引][4b 声道配置][3b 保留 0]`。
     *
     * 发送端发的是不带 ADTS 头的裸访问单元，MediaCodec 的 AAC 解码器没有 csd-0 就认不出帧边界，
     * 会一路报 `0x1001 substituting silence`——只听不到声音，看不出报错，所以必须在这里补齐。
     * 编码档位由发送端固定为 AACObjectLC，配置块完全由采样率与声道数决定，不需要把编码器的输出再传一遍。
     */
    fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray? {
        val rateIndex = SAMPLE_RATES.indexOf(sampleRate)
        if (rateIndex < 0 || channels !in 1..2) return null
        val bits = (AAC_OBJECT_AAC_LC shl 11) or (rateIndex shl 7) or (channels shl 3)
        return byteArrayOf((bits shr 8).toByte(), (bits and 0xFF).toByte())
    }

    fun default(): Spec = Spec(SAMPLE_RATE, CHANNELS, BIT_RATE_KBPS, CODEC_AAC_LC)

    private const val AAC_OBJECT_AAC_LC = 2

    /** ISO/IEC 14496-3 的采样率索引表，顺序即索引 */
    private val SAMPLE_RATES = intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000)

    private const val KEY_RATE = "sr"
    private const val KEY_CHANNELS = "ch"
    private const val KEY_BITRATE = "br"
    private const val KEY_CODEC = "enc"

    fun encode(spec: Spec): ByteArray = buildString {
        append(KEY_RATE).append('=').append(spec.sampleRate)
        append('&').append(KEY_CHANNELS).append('=').append(spec.channels)
        append('&').append(KEY_BITRATE).append('=').append(spec.bitRateKbps)
        append('&').append(KEY_CODEC).append('=').append(PairingLink.escape(spec.codec))
    }.toByteArray(Charsets.UTF_8)

    /** 解不出可用规格回 null：不能拿默认值瞎起 AudioTrack，那会把整条会话的音频变成噪音。 */
    fun decode(payload: ByteArray): Spec? {
        val fields = HashMap<String, String>(4)
        for (part in String(payload, Charsets.UTF_8).split('&')) {
            if (part.contains('=')) {
                fields[part.substringBefore('=')] = PairingLink.unescape(part.substringAfter('='))
            }
        }
        val spec = Spec(
            fields[KEY_RATE]?.toIntOrNull() ?: 0,
            fields[KEY_CHANNELS]?.toIntOrNull() ?: 0,
            fields[KEY_BITRATE]?.toIntOrNull() ?: 0,
            fields[KEY_CODEC].orEmpty(),
        )
        return spec.takeIf { it.isUsable }
    }
}

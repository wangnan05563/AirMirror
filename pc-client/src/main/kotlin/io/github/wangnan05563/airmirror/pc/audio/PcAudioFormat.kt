package io.github.wangnan05563.airmirror.pc.audio

/**
 * PC 发送端音频线上格式（PRD FR-09「音频单独编码并与视频时间戳对齐」）。
 *
 * 与 Android `media/AudioFormat.kt` 的键名/取值保持一致：接收端只认 `sr/ch/br/enc` 四个键，
 * 载荷是不带 ADTS 头的裸 AAC 访问单元，解码器所需的 AudioSpecificConfig 由参数算出来。
 */
object PcAudioFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BIT_RATE_KBPS = 128
    const val SAMPLES_PER_FRAME = 1_024
    const val CODEC_AAC_LC = "aac-lc"

    /** 一帧 16-bit 交织 PCM 的字节数：1024 * 2ch * 2B = 4096。 */
    const val FRAME_BYTES = SAMPLES_PER_FRAME * CHANNELS * 2

    /** 一个 AAC 访问单元的时长（微秒）；48kHz 下约 21.3ms。 */
    fun frameUs(sampleRate: Int = SAMPLE_RATE): Long =
        SAMPLES_PER_FRAME * 1_000_000L / sampleRate

    /** 按已编码帧数推时间戳，与视频共用同一会话起点（FR-09 对齐前提）。 */
    fun ptsUs(frameIndex: Long, sampleRate: Int = SAMPLE_RATE): Long =
        frameIndex * SAMPLES_PER_FRAME * 1_000_000L / sampleRate

    /** AAC-LC 的 2 字节 AudioSpecificConfig；算法与 Android 端完全一致。 */
    fun audioSpecificConfig(sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): ByteArray? {
        val rateIndex = SAMPLE_RATES.indexOf(sampleRate)
        if (rateIndex < 0 || channels !in 1..2) return null
        val bits = (AAC_OBJECT_AAC_LC shl 11) or (rateIndex shl 7) or (channels shl 3)
        return byteArrayOf((bits shr 8).toByte(), (bits and 0xFF).toByte())
    }

    /** CONFIG 帧载荷；键顺序固定，便于抓包对照。 */
    fun encode(
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bitRateKbps: Int = BIT_RATE_KBPS,
        codec: String = CODEC_AAC_LC,
    ): ByteArray = "sr=$sampleRate&ch=$channels&br=$bitRateKbps&enc=$codec"
        .toByteArray(Charsets.UTF_8)

    private const val AAC_OBJECT_AAC_LC = 2

    private val SAMPLE_RATES = intArrayOf(
        96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000,
    )
}

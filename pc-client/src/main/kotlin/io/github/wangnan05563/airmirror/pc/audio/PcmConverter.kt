package io.github.wangnan05563.airmirror.pc.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * WASAPI 回环格式 → 48kHz 双声道 16-bit 交织 PCM。
 *
 * Windows 共享模式的混音格式通常是 48kHz/双声道/float32，但设备也可能给 44.1kHz、单声道或 16-bit；
 * 这里统一做声道映射、线性重采样与量化，保证送进编码器的永远是固定格式。
 * 只做逐块转换：块边界用上一块尾样本夹住，不引入额外延迟。
 */
class PcmConverter(private val input: WasapiLoopbackCapture.Format) {
    data class Output(val pcm: ByteArray, val frames: Int)

    /** [volumePercent] 0..100；0 只表示增益为零，是否发送由上层静音开关决定。 */
    fun convert(bytes: ByteArray, volumePercent: Int = 100, outputRate: Int = PcAudioFormat.SAMPLE_RATE): Output {
        if (bytes.isEmpty() || input.blockAlign <= 0) return Output(ByteArray(0), 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val inFrames = bytes.size / input.blockAlign
        if (inFrames <= 0) return Output(ByteArray(0), 0)
        val outFrames = (inFrames.toLong() * outputRate / input.sampleRate).toInt().coerceAtLeast(1)
        val gain = volumePercent.coerceIn(0, 100) / 100f
        val out = ByteArray(outFrames * 4)
        val outBuffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        for (frame in 0 until outFrames) {
            val src = frame.toDouble() * input.sampleRate / outputRate
            val base = floor(src).toInt().coerceIn(0, inFrames - 1)
            val next = (base + 1).coerceAtMost(inFrames - 1)
            val mix = (src - base).toFloat()
            val left = sample(buffer, base, 0) * (1f - mix) + sample(buffer, next, 0) * mix
            val right = if (input.channels >= 2) {
                sample(buffer, base, 1) * (1f - mix) + sample(buffer, next, 1) * mix
            } else {
                left
            }
            outBuffer.putShort(quantize(left * gain))
            outBuffer.putShort(quantize(right * gain))
        }
        return Output(out, outFrames)
    }

    private fun sample(buffer: ByteBuffer, frame: Int, channel: Int): Float {
        val offset = frame * input.blockAlign + channel * (input.bitsPerSample / 8)
        return when {
            input.isFloat && input.bitsPerSample == 32 -> buffer.getFloat(offset)
            input.isFloat && input.bitsPerSample == 64 -> buffer.getDouble(offset).toFloat()
            input.bitsPerSample == 16 -> buffer.getShort(offset) / 32768f
            input.bitsPerSample == 24 -> {
                val lo = buffer.get(offset).toInt() and 0xFF
                val mid = buffer.get(offset + 1).toInt() and 0xFF
                val hi = buffer.get(offset + 2).toInt()
                ((hi shl 16) or (mid shl 8) or lo) / 8388608f
            }
            input.bitsPerSample == 32 -> buffer.getInt(offset) / 2147483648f
            else -> 0f
        }
    }

    private fun quantize(value: Float): Short {
        val scaled = (value.coerceIn(-1f, 1f) * 32768f).roundToInt()
        return scaled.coerceIn(-32768, 32767).toShort()
    }
}

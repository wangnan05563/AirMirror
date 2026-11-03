package io.github.wangnan05563.airmirror.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 发送端 AAC 硬编码（PRD 8.4 附录「音频单独编码并与视频时间戳对齐」）。
 *
 * 输入是 16-bit 交织 PCM，输出是裸访问单元（不带 ADTS）：接收端按 CONFIG 帧里的
 * 采样率/声道数补上 csd-0 再建解码器，省掉每帧 7 个字节的 ADTS 头。
 * 编码器自己的配置块不发——那两个字节能由参数算出来，见 [AudioFormat.audioSpecificConfig]。
 *
 * 时间戳不取采集时刻：一次 `AudioRecord.read` 拿到的是几百毫秒的样本，帧内每个访问单元
 * 都得按样本数往后偏，所以这里自己数样本，起点由调用方给（与画面同一条 nanoTime 基准）。
 */
class AudioEncoder(
    private val spec: AudioFormat.Spec,
    private val startPtsUs: Long,
) {
    data class Encoded(val data: ByteArray, val ptsUs: Long)

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val frameBytes = AudioFormat.pcmBytes(AudioFormat.SAMPLES_PER_FRAME, spec.channels)
    private val carry = ByteArrayOutputStream(frameBytes * 2)
    private var carried = 0
    private var samplesFed = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, spec.sampleRate, spec.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitRateKbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** 喂一段 PCM；攒够整帧才送进编码器，返回这次能取走的访问单元。 */
    fun feed(pcm: ByteArray): List<Encoded> {
        val frames = ArrayList<Encoded>(2)
        var offset = 0
        while (offset < pcm.size) {
            if (carried == 0 && pcm.size - offset >= frameBytes) {
                queue(pcm, offset)
                offset += frameBytes
                continue
            }
            val take = minOf(frameBytes - carried, pcm.size - offset)
            carry.write(pcm, offset, take)
            carried += take
            offset += take
            if (carried == frameBytes) {
                val frame = carry.toByteArray()
                carry.reset()
                carried = 0
                queue(frame, 0)
            }
        }
        frames += drain()
        return frames
    }

    private fun queue(data: ByteArray, offset: Int) {
        val index = codec.dequeueInputBuffer(QUEUE_TIMEOUT_US)
        if (index < 0) return
        val buffer = inputBuffer(index)
        buffer.clear()
        buffer.put(data, offset, frameBytes)
        codec.queueInputBuffer(index, 0, frameBytes, AudioFormat.ptsUs(startPtsUs, samplesFed, spec.sampleRate), 0)
        samplesFed += AudioFormat.SAMPLES_PER_FRAME
    }

    private fun drain(): List<Encoded> {
        val result = ArrayList<Encoded>(2)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return result
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val buffer = outputBuffer(index)
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data, 0, info.size)
                    codec.releaseOutputBuffer(index, false)
                    // 编码器的配置块不发：接收端按 CONFIG 帧里的描述自己建解码格式
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        result.add(Encoded(data, info.presentationTimeUs))
                    }
                }
            }
        }
    }

    private fun inputBuffer(index: Int): ByteBuffer =
        if (Build.VERSION.SDK_INT >= 21) codec.getInputBuffer(index)!! else codec.inputBuffers[index]

    private fun outputBuffer(index: Int): ByteBuffer =
        if (Build.VERSION.SDK_INT >= 21) codec.getOutputBuffer(index)!! else codec.outputBuffers[index]

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val QUEUE_TIMEOUT_US = 1_000L
    }
}

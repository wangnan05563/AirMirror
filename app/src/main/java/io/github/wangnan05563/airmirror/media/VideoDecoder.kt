package io.github.wangnan05563.airmirror.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer

/** 接收端 H.264 硬解码，输出到 [Surface]。 */
class VideoDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0

    /** 摘 Surface 与收流线程并发：界面已销毁后仍可能拿到旧引用，此时喂帧要静默跳过而不是抛「codec is released」。 */
    @Volatile
    private var released = false

    @Volatile
    var renderedFrames = 0L
        private set

    /** 用发送端的 SPS/PPS（内含宽高）配置解码器。 */
    fun configure(configPayload: ByteArray) {
        if (released) return
        val config = H264.unpackConfig(configPayload)
        width = config.width
        height = config.height
        val units = H264.splitAnnexB(H264.normalize(config.annexB))
        require(units.isNotEmpty()) { "empty codec config" }
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(units[0]))
            if (units.size > 1) setByteBuffer("csd-1", ByteBuffer.wrap(units[1]))
        }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        decoder.configure(format, surface, null, 0)
        decoder.start()
        codec = decoder
    }

    /** 解码器只认宽高非零，配置前用占位值；真实尺寸由 SPS 决定。 */
    fun setExpectedSize(w: Int, h: Int) {
        width = w
        height = h
    }

    fun decode(data: ByteArray, ptsUs: Long) {
        if (released) return
        val decoder = codec ?: return
        val index = decoder.dequeueInputBuffer(10_000)
        if (index < 0) return
        val buffer = inputBuffer(decoder, index)
        buffer.clear()
        buffer.put(data)
        decoder.queueInputBuffer(index, 0, data.size, ptsUs, 0)
        drain(decoder)
    }

    fun drain() {
        if (released) return
        codec?.let { drain(it) }
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    decoder.releaseOutputBuffer(index, true)
                    renderedFrames++
                }
            }
        }
    }

    private fun inputBuffer(decoder: MediaCodec, index: Int): ByteBuffer =
        if (Build.VERSION.SDK_INT >= 21) decoder.getInputBuffer(index)!! else decoder.inputBuffers[index]

    fun release() {
        released = true
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
    }
}

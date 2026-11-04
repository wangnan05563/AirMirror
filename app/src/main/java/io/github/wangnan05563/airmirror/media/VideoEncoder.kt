package io.github.wangnan05563.airmirror.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 发送端硬编码：MediaProjection 的 VirtualDisplay 直接渲染到 [inputSurface]。
 *
 * 编码由 [codec] 决定（PRD 8.7「H.265 优先，H.264 备选」，协商见 [CodecChoice]）。
 * 两个 codec 都按 PRD 要求关掉 B 帧、GOP 控制在 1 秒内：B 帧会让解码端必须攒够
 * 参考帧才能出画，首帧与恢复速度都被拖长；GOP 超过 1 秒则一次丢帧后要等很久才等到 IDR。
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitRate: Int = 8_000_000,
    val codec: VideoCodec = VideoCodec.AVC,
) {
    data class Encoded(val data: ByteArray, val ptsUs: Long, val keyFrame: Boolean, val config: Boolean)

    private val tag get() = "AirMirrorCapture"
    private val codecInstance: MediaCodec = MediaCodec.createEncoderByType(codec.mimeType)
    private var configSent = false

    /** 这个编码器实际接受的低延迟参数集，写日志时要说清 B 帧到底关没关 */
    private var bFramesDisabled = false

    val inputSurface: Surface

    init {
        val guard = if (Build.VERSION.SDK_INT >= 21) 0 else null
        try {
            codecInstance.configure(newFormat(guard), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            bFramesDisabled = guard != null
        } catch (e: IllegalArgumentException) {
            // 有的厂商编码器对不认识的键直接抛。关掉 B 帧是低延迟要求，但不值得为它把投屏弄断：
            // 摘掉这个键重配一次（同一实例在 start 之前允许重复 configure）
            Log.w(tag, "encoder ${codecInstance.name} rejected max-bframes=0 (${e.javaClass.simpleName}: ${e.message}), retry without it")
            codecInstance.configure(newFormat(null), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codecInstance.createInputSurface()
        codecInstance.start()
        Log.i(tag, "encoder up ${codec.wire} ${width}x$height@$fps gop=1s bFrames=${if (bFramesDisabled) "off" else "unknown"}")
    }

    /** [maxBFrames] 为 null 表示不带 B 帧键：老系统没有这个键，或厂商编码器认它就直接抛。 */
    private fun newFormat(maxBFrames: Int?): MediaFormat =
        MediaFormat.createVideoFormat(codec.mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // GOP 控制在 1 秒内（PRD 8.7）：超过一秒，丢帧后要等很久才等到 IDR
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (maxBFrames != null) setInteger(MediaFormat.KEY_MAX_B_FRAMES, maxBFrames)
        }

    fun requestKeyFrame() {
        val params = android.os.Bundle()
        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        codecInstance.setParameters(params)
    }

    /** 取出当前可用的编码输出；没有则返回空列表。 */
    fun drain(): List<Encoded> {
        val result = ArrayList<Encoded>(2)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codecInstance.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return result

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!configSent) {
                        val annexB = configFromFormat(codecInstance.outputFormat)
                        if (annexB != null) {
                            result.add(Encoded(H264.packConfig(width, height, annexB), 0L, keyFrame = true, config = true))
                            configSent = true
                        }
                    }
                }

                index >= 0 -> {
                    val buffer = outputBuffer(index)
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data, 0, info.size)
                    codecInstance.releaseOutputBuffer(index, false)
                    if (info.size > 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (!configSent) {
                                result.add(
                                    Encoded(
                                        H264.packConfig(width, height, H264.normalize(data)),
                                        info.presentationTimeUs,
                                        keyFrame = true,
                                        config = true,
                                    ),
                                )
                                configSent = true
                            }
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            result.add(Encoded(H264.normalize(data), info.presentationTimeUs, key, config = false))
                        }
                    }
                }
            }
        }
    }

    /**
     * 配置块：H.264 是 SPS+PPS，H.265 是 VPS+SPS+PPS。
     * 每个 csd 缓冲都单独加上起始码，接收端按 NAL 类型重新归位，
     * 不把三段裸字节首尾相接——那样解码端拆不出边界，只会配得上却一帧都解不出来。
     */
    private fun configFromFormat(format: MediaFormat): ByteArray? {
        val raws = listOf("csd-0", "csd-1", "csd-2").mapNotNull { format.getByteBuffer(it)?.toByteArray() }
        return when {
            raws.isEmpty() -> null
            codec === VideoCodec.HEVC -> H265.packCsd(raws)
            else -> raws.reduce { a, b -> a + b }
        }
    }

    private fun outputBuffer(index: Int): ByteBuffer =
        if (Build.VERSION.SDK_INT >= 21) codecInstance.getOutputBuffer(index)!! else codecInstance.outputBuffers[index]

    fun release() {
        runCatching { codecInstance.stop() }
        runCatching { codecInstance.release() }
    }
}

internal fun ByteBuffer.toByteArray(): ByteArray {
    val duplicate = duplicate()
    val data = ByteArray(duplicate.remaining())
    duplicate.get(data)
    return data
}

package io.github.wangnan05563.airmirror.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 发送端 H.264 硬编码：MediaProjection 的 VirtualDisplay 直接渲染到 [inputSurface]。
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitRate: Int = 8_000_000,
) {
    data class Encoded(val data: ByteArray, val ptsUs: Long, val keyFrame: Boolean, val config: Boolean)

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var configSent = false

    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    fun requestKeyFrame() {
        val params = android.os.Bundle()
        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        codec.setParameters(params)
    }

    /** 取出当前可用的编码输出；没有则返回空列表。 */
    fun drain(): List<Encoded> {
        val result = ArrayList<Encoded>(2)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return result

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!configSent) {
                        val csd = readCsd(codec.outputFormat)
                        if (csd != null) {
                            result.add(Encoded(H264.packConfig(width, height, csd), 0L, keyFrame = true, config = true))
                            configSent = true
                        }
                    }
                }

                index >= 0 -> {
                    val buffer = outputBuffer(index)
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data, 0, info.size)
                    codec.releaseOutputBuffer(index, false)
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

    private fun readCsd(format: MediaFormat): ByteArray? {
        val sps = format.getByteBuffer("csd-0") ?: return null
        val pps = format.getByteBuffer("csd-1")
        val spsBytes = sps.toByteArray()
        if (pps == null) return spsBytes
        return spsBytes + pps.toByteArray()
    }

    private fun outputBuffer(index: Int): ByteBuffer =
        if (Build.VERSION.SDK_INT >= 21) codec.getOutputBuffer(index)!! else codec.outputBuffers[index]

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

internal fun ByteBuffer.toByteArray(): ByteArray {
    val duplicate = duplicate()
    val data = ByteArray(duplicate.remaining())
    duplicate.get(data)
    return data
}

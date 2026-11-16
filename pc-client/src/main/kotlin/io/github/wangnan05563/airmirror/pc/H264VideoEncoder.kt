package io.github.wangnan05563.airmirror.pc

import org.jcodec.codecs.h264.H264Encoder
import org.jcodec.common.VideoEncoder
import org.jcodec.common.model.ColorSpace
import org.jcodec.scale.AWTUtil
import java.awt.image.BufferedImage
import java.nio.ByteBuffer

/**
 * 纯 Java H.264 编码（JCodec）。首版用于打通 PC → Android 单链路；
 * JCodec 720p 实测约 5–8 fps，达不到 1080p30，后续必须替换为 Media Foundation 硬编。
 *
 * 输出按 NAL 拆分：SPS/PPS 作为 CONFIG 帧只发一次；IDR/P 帧作为 VIDEO 帧发送。
 */
class H264VideoEncoder(
    private val width: Int,
    private val height: Int,
    keyInterval: Int = 30,
) {
    data class Encoded(
        val config: ByteArray?,
        val payload: ByteArray,
        val keyFrame: Boolean,
    )

    private data class Nal(val type: Int, val bytes: ByteArray)

    private val encoder = H264Encoder.createH264Encoder().apply {
        setKeyInterval(keyInterval)
    }
    private val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
    private var lastConfig: ByteArray? = null
    private var forceIdr = false

    fun requestKeyFrame() {
        forceIdr = true
    }

    fun encode(image: BufferedImage): Encoded {
        require(image.width == width && image.height == height) {
            "expected ${width}x$height, got ${image.width}x${image.height}"
        }
        buffer.clear()
        val picture = AWTUtil.fromBufferedImage(image, ColorSpace.YUV420J)
        val encoded = if (forceIdr) {
            forceIdr = false
            VideoEncoder.EncodedFrame(encoder.encodeIDRFrame(picture, buffer), true)
        } else {
            encoder.encodeFrame(picture, buffer)
        }
        val data = ByteArray(encoded.data.remaining())
        encoded.data.get(data)
        val nals = splitAnnexB(data)
        val sps = nals.firstOrNull { it.type == 7 }?.bytes
        val pps = nals.firstOrNull { it.type == 8 }?.bytes
        val config = if (sps != null && pps != null) packConfig(sps + pps) else null
        val out = java.io.ByteArrayOutputStream()
        for (nal in nals) {
            if (nal.type != 7 && nal.type != 8) out.write(nal.bytes)
        }
        val payload = out.toByteArray()
        val changed = config != null && !config.contentEquals(lastConfig ?: ByteArray(0))
        if (changed) lastConfig = config
        return Encoded(
            config = if (changed) config else null,
            payload = payload,
            keyFrame = encoded.isKeyFrame,
        )
    }

    fun reset() {
        lastConfig = null
    }

    /**
     * 对端 `H264.unpackConfig` 的格式是「width(u16) + height(u16) + Annex-B SPS/PPS」；
     * 只发裸 SPS/PPS 会被对端把前 4 字节当成宽高，解码器配置成错误尺寸后永远不输出画面。
     */
    private fun packConfig(annexB: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(annexB.size + 4)
        out.write((width shr 8) and 0xFF)
        out.write(width and 0xFF)
        out.write((height shr 8) and 0xFF)
        out.write(height and 0xFF)
        out.write(annexB)
        return out.toByteArray()
    }

    private fun splitAnnexB(data: ByteArray): List<Nal> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts += i
                    i += 3
                    continue
                }
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts += i
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return emptyList()
        val nals = ArrayList<Nal>(starts.size)
        for ((index, start) in starts.withIndex()) {
            val end = if (index + 1 < starts.size) starts[index + 1] else data.size
            val header = start + if (data[start + 2] == 1.toByte()) 3 else 4
            if (header >= end) continue
            nals += Nal(data[header].toInt() and 0x1f, data.copyOfRange(start, end))
        }
        return nals
    }
}

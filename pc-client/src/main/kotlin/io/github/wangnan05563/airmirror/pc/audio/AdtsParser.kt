package io.github.wangnan05563.airmirror.pc.audio

import java.io.ByteArrayOutputStream

/**
 * 剥掉 FFmpeg 输出的 ADTS 头，只留 Android MediaCodec 能直接吃的裸 AAC 访问单元。
 *
 * FFmpeg 的 `-f adts` 会连续写多个帧，管道读到的块边界与帧边界不重合，
 * 所以这里自己攒缓冲并按 `frame_length` 切；残帧留到下一次 feed。
 */
class AdtsParser {
    private val buffer = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): List<ByteArray> {
        buffer.write(bytes)
        val data = buffer.toByteArray()
        val units = ArrayList<ByteArray>(2)
        var offset = 0
        while (true) {
            if (data.size - offset < 7) break
            if (!isSync(data, offset)) {
                offset++
                continue
            }
            val frameLength = ((data[offset + 3].toInt() and 0x03) shl 11) or
                ((data[offset + 4].toInt() and 0xFF) shl 3) or
                ((data[offset + 5].toInt() and 0xFF) shr 5)
            if (frameLength < 7 || offset + frameLength > data.size) break
            val header = if (data[offset + 1].toInt() and 0x01 != 0) 7 else 9
            val payloadSize = frameLength - header
            if (payloadSize > 0) units += data.copyOfRange(offset + header, offset + frameLength)
            offset += frameLength
        }
        buffer.reset()
        if (offset < data.size) buffer.write(data, offset, data.size - offset)
        return units
    }

    fun reset() = buffer.reset()

    private fun isSync(data: ByteArray, offset: Int): Boolean =
        (data[offset].toInt() and 0xFF) == 0xFF && (data[offset + 1].toInt() and 0xF6) == 0xF0
}

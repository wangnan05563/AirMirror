package io.github.wangnan05563.airmirror.media

/** H.264 Annex-B 工具：从编码器配置帧里拆出 SPS / PPS 供解码端设置 csd。 */
object H264 {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    data class Config(val width: Int, val height: Int, val annexB: ByteArray)

    /** 配置帧格式：width(u16) height(u16) + Annex-B 的 SPS/PPS，避免解码端猜分辨率。 */
    fun packConfig(width: Int, height: Int, annexB: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(annexB.size + 4)
        out.write((width ushr 8) and 0xFF)
        out.write(width and 0xFF)
        out.write((height ushr 8) and 0xFF)
        out.write(height and 0xFF)
        out.write(annexB)
        return out.toByteArray()
    }

    fun unpackConfig(payload: ByteArray): Config {
        require(payload.size > 4) { "config payload too short" }
        val width = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val height = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        return Config(width, height, payload.copyOfRange(4, payload.size))
    }

    /** 返回各 NAL 的完整字节（含起始码）。 */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts.add(i)
                    i += 3
                    continue
                }
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts.add(i)
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return listOf(data)
        val nalUnits = ArrayList<ByteArray>(starts.size)
        for (index in starts.indices) {
            val end = if (index + 1 < starts.size) starts[index + 1] else data.size
            nalUnits.add(data.copyOfRange(starts[index], end))
        }
        return nalUnits
    }

    /** 把 Annex-B 起始码统一成 4 字节，便于解码端直接喂入。 */
    fun normalize(data: ByteArray): ByteArray {
        val units = splitAnnexB(data)
        val out = java.io.ByteArrayOutputStream(data.size + units.size * 4)
        for (unit in units) {
            var offset = 0
            while (offset + 3 < unit.size && unit[offset] == 0.toByte() && unit[offset + 1] == 0.toByte()) {
                if (unit[offset + 2] == 1.toByte()) {
                    offset += 3
                    break
                }
                if (unit[offset + 2] == 0.toByte() && unit[offset + 3] == 1.toByte()) {
                    offset += 4
                    break
                }
                offset++
            }
            out.write(START_CODE)
            out.write(unit, offset, unit.size - offset)
        }
        return out.toByteArray()
    }
}

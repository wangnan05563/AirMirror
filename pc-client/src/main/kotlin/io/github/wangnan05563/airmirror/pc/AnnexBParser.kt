package io.github.wangnan05563.airmirror.pc

import java.io.ByteArrayOutputStream

/**
 * Annex-B 码流解析：按 AUD（NAL type 9）切分访问单元（access unit）。
 *
 * FFmpeg 输出端通过 `h264_metadata=aud=insert` 强制在每帧前插入 AUD，
 * 因此这里只需要按 AUD 边界切分；一个访问单元内的 SPS/PPS（NAL 7/8）
 * 单独抽为 CONFIG，其余 VCL NAL 组成 VIDEO 载荷。
 */
internal object AnnexB {
    data class Nal(val type: Int, val start: Int, val end: Int)

    data class AccessUnit(
        val bytes: ByteArray,
        val config: ByteArray?,
        val payload: ByteArray,
        val keyFrame: Boolean,
    )

    /** 返回 [data] 中所有起始码位置；起始码为 00 00 01 或 00 00 00 01。 */
    fun startCodes(data: ByteArray): List<Int> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts += i
                    i += 3
                    continue
                }
                if (i + 4 <= data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts += i
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts
    }

    fun headerLength(data: ByteArray, start: Int): Int =
        if (start + 3 < data.size && data[start + 2] == 0.toByte() && data[start + 3] == 1.toByte()) 4 else 3

    fun nals(data: ByteArray): List<Nal> {
        val starts = startCodes(data)
        if (starts.isEmpty()) return emptyList()
        val out = ArrayList<Nal>(starts.size)
        for ((index, start) in starts.withIndex()) {
            val end = if (index + 1 < starts.size) starts[index + 1] else data.size
            val header = start + headerLength(data, start)
            if (header >= end) continue
            out += Nal(data[header].toInt() and 0x1f, start, end)
        }
        return out
    }

    fun parseAccessUnit(bytes: ByteArray): AccessUnit? {
        val nals = nals(bytes)
        if (nals.isEmpty()) return null
        val configOut = ByteArrayOutputStream()
        for (nal in nals) {
            if (nal.type == 7 || nal.type == 8) {
                configOut.write(bytes, nal.start, nal.end - nal.start)
            }
        }
        val config = configOut.toByteArray().takeIf { it.isNotEmpty() }
        val payloadOut = ByteArrayOutputStream()
        var keyFrame = false
        for (nal in nals) {
            when (nal.type) {
                7, 8, 9 -> Unit
                else -> {
                    payloadOut.write(bytes, nal.start, nal.end - nal.start)
                    if (nal.type == 5) keyFrame = true
                }
            }
        }
        val payload = payloadOut.toByteArray()
        if (payload.isEmpty()) return null
        return AccessUnit(bytes, config, payload, keyFrame)
    }
}

/**
 * 增量 Annex-B 访问单元解析器。
 *
 * 输入是 FFmpeg stdout 的任意分块；只有看到下一个 AUD 才认为上一帧完整，
 * 这样不会把半帧发给接收端。最多保留一个未完成帧，内存恒定。
 */
internal class AnnexBAccessUnitParser {
    private val pending = ByteArrayOutputStream()

    fun feed(chunk: ByteArray): List<AnnexB.AccessUnit> {
        pending.write(chunk)
        val data = pending.toByteArray()
        val aud = audStarts(data)
        if (aud.size < 2) return emptyList()
        val units = ArrayList<AnnexB.AccessUnit>(aud.size - 1)
        val first = aud.first()
        for (i in 0 until aud.size - 1) {
            val start = if (i == 0) 0 else aud[i]
            val end = aud[i + 1]
            val slice = data.copyOfRange(start, end)
            AnnexB.parseAccessUnit(slice)?.let { units += it }
        }
        val keepFrom = aud.last()
        pending.reset()
        pending.write(data, keepFrom, data.size - keepFrom)
        // first 可能大于 0（AUD 前的 SPS/PPS 已被并入第一帧），这里只保留最后一个 AUD 起。
        if (first > 0 && aud.size == 2 && keepFrom > 0) {
            // 无需额外处理：下一帧从 AUD 重新开始，SPS/PPS 会由 repeat-headers 重新附上。
        }
        return units
    }

    fun flush(): List<AnnexB.AccessUnit> {
        val data = pending.toByteArray()
        if (data.isEmpty()) return emptyList()
        pending.reset()
        return AnnexB.parseAccessUnit(data)?.let { listOf(it) } ?: emptyList()
    }

    private fun audStarts(data: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        for (start in AnnexB.startCodes(data)) {
            val header = start + AnnexB.headerLength(data, start)
            if (header < data.size && (data[header].toInt() and 0x1f) == 9) out += start
        }
        return out
    }
}

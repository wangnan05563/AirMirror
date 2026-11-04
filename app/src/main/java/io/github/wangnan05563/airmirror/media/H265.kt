package io.github.wangnan05563.airmirror.media

/**
 * H.265/HEVC 的 Annex-B 工具（PRD 8.7 编码选择顺序里的 H.265 一侧）。
 *
 * 拆起始码/统一起始码这两件事与编码无关，直接复用 [H264] 里同名方法；
 * 这里只放 HEVC 特有的部分：NAL 头是两字节，类型取首字节低 6 位右移一位，
 * 配置块是 VPS+SPS+PPS 三段（H.264 只有 SPS+PPS），解码器按 csd-0/1/2 依次喂。
 */
object H265 {
    const val NAL_VPS = 32
    const val NAL_SPS = 33
    const val NAL_PPS = 34

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** NAL 类型；单元太短或不含负载时回 -1，调用方按「不认识」处理。 */
    fun typeOf(unit: ByteArray): Int {
        var i = 0
        while (i < unit.size && (unit[i] == 0.toByte() || unit[i] == 1.toByte())) i++
        if (i >= unit.size) return -1
        return (unit[i].toInt() shr 1) and 0x3F
    }

    /** 把编码器给的裸 NAL 拼成带起始码的配置块，顺序保持 VPS、SPS、PPS。 */
    fun packCsd(rawUnits: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream(rawUnits.sumOf { it.size } + rawUnits.size * 4)
        for (raw in rawUnits) {
            out.write(START_CODE)
            out.write(raw)
        }
        return out.toByteArray()
    }

    /**
     * 从 Annex-B 配置块里按 VPS、SPS、PPS 的顺序取出三段，每段都带起始码。
     * 少任何一段都返回 null：缺 PPS 起解码器会配得上但一帧都解不出来，
     * 那种「配成功、画面全黑」比直接报错难查得多，宁可由调用方回退 H.264。
     */
    fun csdUnits(annexB: ByteArray): List<ByteArray>? {
        val byType = HashMap<Int, ByteArray>(3)
        for (unit in H264.splitAnnexB(H264.normalize(annexB))) {
            val type = typeOf(unit)
            if (type == NAL_VPS || type == NAL_SPS || type == NAL_PPS) byType.putIfAbsent(type, unit)
        }
        val ordered = listOfNotNull(byType[NAL_VPS], byType[NAL_SPS], byType[NAL_PPS])
        return ordered.takeIf { it.size == 3 }
    }
}

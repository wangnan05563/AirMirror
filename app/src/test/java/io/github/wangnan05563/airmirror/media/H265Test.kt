package io.github.wangnan05563.airmirror.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * H.265 配置块的拆与装（PRD 8.7 的 H.265 一侧）。
 * 构造的 NAL 只需头两字节真实，负载用假字节即可：这里验的是类型识别与顺序，不是码流内容。
 */
class H265Test {
    private fun nal(type: Int, vararg body: Int): ByteArray =
        byteArrayOf((type shl 1).toByte(), 0x01) + body.map { it.toByte() }.toByteArray()

    private val vps = nal(H265.NAL_VPS, 0x41, 0x02)
    private val sps = nal(H265.NAL_SPS, 0x42, 0x01)
    private val pps = nal(H265.NAL_PPS, 0x44, 0x03)
    private val idr = nal(19, 0x08)

    private val startCode = byteArrayOf(0, 0, 0, 1)

    @Test
    fun `nal type comes from the six bits after the two byte header`() {
        assertEquals(H265.NAL_VPS, H265.typeOf(startCode + vps))
        assertEquals(H265.NAL_SPS, H265.typeOf(startCode + sps))
        assertEquals(H265.NAL_PPS, H265.typeOf(startCode + pps))
        assertEquals(19, H265.typeOf(startCode + idr))
        // 短到没有负载的单元按「不认识」处理，不让调用方拿到一个瞎猜出来的类型
        assertEquals(-1, H265.typeOf(byteArrayOf(0, 0, 0, 1)))
    }

    @Test
    fun `packing keeps every nal separately addressable`() {
        val packed = H265.packCsd(listOf(vps, sps, pps))
        val units = H264.splitAnnexB(packed)
        assertEquals(3, units.size)
        assertEquals(listOf(H265.NAL_VPS, H265.NAL_SPS, H265.NAL_PPS), units.map { H265.typeOf(it) })
    }

    @Test
    fun `csd units are reordered to vps sps pps whatever the stream order is`() {
        val annexB = H265.packCsd(listOf(pps, idr, vps, sps))
        val units = H265.csdUnits(annexB)!!
        assertEquals(3, units.size)
        assertEquals(listOf(H265.NAL_VPS, H265.NAL_SPS, H265.NAL_PPS), units.map { H265.typeOf(it) })
        // 每段都自带起始码：直接喂给 csd-0/1/2，接收端不需要再猜边界
        units.forEach { assertEquals(startCode.toList(), it.take(4).toList()) }
    }

    /** 缺任一段就回 null：配得上却一帧解不出来，比直接失败难查得多 */
    @Test
    fun `an incomplete config is refused`() {
        assertNull(H265.csdUnits(H265.packCsd(listOf(vps, sps))))
        assertNull(H265.csdUnits(H265.packCsd(listOf(sps, pps))))
        assertNull(H265.csdUnits(H265.packCsd(listOf(idr))))
        assertNull(H265.csdUnits(ByteArray(0)))
    }

    @Test
    fun `three byte start codes are normalised before splitting`() {
        val threeByte = byteArrayOf(0, 0, 1) + vps + byteArrayOf(0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps
        val units = H265.csdUnits(threeByte)!!
        assertEquals(listOf(H265.NAL_VPS, H265.NAL_SPS, H265.NAL_PPS), units.map { H265.typeOf(it) })
    }

    /** 收到 H.264 的配置却按 H.265 解，绝不能被判成「三段齐全」而蒙着建解码器 */
    @Test
    fun `a h264 style config is not mistaken for h265`() {
        // H.264 的 SPS/PPS 首字节是 0x67/0x68，两字节头读出来的类型对不上 VPS/SPS/PPS
        val h264 = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE.toByte())
        assertNull(H265.csdUnits(h264))
    }
}

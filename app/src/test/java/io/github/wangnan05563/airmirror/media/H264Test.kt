package io.github.wangnan05563.airmirror.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264Test {

    @Test
    fun splitFindsFourByteStartCodes() {
        val stream = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3)
        val units = H264.splitAnnexB(stream)
        assertEquals(2, units.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 1, 2), units[0])
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x68, 3), units[1])
    }

    @Test
    fun splitFindsThreeByteStartCodes() {
        val stream = byteArrayOf(0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2)
        val units = H264.splitAnnexB(stream)
        assertEquals(2, units.size)
        assertEquals(0x67.toByte(), units[0][3])
    }

    @Test
    fun normalizeRewritesThreeByteStartCodes() {
        val stream = byteArrayOf(0, 0, 1, 0x67, 9, 0, 0, 1, 0x68, 8)
        val normalized = H264.normalize(stream)
        assertEquals(12, normalized.size) // 两个 NAL，各 4 字节起始码 + 2 字节负载
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 9), normalized.copyOfRange(0, 6))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x68, 8), normalized.copyOfRange(6, 12))
    }

    @Test
    fun configRoundTripsWithSize() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1E)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, (0xCE.toByte()), 0x38, 0x80.toByte())
        val packed = H264.packConfig(1280, 720, sps + pps)
        val config = H264.unpackConfig(packed)
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertArrayEquals(sps + pps, config.annexB)
        assertTrue(H264.splitAnnexB(config.annexB).size == 2)
    }
}

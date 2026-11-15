package io.github.wangnan05563.airmirror.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBParserTest {
    private fun nal(type: Int, body: String = ""): ByteArray {
        val header = byteArrayOf(0, 0, 0, 1, (0x60 or type).toByte())
        return header + body.toByteArray()
    }

    @Test
    fun splitsAccessUnitsByAudAndExtractsConfig() {
        val sps = nal(7, "sps")
        val pps = nal(8, "pps")
        val idr = nal(5, "idr")
        val p = nal(1, "p")
        val stream = nal(9) + sps + pps + idr + nal(9) + p

        val parser = AnnexBAccessUnitParser()
        val first = parser.feed(stream)
        assertEquals(1, first.size)
        assertTrue(first[0].keyFrame)
        assertNotNull(first[0].config)
        assertTrue(first[0].payload.isNotEmpty())

        val second = parser.flush()
        assertEquals(1, second.size)
        assertEquals(false, second[0].keyFrame)
        assertTrue(second[0].payload.isNotEmpty())
    }

    @Test
    fun handlesChunkBoundaries() {
        val stream = nal(9) + nal(7, "s") + nal(8, "p") + nal(5, "i") + nal(9) + nal(1, "p") + nal(9)
        val parser = AnnexBAccessUnitParser()
        val units = ArrayList<AnnexB.AccessUnit>()
        for (byte in stream) {
            units += parser.feed(byteArrayOf(byte))
        }
        units += parser.flush()
        assertEquals(2, units.size)
        assertTrue(units[0].keyFrame)
    }
}

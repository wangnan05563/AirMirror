package io.github.wangnan05563.airmirror.pc.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AdtsParserTest {
    @Test
    fun `strips adts header and returns access unit`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val parser = AdtsParser()
        val units = parser.feed(adts(payload))
        assertEquals(1, units.size)
        assertArrayEquals(payload, units[0])
    }

    @Test
    fun `keeps partial frame until next feed`() {
        val payload = byteArrayOf(9, 8, 7, 6, 5, 4)
        val frame = adts(payload)
        val parser = AdtsParser()
        assertEquals(0, parser.feed(frame.copyOfRange(0, 6)).size)
        val units = parser.feed(frame.copyOfRange(6, frame.size))
        assertEquals(1, units.size)
        assertArrayEquals(payload, units[0])
    }

    @Test
    fun `skips garbage before sync word`() {
        val payload = byteArrayOf(0x55, 0x66)
        val parser = AdtsParser()
        val units = parser.feed(byteArrayOf(0x00, 0x11, 0x22) + adts(payload))
        assertEquals(1, units.size)
        assertArrayEquals(payload, units[0])
    }

    @Test
    fun `handles two frames in one chunk`() {
        val first = adts(byteArrayOf(1, 1))
        val second = adts(byteArrayOf(2, 2))
        val units = AdtsParser().feed(first + second)
        assertEquals(2, units.size)
        assertArrayEquals(byteArrayOf(1, 1), units[0])
        assertArrayEquals(byteArrayOf(2, 2), units[1])
    }

    /** 48kHz stereo AAC-LC 的最小 ADTS 头，够解析器按 frame_length 切帧。 */
    private fun adts(payload: ByteArray): ByteArray {
        val length = 7 + payload.size
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            0x4C.toByte(), // profile=1(AAC-LC)、sf=3(48k)、ch=2 的高位
            0x80.toByte(), // ch 低位 0 + frame_length 高位
            ((length shr 3) and 0xFF).toByte(),
            (((length and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        ) + payload
    }
}

package io.github.wangnan05563.airmirror.pc.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcAudioFormatTest {
    @Test
    fun `wire format matches android receiver`() {
        assertEquals(
            "sr=48000&ch=2&br=128&enc=aac-lc",
            String(PcAudioFormat.encode(), Charsets.UTF_8),
        )
    }

    @Test
    fun `audio specific config matches android 48k stereo`() {
        assertArrayEquals(
            byteArrayOf(0x11.toByte(), 0x90.toByte()),
            PcAudioFormat.audioSpecificConfig(48_000, 2),
        )
        assertArrayEquals(
            byteArrayOf(0x12.toByte(), 0x08.toByte()),
            PcAudioFormat.audioSpecificConfig(44_100, 1),
        )
        assertArrayEquals(
            byteArrayOf(0x11.toByte(), 0x88.toByte()),
            PcAudioFormat.audioSpecificConfig(48_000, 1),
        )
    }

    @Test
    fun `pts advances by aac frame duration`() {
        assertEquals(21_333L, PcAudioFormat.frameUs())
        assertEquals(0L, PcAudioFormat.ptsUs(0))
        assertEquals(21_333L, PcAudioFormat.ptsUs(1))
        assertEquals(2_133_333L, PcAudioFormat.ptsUs(100))
    }

    @Test
    fun `frame bytes are 1024 stereo s16 samples`() {
        assertEquals(4_096, PcAudioFormat.FRAME_BYTES)
    }
}

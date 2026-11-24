package io.github.wangnan05563.airmirror.pc.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmConverterTest {
    @Test
    fun `float stereo is converted to signed 16 bit little endian`() {
        val input = WasapiLoopbackCapture.Format(48_000, 2, 32, true, 8)
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0.5f)
            putFloat(-0.5f)
        }.array()
        val out = PcmConverter(input).convert(bytes, 100)
        assertEquals(1, out.frames)
        val result = ByteBuffer.wrap(out.pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16_384.toShort(), result.short)
        assertEquals((-16_384).toShort(), result.short)
    }

    @Test
    fun `mono 44k is upmixed and resampled to 48k stereo`() {
        val input = WasapiLoopbackCapture.Format(44_100, 1, 16, false, 2)
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1_000)
            putShort(2_000)
        }.array()
        val out = PcmConverter(input).convert(bytes, 100)
        assertEquals(2, out.frames)
        val result = ByteBuffer.wrap(out.pcm).order(ByteOrder.LITTLE_ENDIAN)
        val frame0Left = result.short
        val frame0Right = result.short
        val frame1Left = result.short
        val frame1Right = result.short
        assertEquals(frame0Left, frame0Right)
        assertEquals(frame1Left, frame1Right)
    }

    @Test
    fun `volume zero produces silence without changing frame count`() {
        val input = WasapiLoopbackCapture.Format(48_000, 2, 16, false, 4)
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(10_000); putShort(-10_000)
            putShort(20_000); putShort(-20_000)
        }.array()
        val out = PcmConverter(input).convert(bytes, 0)
        assertEquals(2, out.frames)
        assertTrue(out.pcm.all { it == 0.toByte() })
    }

    @Test
    fun `24 bit pcm is scaled to 16 bit`() {
        val input = WasapiLoopbackCapture.Format(48_000, 1, 24, false, 3)
        val bytes = byteArrayOf(0x00, 0x00, 0x40) // 0x400000 = 0.5
        val out = PcmConverter(input).convert(bytes, 100)
        assertEquals(1, out.frames)
        val result = ByteBuffer.wrap(out.pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16_384.toShort(), result.short)
    }
}

package io.github.wangnan05563.airmirror.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 镜像音频的线上口径（PRD FR-09「音频单独编码并与视频时间戳对齐」）。 */
class AudioFormatTest {

    @Test
    fun `the descriptor survives a round trip`() {
        val spec = AudioFormat.Spec(sampleRate = 44_100, channels = 1, bitRateKbps = 96, codec = AudioFormat.CODEC_AAC_LC)
        assertEquals(spec, AudioFormat.decode(AudioFormat.encode(spec)))
    }

    @Test
    fun `a descriptor that is not playable decodes to null`() {
        // 拿默认值瞎起声卡会把整条会话的音频变成噪音，必须由调用方决定回落
        assertNull(AudioFormat.decode("sr=0&ch=2&br=128&enc=aac-lc".toByteArray()))
        assertNull(AudioFormat.decode("sr=48000&ch=2&br=128&enc=mp3".toByteArray()))
        assertNull(AudioFormat.decode("sr=48000".toByteArray()))
        assertNull(AudioFormat.decode(ByteArray(0)))
    }

    @Test
    fun `an unknown field is ignored so older builds keep working`() {
        val spec = AudioFormat.decode("sr=48000&ch=2&br=128&enc=aac-lc&future=1".toByteArray())
        assertEquals(48_000, spec?.sampleRate)
        assertEquals(2, spec?.channels)
    }

    @Test
    fun `timestamps advance by whole frames, not by read size`() {
        val start = 1_000_000L
        // 一次 read 拿到的是几百毫秒的样本，帧内每个访问单元只能按样本数往后偏
        assertEquals(start, AudioFormat.ptsUs(start, 0L))
        assertEquals(start + 21_333L, AudioFormat.ptsUs(start, AudioFormat.SAMPLES_PER_FRAME.toLong()))
        assertEquals(start + 42_666L, AudioFormat.ptsUs(start, 2L * AudioFormat.SAMPLES_PER_FRAME))
    }

    @Test
    fun `one frame is about twenty one milliseconds at forty eight kilohertz`() {
        assertTrue(AudioFormat.frameUs() in 21_000L..21_500L)
        assertEquals(42_666L, AudioFormat.frameUs(24_000))
    }

    @Test
    fun `pcm sizing follows the channel count`() {
        assertEquals(2048, AudioFormat.pcmBytes(AudioFormat.SAMPLES_PER_FRAME, channels = 1))
        assertEquals(4096, AudioFormat.pcmBytes(AudioFormat.SAMPLES_PER_FRAME, channels = 2))
    }

    @Test
    fun `the default profile is what the sender encodes with`() {
        val spec = AudioFormat.default()
        assertEquals(AudioFormat.SAMPLE_RATE, spec.sampleRate)
        assertEquals(AudioFormat.CHANNELS, spec.channels)
        assertTrue(spec.isUsable)
        assertEquals(spec, AudioFormat.decode(AudioFormat.encode(spec)))
    }

    @Test
    fun `the raw stream carries the config block the decoder needs`() {
        // 发送端发的是不带 ADTS 头的裸访问单元；没有 csd-0，AAC 解码器认不出帧边界，
        // 现场表现是「音帧一直在、已播永远是 0」，而且一声不吭
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), AudioFormat.audioSpecificConfig(48_000, 2))
        assertArrayEquals(byteArrayOf(0x12, 0x08), AudioFormat.audioSpecificConfig(44_100, 1))
        assertArrayEquals(byteArrayOf(0x11, 0x88.toByte()), AudioFormat.audioSpecificConfig(48_000, 1))
    }

    @Test
    fun `a spec we cannot build a decoder for is not usable`() {
        assertNull(AudioFormat.audioSpecificConfig(44_000, 2))
        assertNull(AudioFormat.audioSpecificConfig(48_000, 3))
        assertFalse(AudioFormat.Spec(44_000, 2, 128, AudioFormat.CODEC_AAC_LC).isUsable)
        assertNull(AudioFormat.decode("sr=44000&ch=2&br=128&enc=aac-lc".toByteArray()))
    }
}

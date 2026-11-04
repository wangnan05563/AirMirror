package io.github.wangnan05563.airmirror.protocol

import io.github.wangnan05563.airmirror.media.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitTest {

    @Test
    fun `ceiling survives a round trip`() {
        val ceiling = Limit.Ceiling(1920, 1080, 60, 12_000, "1080p60")
        val decoded = Limit.decode(Limit.encode(ceiling))
        assertEquals(ceiling, decoded)
    }

    @Test
    fun `empty ceiling decodes to empty`() {
        val decoded = Limit.decode(Limit.empty().let { Limit.encode(it) })
        assertTrue(decoded.isEmpty)
        assertEquals("", decoded.tierLabel)
    }

    @Test
    fun `missing fields default to zero instead of throwing`() {
        val decoded = Limit.decode("w=1280".toByteArray())
        assertEquals(1280, decoded.width)
        assertEquals(0, decoded.height)
        assertTrue(decoded.isEmpty)
    }

    @Test
    fun `garbage payload is tolerated`() {
        val decoded = Limit.decode("not a limit".toByteArray())
        assertTrue(decoded.isEmpty)
    }

    @Test
    fun `covers compares the long edge so portrait screens work`() {
        val ceiling = Limit.Ceiling(1920, 1080, 60, 12_000, "1080p60")
        assertFalse(ceiling.covers(1080, 2400))
        assertTrue(ceiling.covers(1080, 1920))
        assertTrue(ceiling.covers(1280, 720))
    }

    @Test
    fun `zero ceiling never covers anything`() {
        assertFalse(Limit.empty().covers(100, 100))
    }

    /** PRD 8.7 的编码协商走这个字段：声明了 H.265 硬解才发得出去 */
    @Test
    fun `advertised codecs survive a round trip`() {
        val ceiling = Limit.Ceiling(3840, 2160, 60, 30_000, "4K", setOf(VideoCodec.AVC, VideoCodec.HEVC))
        val text = String(Limit.encode(ceiling), Charsets.UTF_8)
        assertTrue(text.contains("&codec=avc+hevc"))
        assertEquals(ceiling, Limit.decode(Limit.encode(ceiling)))
    }

    /** 只支持 H.264 时压根不多发这个字段：线上字节和老版本逐字节一致 */
    @Test
    fun `an avc only ceiling is wire-identical to the old version`() {
        val legacy = String(Limit.encode(Limit.Ceiling(1920, 1080, 60, 12_000, "1080p60")), Charsets.UTF_8)
        val modern = String(
            Limit.encode(Limit.Ceiling(1920, 1080, 60, 12_000, "1080p60", setOf(VideoCodec.AVC))),
            Charsets.UTF_8,
        )
        assertEquals(legacy, modern)
        assertFalse(modern.contains("codec="))
    }

    /** 老接收端不发 codec= ，解出来是空集合，发送端据此只能走 H.264 */
    @Test
    fun `a ceiling without the codec field reports nothing`() {
        assertTrue(Limit.decode("w=1920&h=1080&fps=60&br=12000&tier=1080p60".toByteArray()).codecs.isEmpty())
    }

    @Test
    fun `an unknown codec descriptor does not void the whole ceiling`() {
        val decoded = Limit.decode("w=1920&h=1080&codec=vp9".toByteArray())
        assertEquals(1920, decoded.width)
        assertTrue(decoded.codecs.isEmpty())
    }
}

package io.github.wangnan05563.airmirror.media

import io.github.wangnan05563.airmirror.device.NetworkBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD 8.7「编码选择顺序」：接收端硬解支持 H.265 且带宽充足时优先 H.265，否则 H.264。 */
class VideoCodecTest {
    private val both = setOf(VideoCodec.AVC, VideoCodec.HEVC)
    private val avcOnly = setOf(VideoCodec.AVC)

    @Test
    fun `h265 wins only when both sides have the hardware path`() {
        assertEquals(VideoCodec.HEVC, CodecChoice.select(both, both, NetworkBand.GHZ_5))
        assertEquals(VideoCodec.HEVC, CodecChoice.select(both, both, NetworkBand.UNKNOWN))
    }

    @Test
    fun `a missing hardware path on either side falls back to h264`() {
        assertEquals(VideoCodec.AVC, CodecChoice.select(avcOnly, both, NetworkBand.GHZ_5))
        assertEquals(VideoCodec.AVC, CodecChoice.select(both, avcOnly, NetworkBand.GHZ_5))
        assertEquals(VideoCodec.AVC, CodecChoice.select(emptySet(), both, NetworkBand.GHZ_5))
    }

    /** 老接收端压根没上报 codec= ，空集合的含义是「不知道」，不能当成「全都支持」 */
    @Test
    fun `an unreported ceiling never gets h265`() {
        assertEquals(VideoCodec.AVC, CodecChoice.select(emptySet(), emptySet(), NetworkBand.UNKNOWN))
        assertEquals(VideoCodec.AVC, CodecChoice.select(emptySet(), both, NetworkBand.UNKNOWN))
    }

    /** 2.4GHz 被档位协商压到 720p30，那一档 PRD 写的编码就是 H.264 */
    @Test
    fun `24ghz keeps h264 whatever the hardware says`() {
        assertEquals(VideoCodec.AVC, CodecChoice.select(both, both, NetworkBand.GHZ_2))
        assertEquals("2.4GHz 用 H.264", CodecChoice.reason(VideoCodec.AVC, both, both, NetworkBand.GHZ_2))
    }

    @Test
    fun `the reason names the side that is missing`() {
        assertEquals("双方 H.265 硬解硬编", CodecChoice.reason(VideoCodec.HEVC, both, both, NetworkBand.GHZ_5))
        assertEquals("对方无 H.265 硬解", CodecChoice.reason(VideoCodec.AVC, avcOnly, both, NetworkBand.GHZ_5))
        assertEquals("本机无 H.265 硬编", CodecChoice.reason(VideoCodec.AVC, both, avcOnly, NetworkBand.GHZ_5))
    }

    @Test
    fun `descriptor round trips in priority order`() {
        assertEquals("avc+hevc", CodecChoice.render(both))
        assertEquals("avc", CodecChoice.render(avcOnly))
        assertEquals("", CodecChoice.render(emptySet()))
        assertEquals(both, CodecChoice.parse("avc+hevc"))
        assertEquals(both, CodecChoice.parse("hevc,avc"))
    }

    @Test
    fun `unknown codec names are dropped not fatal`() {
        assertEquals(avcOnly, CodecChoice.parse("avc+vp9"))
        assertEquals(emptySet<VideoCodec>(), CodecChoice.parse("h266"))
        assertEquals(emptySet<VideoCodec>(), CodecChoice.parse(""))
        assertTrue(CodecChoice.parse("AVC").isEmpty())
        assertNull(VideoCodec.fromWire("H264"))
    }

    @Test
    fun `mimeType matches the media codec constants used on both ends`() {
        assertEquals("video/avc", VideoCodec.AVC.mimeType)
        assertEquals("video/hevc", VideoCodec.HEVC.mimeType)
        assertEquals("H.264", VideoCodec.AVC.display)
        assertEquals("H.265", VideoCodec.HEVC.display)
        assertFalse(VideoCodec.AVC == VideoCodec.HEVC)
    }
}

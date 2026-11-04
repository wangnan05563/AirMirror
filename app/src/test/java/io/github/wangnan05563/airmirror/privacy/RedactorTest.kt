package io.github.wangnan05563.airmirror.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD FS-11 / 14.4「日志不含画面、文件名、URL」的判据锁。
 * 这些用例覆盖的是真实泄漏路径：DLNA 片源地址、DIDL 片名、MediaPlayer 异常原文。
 */
class RedactorTest {

    @Test
    fun `片源 URL 整段消失，追踪参数与地址都不残留`() {
        val raw = "play http://192.168.1.9:8080/vod/家庭录像.mp4?token=abc123&utm_source=wechat"
        val out = Redactor.text(raw)
        assertTrue(out, out.startsWith("play "))
        assertTrue(out, out.contains(Redactor.URI_PLACEHOLDER))
        assertFalse(out, out.contains("家庭录像"))
        assertFalse(out, out.contains("192.168.1.9"))
        assertFalse(out, out.contains("abc123"))
        assertFalse(out, out.contains("utm_source"))
        assertTrue(Redactor.containsSensitive(raw))
    }

    @Test
    fun `content 与 rtsp 地址同样整段替换`() {
        for (raw in listOf(
            "setDataSource content://media/external/video/media/42 failed",
            "open rtsp://10.0.0.7:554/live/ch1?sig=deadbeef",
        )) {
            val out = Redactor.text(raw)
            assertTrue(out, out.contains(Redactor.URI_PLACEHOLDER))
            assertFalse(out, out.contains("media/42"))
            assertFalse(out, out.contains("deadbeef"))
        }
    }

    @Test
    fun `中文片名与裸文件名都变成占位`() {
        assertEquals("DLNA 载入 [文件名]", Redactor.text("DLNA 载入 春节晚会.mp4"))
        assertEquals("load [文件名] failed", Redactor.text("load clip_2026.mkv failed"))
        assertEquals("title=[文件名]", Redactor.text("title=Avatar.1080p.mkv"))
    }

    @Test
    fun `无 scheme 的媒体路径也挡住，但不误伤诊断文本`() {
        assertTrue(Redactor.text("/sdcard/Movies/私人录像.mp4").contains(Redactor.URI_PLACEHOLDER))
        assertTrue(Redactor.text("D:\\Movies\\clip.mp4").contains(Redactor.URI_PLACEHOLDER))
        for (plain in listOf(
            "play: surfaceReady=true prepared=false",
            "encoder up avc 1920x1080@60 gop=1s bFrames=off",
            "codec H.265/H.264 协商",
            "fit FULL video=384x854 box=1080x1920 -> 384x854",
        )) {
            assertEquals("诊断信息被误脱敏：$plain", plain, Redactor.text(plain))
        }
    }

    @Test
    fun `裸 query 里的令牌与会话号被抹掉`() {
        val out = Redactor.text("retry sid=abc123 token=xyz state=busy")
        assertFalse(out, out.contains("abc123"))
        assertFalse(out, out.contains("xyz"))
        assertTrue(out, out.contains("sid=" + Redactor.REDACTED))
        assertTrue(out, out.contains("state=busy"))
    }

    @Test
    fun `异常 message 常自带片源地址，进日志前必须过一遍`() {
        val error = IllegalStateException("setDataSource failed: http://10.0.0.2/vod/私人.mp4")
        val out = Redactor.throwable(error)
        assertFalse(out, out.contains("私人.mp4"))
        assertFalse(out, out.contains("10.0.0.2"))
        assertEquals("", Redactor.throwable(null))
    }

    @Test
    fun `片名整体用占位，不猜哪些字算敏感`() {
        assertEquals("[片名]", Redactor.title("家庭录像"))
        assertEquals("", Redactor.title(null))
        assertEquals("", Redactor.title("   "))
    }
}

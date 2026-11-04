package io.github.wangnan05563.airmirror.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD 8.9「崩溃日志默认关闭且可清除」、14.4「日志本地 7 天且可清空」、
 * FS-11「诊断默认关闭，开启后仅上报崩溃堆栈与设备型号」的判据锁。
 */
class DiagnosticPolicyTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `默认关闭，没显式开启一次都不写`() {
        assertFalse(DiagnosticPolicy.DEFAULT_ENABLED)
        assertFalse(DiagnosticPolicy.shouldRecord(false))
        assertTrue(DiagnosticPolicy.shouldRecord(true))
    }

    @Test
    fun `恰好 7 天仍保留，超过才过期`() {
        val edge = now - DiagnosticPolicy.RETENTION_MS
        assertFalse("恰好 7 天不该被删", DiagnosticPolicy.isExpired(now, edge))
        assertTrue("超过 7 天要删", DiagnosticPolicy.isExpired(now, edge - 1))
        assertFalse("未来的时间戳不该被删", DiagnosticPolicy.isExpired(now, now + 1_000))
        assertTrue("读不到修改时间按过期处理", DiagnosticPolicy.isExpired(now, 0))
        assertEquals(7, DiagnosticPolicy.RETENTION_DAYS)
    }

    @Test
    fun `一条诊断只允许有时间戳、型号和堆栈`() {
        val stack = "java.lang.IllegalStateException: setDataSource failed: http://10.0.0.2/vod/私人.mp4\n" +
            "  at io.github.wangnan05563.airmirror.media.DlnaPlayer.load(DlnaPlayer.kt:150)"
        val body = DiagnosticPolicy.render(now, "当贝 MAX1", stack)
        assertTrue(body, body.contains("capturedAt=$now"))
        assertTrue(body, body.contains("model=当贝 MAX1"))
        assertTrue(body, body.contains("IllegalStateException"))
        assertFalse("堆栈里的片源地址也必须脱敏", body.contains("私人.mp4"))
        assertFalse(body.contains("10.0.0.2"))
        assertFalse("不采集画面/文件名/URL 之外的用户字段", body.contains("device_id"))
    }

    @Test
    fun `型号里若混入地址同样脱敏`() {
        val body = DiagnosticPolicy.render(now, "http://tracker.example.com/id", "stack")
        assertFalse(body.contains("tracker.example.com"))
        assertTrue(body.contains("model=" + Redactor.URI_PLACEHOLDER))
    }

    @Test
    fun `清缓存不碰设备身份与已授权设备`() {
        for (name in listOf("airmirror-pairing", "airmirror-dlna", "airmirror-diagnostics", "shared_prefs")) {
            assertFalse("清缓存删到了 $name", DiagnosticPolicy.mayDelete(name))
        }
        assertTrue(DiagnosticPolicy.mayDelete("dlna-thumbnails"))
        assertTrue(DiagnosticPolicy.mayDelete("http-cache"))
    }

    @Test
    fun `诊断目录固定在应用私有目录名，不落到外部公共存储`() {
        assertEquals("diagnostics", DiagnosticPolicy.DIR)
    }
}

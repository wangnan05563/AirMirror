package io.github.wangnan05563.airmirror.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T06120 诊断看板口径：只取最近一次会话、没有样本不冒充 0、导出前过 Redactor。
 * 用 Robolectric 跑是为了让 `org.json` 走 Android 实现，而不是 JVM 的未实现桩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerfSummaryTest {

    @Test
    fun `latest gathers only the last session`() {
        val old = PerfTrace.line(1_000L, "old", PerfTrace.Event.SESSION_START, mapOf("encrypted" to false))
        val oldEnd = PerfTrace.line(2_000L, "old", PerfTrace.Event.SESSION_END, mapOf("received" to 9))
        val lines = listOf(
            old,
            oldEnd,
            PerfTrace.line(10_000L, "s1", PerfTrace.Event.SESSION_START, mapOf("encrypted" to true)),
            PerfTrace.line(
                11_000L,
                "s1",
                PerfTrace.Event.CONFIG,
                mapOf("codec" to "h264", "width" to 1920, "height" to 1080),
            ),
            PerfTrace.line(
                12_000L,
                "s1",
                PerfTrace.Event.FIRST_FRAME,
                mapOf("config_ms" to 800, "auth_ms" to 1_200),
            ),
            PerfTrace.line(
                13_000L,
                "s1",
                PerfTrace.Event.METRICS,
                mapOf(
                    "p90_ms" to 80,
                    "p95_ms" to 120,
                    "drift_ms" to 4,
                    "received" to 100,
                    "decoded" to 99,
                    "rendered" to 98,
                    "dropped" to 1,
                    "audio_frames" to 50,
                ),
            ),
            PerfTrace.line(
                14_000L,
                "s1",
                PerfTrace.Event.AUDIO_OPEN,
                mapOf("sr" to 48_000, "ch" to 2, "codec" to "aac"),
            ),
            PerfTrace.line(15_000L, "s1", PerfTrace.Event.AUDIO_GAP, mapOf("reason" to "silent")),
            PerfTrace.line(
                16_000L,
                "s1",
                PerfTrace.Event.SESSION_END,
                mapOf("received" to 120, "decoded" to 119, "audio_frames" to 60),
            ),
        )

        val summary = PerfSummaryReader.latest(lines)
        assertNotNull(summary)
        summary!!
        assertEquals("s1", summary.sessionId)
        assertTrue(summary.encrypted)
        assertEquals(10_000L, summary.startedAtMs)
        assertEquals(16_000L, summary.endedAtMs)
        assertEquals(1_200L, summary.firstFrameAuthMs)
        assertEquals(800L, summary.firstFrameConfigMs)
        assertEquals(80L, summary.p90Ms)
        assertEquals(120L, summary.p95Ms)
        assertEquals(120L, summary.received)
        assertEquals(119L, summary.decoded)
        assertEquals(98L, summary.rendered)
        assertEquals(48_000, summary.audioSampleRate)
        assertEquals(2, summary.audioChannels)
        assertEquals("aac", summary.audioCodec)
        assertEquals(1, summary.audioGaps)
        assertEquals(1, summary.silentGaps)
        val text = DiagnosticReport.summaryText(summary)
        assertTrue(text.contains("偏差 4ms"))
        assertTrue(text.contains("达标（≤200ms）"))
    }

    @Test
    fun `missing samples stay unknown instead of zero`() {
        val lines = listOf(
            PerfTrace.line(1_000L, "s2", PerfTrace.Event.SESSION_START, mapOf("encrypted" to false)),
            PerfTrace.line(2_000L, "s2", PerfTrace.Event.SESSION_END, mapOf("received" to 0)),
        )
        val summary = PerfSummaryReader.latest(lines)
        assertNotNull(summary)
        summary!!
        assertEquals(PerfSummary.UNKNOWN, summary.p95Ms)
        assertEquals(-1.0, summary.dropRatePercent, 0.0001)
        val text = DiagnosticReport.summaryText(summary)
        assertTrue(text.contains("P95 未采集"))
        assertTrue(text.contains("丢帧未采集"))
        assertFalse(text.contains("P95 0ms"))
    }

    @Test
    fun `bad json lines are skipped`() {
        val lines = listOf(
            "not-json",
            PerfTrace.line(1_000L, "s3", PerfTrace.Event.SESSION_START, mapOf("encrypted" to true)),
            "{broken",
            PerfTrace.line(2_000L, "s3", PerfTrace.Event.FIRST_FRAME, mapOf("auth_ms" to 500)),
        )
        val summary = PerfSummaryReader.latest(lines)
        assertEquals("s3", summary?.sessionId)
        assertEquals(500L, summary?.firstFrameAuthMs)
    }

    @Test
    fun `export applies redactor to model and summary`() {
        val summary = PerfSummary(
            sessionId = "s4",
            startedAtMs = 1_000L,
            endedAtMs = 2_000L,
            p95Ms = 120L,
        )
        val exported = DiagnosticReport.exportText(
            summary,
            "Pixel https://example.com/a.mp4",
            3_000L,
        )
        assertTrue(exported.contains("AirMirror 诊断摘要"))
        assertFalse(exported.contains("example.com"))
        assertTrue(exported.contains("[地址]"))
    }

    @Test
    fun `latest still works when session start scrolled out of window`() {
        val lines = listOf(
            PerfTrace.line(
                91_000L,
                "s5",
                PerfTrace.Event.METRICS,
                mapOf("p90_ms" to 70, "p95_ms" to 130, "received" to 100, "decoded" to 100, "dropped" to 0),
            ),
            PerfTrace.line(92_000L, "s5", PerfTrace.Event.SESSION_END, mapOf("received" to 110)),
        )
        val summary = PerfSummaryReader.latest(lines)
        assertEquals("s5", summary?.sessionId)
        assertEquals(130L, summary?.p95Ms)
        assertTrue(DiagnosticReport.summaryText(summary).contains("时长未采集"))
    }

    @Test
    fun `duration text is stable for short and long sessions`() {
        assertEquals("0m 5s", DiagnosticReport.durationText(5_000L))
        assertEquals("1h 2m", DiagnosticReport.durationText(3_720_000L))
        assertEquals("0m 0s", DiagnosticReport.durationText(-1_000L))
    }
}

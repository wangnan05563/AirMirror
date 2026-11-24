package io.github.wangnan05563.airmirror.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfTraceTest {
    @Test
    fun `line escapes quotes newlines and backslashes`() {
        val line = PerfTrace.line(
            1_000L,
            "s1",
            PerfTrace.Event.DROP,
            mapOf("reason" to "a\"b\\c\nd"),
        )
        assertTrue(line.contains("\\\"b"))
        assertTrue(line.contains("\\\\c"))
        assertTrue(line.contains("\\nd"))
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `forbidden field names are redacted`() {
        val line = PerfTrace.line(1_000L, "s1", "x", mapOf("ip" to "10.0.0.1", "ok" to 1))
        assertTrue(line.contains("\"redacted\":\"10.0.0.1\""))
        assertTrue(line.contains("\"ok\":\"1\""))
    }

    @Test
    fun `retention keeps exactly seven days`() {
        val now = 10_000_000_000L
        assertFalse(PerfTrace.isExpired(now, now - PerfTrace.RETENTION_MS))
        assertTrue(PerfTrace.isExpired(now, now - PerfTrace.RETENTION_MS - 1))
        assertTrue(PerfTrace.isExpired(now, 0L))
    }

    @Test
    fun `file cap is bounded`() {
        assertEquals(8, PerfTrace.keepCount(100))
        assertEquals(3, PerfTrace.keepCount(3))
    }

    @Test
    fun `session ids are unique and carry no device identity`() {
        val a = PerfTrace.sessionId(1_000L)
        val b = PerfTrace.sessionId(1_000L)
        assertTrue(a.startsWith("s"))
        assertFalse(a == b)
        assertFalse(a.contains("."))
    }
}

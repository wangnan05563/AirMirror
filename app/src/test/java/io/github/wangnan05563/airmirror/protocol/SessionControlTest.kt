package io.github.wangnan05563.airmirror.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControlTest {

    @Test
    fun `pause and resume round trip`() {
        assertEquals(SessionControl.OP_PAUSE, SessionControl.decode(SessionControl.pause()).op)
        assertEquals(SessionControl.OP_RESUME, SessionControl.decode(SessionControl.resume()).op)
    }

    @Test
    fun `takeover carries the requesting device`() {
        val decoded = SessionControl.decode(SessionControl.takeover("device-42"))
        assertEquals(SessionControl.OP_TAKEOVER, decoded.op)
        assertEquals("device-42", decoded.from)
        assertFalse(decoded.allow)
    }

    @Test
    fun `takeover result round trips both answers`() {
        assertTrue(SessionControl.decode(SessionControl.takeoverResult(true)).allow)
        assertFalse(SessionControl.decode(SessionControl.takeoverResult(false)).allow)
    }

    @Test
    fun `a ceiling payload is not mistaken for a signal`() {
        val decoded = SessionControl.decode(Limit.encode(Limit.Ceiling(1920, 1080, 60, 12_000, "1080p60")))
        assertTrue(decoded.isEmpty)
    }

    @Test
    fun `garbage payload decodes to empty signal`() {
        val decoded = SessionControl.decode("not a signal".toByteArray())
        assertTrue(decoded.isEmpty)
        assertEquals("", decoded.from)
        assertFalse(decoded.allow)
    }

    @Test
    fun `escaped device names survive the trip`() {
        val decoded = SessionControl.decode(SessionControl.takeover("客厅&电视 50%"))
        assertEquals("客厅&电视 50%", decoded.from)
    }
}
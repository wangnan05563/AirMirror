package io.github.wangnan05563.airmirror.protocol

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
}

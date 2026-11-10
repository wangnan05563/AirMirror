package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QrCacheTest {

    @Test
    fun `cache key includes all parameters`() {
        val key = Qr.cacheKey("hello", 420, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals("hello|420|-16777216|-1", key)
    }

    @Test
    fun `same input produces same key`() {
        val a = Qr.cacheKey("payload", 420, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val b = Qr.cacheKey("payload", 420, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(a, b)
    }

    @Test
    fun `different text produces different key`() {
        val a = Qr.cacheKey("aaa", 420, 0, -1)
        val b = Qr.cacheKey("bbb", 420, 0, -1)
        assertNotEquals(a, b)
    }

    @Test
    fun `different size produces different key`() {
        val a = Qr.cacheKey("x", 420, 0, -1)
        val b = Qr.cacheKey("x", 240, 0, -1)
        assertNotEquals(a, b)
    }

    @Test
    fun `different colors produce different key`() {
        val a = Qr.cacheKey("x", 420, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val b = Qr.cacheKey("x", 420, 0xFF12314F.toInt(), 0xFFFFFFFF.toInt())
        assertNotEquals(a, b)
    }

    @Test
    fun `evict does not throw`() {
        Qr.evict()
    }
}
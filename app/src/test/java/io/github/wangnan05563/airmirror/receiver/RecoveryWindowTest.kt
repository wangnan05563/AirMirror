package io.github.wangnan05563.airmirror.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FS-10 保留窗口：只认原设备、到点失效、续会与主动关闭都不能被误判成超时。 */
class RecoveryWindowTest {

    private var clock = 1_000L
    private val window = RecoveryWindow(windowMillis = 30_000L, now = { clock })

    @Test
    fun `open keeps the device for the whole window`() {
        val holder = window.open("phone-1", "Pixel")
        assertEquals("phone-1", holder.deviceId)
        assertEquals(1_000L + 30_000L, holder.deadline)
        assertEquals("phone-1", window.active()?.deviceId)
        assertTrue(window.matches("phone-1"))
    }

    @Test
    fun `another device never matches the reserved window`() {
        window.open("phone-1", "Pixel")
        assertFalse(window.matches("phone-2"))
        assertEquals("phone-1", window.active()?.deviceId)
    }

    @Test
    fun `the window expires exactly at the deadline`() {
        window.open("phone-1", "Pixel")
        clock += 29_999L
        assertTrue(window.matches("phone-1"))
        clock += 1L
        assertNull(window.active())
        assertFalse(window.matches("phone-1"))
    }

    @Test
    fun `expire only fires for the reserved device after the deadline`() {
        window.open("phone-1", "Pixel")
        clock += 10_000L
        assertFalse("early expire must not clear the window", window.expire("phone-1"))
        assertFalse("wrong device must not clear the window", window.expire("phone-2"))
        clock += 20_000L
        assertTrue(window.expire("phone-1"))
        assertNull(window.peek())
    }

    @Test
    fun `a finished resume closes the window without looking like a timeout`() {
        window.open("phone-1", "Pixel")
        window.close()
        assertNull(window.active())
        clock += 31_000L
        assertFalse("closed window must not expire as a timeout", window.expire("phone-1"))
    }

    @Test
    fun `peek reports the holder without clearing an expired one`() {
        window.open("phone-1", "Pixel")
        clock += 30_000L
        assertEquals("phone-1", window.peek()?.deviceId)
        assertNull(window.active())
        assertNull(window.peek())
    }
}
package io.github.wangnan05563.airmirror.protocol

import io.github.wangnan05563.airmirror.error.ErrorNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolBoundaryTest {

    @Test
    fun `only the protocols we actually receive are reported as supported`() {
        assertTrue(ProtocolBoundary.supports("AMRC"))
        assertTrue(ProtocolBoundary.supports("dlna"))
        assertFalse(ProtocolBoundary.supports("Miracast"))
        assertFalse(ProtocolBoundary.supports("AirPlay"))
        assertFalse(ProtocolBoundary.supports("Cast"))
        assertFalse(ProtocolBoundary.supports("Cast+"))
        assertFalse(ProtocolBoundary.supports("Browser"))
    }

    @Test
    fun `miracast gets an actionable refusal instead of a dead end`() {
        val capability = ProtocolBoundary.describe("Miracast")
        assertFalse(capability.supported)
        assertTrue(capability.headline.startsWith("不支持"))
        assertTrue(capability.nextStep.contains("无线显示"))
        assertTrue(capability.text().length > capability.headline.length)
    }

    @Test
    fun `every headline fits the 18 character budget`() {
        val names = listOf("AMRC", "DLNA", "AirPlay", "Cast", "Miracast", "Cast+", "Unknown")
        for (name in names) {
            val headline = ProtocolBoundary.describe(name).headline
            assertTrue("$name: $headline", headline.length <= ErrorNotice.MAX_HEADLINE_CHARS)
        }
    }

    @Test
    fun `unknown protocol is not silently treated as usable`() {
        val capability = ProtocolBoundary.describe("Browser")
        assertFalse(capability.supported)
        assertNull(capability.protocol)
        assertTrue(capability.nextStep.contains("投屏码"))
    }

    @Test
    fun `a device advertising several protocols keeps the one we can use`() {
        assertEquals("DLNA", ProtocolBoundary.bestSupported(listOf("AirPlay", "DLNA")))
        assertEquals("AMRC", ProtocolBoundary.bestSupported(listOf("Miracast", "AMRC", "DLNA")))
        assertNull(ProtocolBoundary.bestSupported(listOf("Miracast", "AirPlay")))
    }

    @Test
    fun `receiver page lists implemented entries and names what is missing`() {
        val mirror = ProtocolBoundary.receiverMirrorLine()
        val push = ProtocolBoundary.receiverPushLine()
        val missing = ProtocolBoundary.receiverUnsupportedLine()
        assertTrue(mirror.contains("AirMirror"))
        assertTrue(mirror.contains("投屏码"))
        assertTrue(push.contains("DLNA"))
        assertTrue(missing.contains("Miracast"))
        assertTrue(missing.contains("iPhone"))
        // 不能出现「控制中心 → 屏幕镜像」这类尚未实现的 AirPlay 引导
        assertFalse(mirror.contains("控制中心"))
        assertFalse(missing.contains("控制中心"))
    }

    @Test
    fun `badges never promise a protocol we cannot receive`() {
        assertEquals("镜像 · 私有通道", ProtocolBoundary.badgeFor("AMRC"))
        assertEquals("DLNA 推送", ProtocolBoundary.badgeFor("DLNA"))
        assertEquals("不支持", ProtocolBoundary.badgeFor("Miracast"))
        assertEquals("不支持", ProtocolBoundary.badgeFor("AirPlay"))
    }
}

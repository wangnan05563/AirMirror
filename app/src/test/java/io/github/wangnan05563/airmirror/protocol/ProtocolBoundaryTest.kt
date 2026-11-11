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
        assertTrue("浏览器上传已落地，必须进矩阵", ProtocolBoundary.supports("Browser"))
        assertFalse(ProtocolBoundary.supports("Miracast"))
        assertFalse(ProtocolBoundary.supports("AirPlay"))
        assertFalse(ProtocolBoundary.supports("Cast"))
        assertFalse(ProtocolBoundary.supports("Cast+"))
        assertFalse(ProtocolBoundary.supports("Unknown"))
    }

    @Test
    fun `matrix and copy never disagree`() {
        // supports() 与 describe().supported 必须同源：两套口径正是「假能力」的来源
        for (protocol in ProtocolBoundary.Protocol.values()) {
            assertEquals(
                "${protocol.wireName} 的 supports 与 describe 不一致",
                ProtocolBoundary.supports(protocol.wireName),
                ProtocolBoundary.describe(protocol.wireName).supported,
            )
        }
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
        val capability = ProtocolBoundary.describe("CarPlay")
        assertFalse(capability.supported)
        assertNull(capability.protocol)
        assertTrue(capability.nextStep.contains("投屏码"))
    }

    @Test
    fun `browser entry is advertised with an actionable next step`() {
        val capability = ProtocolBoundary.describe("Browser")
        assertTrue(capability.supported)
        assertEquals(ProtocolBoundary.Protocol.BROWSER, capability.protocol)
        assertTrue(capability.nextStep.contains("浏览器"))
        assertTrue(capability.nextStep.contains("文件"))
    }

    @Test
    fun `receiver page does not promise a desktop client that does not exist`() {
        assertFalse(
            "电脑端发送端仍在 M7，未实现前不能出现在待连接页",
            ProtocolBoundary.receiverMirrorLine().contains("电脑"),
        )
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
        assertEquals("浏览器上传", ProtocolBoundary.badgeFor("Browser"))
        assertEquals("不支持", ProtocolBoundary.badgeFor("Miracast"))
        assertEquals("不支持", ProtocolBoundary.badgeFor("AirPlay"))
    }

    @Test
    fun `casting overlay names the sender the channel and the quality`() {
        // PRD §11.2：连接成功那 3 秒浮层要同时回答「谁在投、走哪条通道、什么画质」
        val line = ProtocolBoundary.castingOverlayLine("客厅手机", 1080, 1920)
        assertTrue(line, line.contains("客厅手机"))
        assertTrue(line, line.contains("私有通道"))
        assertTrue(line, line.contains("1080x1920"))
    }

    @Test
    fun `casting overlay omits quality until the first frame reports it`() {
        // 画质由首帧带回，浮层先弹；这时宁可不写画质，也不能显示 0x0 让人以为出故障
        val line = ProtocolBoundary.castingOverlayLine("客厅手机", 0, 0)
        assertEquals("客厅手机 · 私有通道", line)
        assertFalse(line.contains("0x0"))
    }
}

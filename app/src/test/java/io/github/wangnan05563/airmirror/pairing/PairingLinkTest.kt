package io.github.wangnan05563.airmirror.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待连接页二维码载荷：扫码后另一端要能还原出设备标识、一次性令牌与直连地址，
 * 解析不出来的必须整条拒绝，而不是拿残缺数据去连。
 */
class PairingLinkTest {

    private val link = PairingLink.Link("am-7f3c", "客厅的 AirMirror", "0a1b2c3d4e5f6071", "192.168.1.20", 41_523)

    @Test
    fun buildProducesAScanReadyLink() {
        val url = PairingLink.build(link)
        assertTrue(url.startsWith("airmirror://pair?id=am-7f3c&t=0a1b2c3d4e5f6071&n="))
        assertTrue(url.endsWith("&p=41523"))
    }

    @Test
    fun roundTripKeepsChineseNamesAndReservedCharacters() {
        val tricky = link.copy(name = "张三的手机 & 电视=50%\n\"'")
        assertEquals(tricky, PairingLink.parse(PairingLink.build(tricky)))
    }

    @Test
    fun anythingThatIsNotAPairingLinkIsRejected() {
        assertNull(PairingLink.parse(""))
        assertNull(PairingLink.parse("http://example.com/?id=a&t=b&p=1"))
        assertNull(PairingLink.parse("airmirror://pair"))
        // 缺任一定位字段都不能连，按「不是投屏二维码」处理
        assertNull(PairingLink.parse("airmirror://pair?t=token&p=41523"))
        assertNull(PairingLink.parse("airmirror://pair?id=abc&p=41523"))
        assertNull(PairingLink.parse("airmirror://pair?id=abc&t=token"))
        assertNull(PairingLink.parse("airmirror://pair?id=abc&t=token&p=0"))
        assertNull(PairingLink.parse("airmirror://pair?id=abc&t=token&p=70000"))
        assertNull(PairingLink.parse("airmirror://pair?id=abc&t=token&p=abc"))
    }

    @Test
    fun escapingFollowsTheUnreservedSet() {
        assertEquals("abc-_.~ABC123", PairingLink.escape("abc-_.~ABC123"))
        assertEquals("%25%26%3D%20", PairingLink.escape("%&= "))
        assertEquals("%E5%AE%A2", PairingLink.escape("客"))
        assertEquals("客", PairingLink.unescape("%E5%AE%A2"))
    }

    @Test
    fun anUnknownPercentSequenceStaysAsIsInsteadOfCorruptingTheText() {
        assertEquals("%zz", PairingLink.unescape("%zz"))
        assertEquals("%4", PairingLink.unescape("%4"))
        assertEquals("plain", PairingLink.unescape("plain"))
    }

    @Test
    fun rawUtf8AndEscapeSegmentsMayInterleave() {
        // 第三方扫码端未必按同一口径转义，混排也不能出乱码
        assertEquals("客a b", PairingLink.unescape("%E5%AE%A2a%20b"))
        assertEquals("客客", PairingLink.unescape("客%E5%AE%A2"))
        assertEquals("客厅 TV", PairingLink.unescape("客厅 TV"))
    }

    @Test
    fun theTokenInTheLinkIsTheOneVerificationExpects() {
        val code = CastCode(now = { 1_000_000L })
        val parsed = PairingLink.parse(PairingLink.build(link.copy(token = code.token())))!!
        assertEquals(CastCode.Outcome.Accepted, code.submitToken(parsed.token))
    }
}

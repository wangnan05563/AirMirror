package io.github.wangnan05563.airmirror.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SSDP 报文的构造与搜索请求识别（纯函数部分，不建 socket）。 */
class SsdpTest {

    private val types = listOf(
        Upnp.AVTRANSPORT,
        Upnp.RENDERING_CONTROL,
        Upnp.CONNECTION_MANAGER,
        "urn:schemas-upnp-org:device:MediaRenderer:1",
    )

    private fun searchRequest(st: String, extra: String = "") =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\nST: $st\r\n$extra\r\n"

    @Test
    fun readsSearchTarget() {
        assertEquals("ssdp:all", Ssdp.searchTarget(searchRequest("ssdp:all")))
        assertEquals(Upnp.AVTRANSPORT, Ssdp.searchTarget(searchRequest(Upnp.AVTRANSPORT)))
        assertEquals("uuid:abc", Ssdp.searchTarget(searchRequest("uuid:abc")))
    }

    @Test
    fun headerMatchingIsCaseInsensitive() {
        val request = "M-SEARCH * HTTP/1.1\r\nst: ssdp:all\r\n\r\n"
        assertEquals("ssdp:all", Ssdp.searchTarget(request))
    }

    @Test
    fun ignoresNotifyAndNonSearch() {
        assertNull(Ssdp.searchTarget("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNTS: ssdp:alive\r\n"))
        assertNull(Ssdp.searchTarget("GET /description.xml HTTP/1.1\r\nHOST: 127.0.0.1\r\n\r\n"))
        assertNull(Ssdp.searchTarget(""))
    }

    @Test
    fun searchWithoutStIsNotAnswered() {
        assertNull(Ssdp.searchTarget("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n\r\n"))
    }

    @Test
    fun matchesEveryTargetControlPointsUse() {
        // 应答器的目标集＝服务/设备类型 + rootdevice + 自身 uuid，见 SsdpResponder.targets
        val targets = types + "upnp:rootdevice" + "uuid:11111111-2222-3333-4444-555555555555"
        for (type in targets) assertTrue(type, Ssdp.matches(type, targets))
        assertTrue(Ssdp.matches("ssdp:all", targets))
        assertFalse(Ssdp.matches("urn:schemas-upnp-org:device:MediaServer:1", targets))
        assertFalse(Ssdp.matches("urn:schemas-upnp-org:service:AVTransport", targets))
    }

    @Test
    fun searchResponseEchoesStAndPointsAtDescription() {
        val text = Ssdp.searchResponse("ssdp:all", "http://192.168.1.7:41234/description.xml", "uuid:x::ssdp:all")
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("ST: ssdp:all\r\n"))
        assertTrue(text.contains("LOCATION: http://192.168.1.7:41234/description.xml\r\n"))
        assertTrue(text.contains("USN: uuid:x::ssdp:all\r\n"))
        assertTrue(text.contains("SERVER: ${Ssdp.SERVER}\r\n"))
        assertTrue(text.contains("CACHE-CONTROL: max-age=${Ssdp.CACHE_MAX_SECONDS}\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun aliveAnnouncesLocationAndByebyeClearsIt() {
        val alive = Ssdp.alive(Upnp.AVTRANSPORT, "http://192.168.1.7:41234/description.xml", "uuid:x::$Upnp.AVTRANSPORT")
        assertTrue(alive.startsWith("NOTIFY * HTTP/1.1\r\n"))
        assertTrue(alive.contains("NT: ${Upnp.AVTRANSPORT}\r\n"))
        assertTrue(alive.contains("NTS: ssdp:alive\r\n"))
        assertTrue(alive.contains("HOST: ${Ssdp.GROUP}:${Ssdp.PORT}\r\n"))
        val bye = Ssdp.byebye(Upnp.AVTRANSPORT, "uuid:x::$Upnp.AVTRANSPORT")
        assertTrue(bye.contains("NTS: ssdp:byebye\r\n"))
        assertFalse("byebye 不带 LOCATION", bye.contains("LOCATION"))
    }

    @Test
    fun serverHeaderAdvertisesUpnpAndDlnaVersions() {
        assertEquals("AirMirror/1.0 UPnP/1.0 DLNADOC/1.50", Ssdp.SERVER)
    }

    @Test
    fun dateHeaderIsRealRfc1123Gmt() {
        // 1735689600000 = 2025-01-01 00:00:00 GMT；控制点拿 DATE 校正 max-age，写死会被判陈旧
        assertEquals("Wed, 01 Jan 2025 00:00:00 GMT", Ssdp.httpDate(1_735_689_600_000L))
        assertTrue(Ssdp.alive("ssdp:all", "http://h/d.xml", "u").contains("DATE: "))
        assertTrue(Ssdp.searchResponse("ssdp:all", "http://h/d.xml", "u").contains("DATE: "))
    }
}

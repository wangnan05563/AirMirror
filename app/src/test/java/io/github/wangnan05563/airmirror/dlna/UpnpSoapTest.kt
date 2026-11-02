package io.github.wangnan05563.airmirror.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SOAP 报文与 DIDL-Lite 元数据的解析。 */
class UpnpSoapTest {

    private fun envelope(action: String, vararg args: Pair<String, String>): String = buildString {
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><s:Body>")
        append("<u:$action>")
        for ((name, value) in args) append("<u:$name>$value</u:$name>")
        append("</u:$action></s:Body></s:Envelope>")
    }

    @Test
    fun readsActionAndArgs() {
        val body = envelope(
            "SetAVTransportURI",
            "InstanceID" to "0",
            "CurrentURI" to "http://10.0.2.2/a.mp4",
            "CurrentURIMetaData" to "<x>",
        )
        assertEquals("SetAVTransportURI", Upnp.soapAction(body))
        val args = Upnp.soapArgs(body)
        assertEquals("0", args["InstanceID"])
        assertEquals("http://10.0.2.2/a.mp4", args["CurrentURI"])
    }

    @Test
    fun missingBodyYieldsNoAction() {
        assertNull(Upnp.soapAction("<html>not soap</html>"))
        assertTrue(Upnp.soapArgs("<html>not soap</html>").isEmpty())
    }

    @Test
    fun responseRoundTripsThroughOwnParser() {
        val xml = Upnp.soapResponse(Upnp.AVTRANSPORT, "GetTransportInfo", listOf("CurrentTransportState" to "PLAYING"))
        // 自己的解析器读自己的应答：动作名带 Response 后缀，参数能取回
        assertEquals("GetTransportInfoResponse", Upnp.soapAction(xml))
        assertEquals("PLAYING", Upnp.soapArgs(xml)["CurrentTransportState"])
        assertTrue(xml.contains("schemas-upnp-org:service:AVTransport:1"))
    }

    @Test
    fun selfClosingActionElementIsRecognised() {
        // 控制点对无参动作常写 <u:GetProtocolInfo/>，尾巴的 / 不能被当成名字的一部分
        val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
            "<s:Body><u:GetProtocolInfo /></s:Body></s:Envelope>"
        assertEquals("GetProtocolInfo", Upnp.soapAction(body))
        assertTrue(Upnp.soapArgs(body).isEmpty())
    }

    @Test
    fun faultCarriesUpnpErrorCodeInDetail() {
        val fault = Upnp.soapFault(Upnp.FAULT_NO_MEDIA, "no media present")
        assertTrue(fault.contains("<errorCode>701</errorCode>"))
        assertTrue(fault.contains("<faultcode>s:Client</faultcode>"))
        assertTrue(fault.contains("urn:schemas-upnp-org:control-1-0"))
    }

    @Test
    fun didlSurvivesCdataWrapping() {
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\">晚安&#246;</dc:title>" +
            "<upnp:class xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">object.item.videoItem</upnp:class>" +
            "<res duration=\"0:03:20\" size=\"1024\" protocolInfo=\"http-get:*:video/mp4:*\">" +
            "http://192.168.1.9:8080/clip.mp4</res>" +
            "</item></DIDL-Lite>"
        val meta = Upnp.parseDidl("<![CDATA[$didl]]>")
        assertEquals("http://192.168.1.9:8080/clip.mp4", meta?.uri)
        assertEquals("晚安ö", meta?.title)
        assertEquals("object.item.videoItem", meta?.upnpClass)
        assertEquals(200_000L, meta?.durationMs)
        assertEquals("video/mp4", meta?.mime)
    }

    @Test
    fun didlWithoutResIsNotMedia() {
        assertNull(Upnp.parseDidl("<DIDL-Lite><item><dc:title>t</dc:title></item></DIDL-Lite>"))
        assertNull(Upnp.parseDidl(""))
    }

    @Test
    fun didlOutputParsesBack() {
        val meta = Upnp.MediaMeta("http://h/v.mp4", "片 & 名", "object.item.videoItem", 65_000L, "video/mp4")
        val parsed = Upnp.parseDidl(Upnp.didlLite(meta))
        assertEquals(meta.uri, parsed?.uri)
        assertEquals("片 & 名", parsed?.title)
        assertEquals(65_000L, parsed?.durationMs)
        assertEquals("video/mp4", parsed?.mime)
    }

    @Test
    fun clockFormatsBothWays() {
        assertEquals(0L, Upnp.parseClock("00:00:00"))
        assertEquals(200_000L, Upnp.parseClock("0:03:20"))
        assertEquals(3_725_500L, Upnp.parseClock("1:02:05.5"))
        assertEquals(-1L, Upnp.parseClock("garbage"))
        assertEquals(-1L, Upnp.parseClock(""))
        assertEquals("01:02:05", Upnp.formatClock(3_725_500L))
        assertEquals("00:00:00", Upnp.formatClock(-1L))
    }

    @Test
    fun sinkAdvertisesCommonContainers() {
        val sink = Upnp.sinkProtocols()
        assertTrue(sink.contains("http-get:*:video/mp4:*"))
        assertTrue(sink.contains("http-get:*:audio/mpeg:*"))
    }
}

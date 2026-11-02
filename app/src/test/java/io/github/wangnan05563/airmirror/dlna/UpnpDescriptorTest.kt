package io.github.wangnan05563.airmirror.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设备描述与 SCPD 的静态结构。 */
class UpnpDescriptorTest {

    private val device = Upnp.Device(
        uuid = "11111111-2222-3333-4444-555555555555",
        friendlyName = "AirMirror-Pixel-6",
        urlBase = "http://192.168.1.7:41234/",
    )

    @Test
    fun deviceXmlCarriesIdentityAndThreeServices() {
        val xml = Upnp.deviceXml(device)
        assertTrue(xml.contains("<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"))
        assertTrue(xml.contains("<friendlyName>AirMirror-Pixel-6</friendlyName>"))
        assertTrue(xml.contains("<UDN>uuid:${device.uuid}</UDN>"))
        assertTrue(xml.contains("<URLBase>${device.urlBase}</URLBase>"))
        assertTrue(xml.contains("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>"))
        for (short in listOf("AVTransport", "RenderingControl", "ConnectionManager")) {
            assertTrue("$short serviceType", xml.contains("<serviceType>urn:schemas-upnp-org:service:$short:1</serviceType>"))
            assertTrue("$short controlURL", xml.contains("<controlURL>/ctl/$short</controlURL>"))
            assertTrue("$short scpdURL", xml.contains("<SCPDURL>/scpd/$short.xml</SCPDURL>"))
        }
        // 尖括号必须配对，否则控制点解析直接判设备不可用
        assertEquals(xml.count { it == '<' }, xml.count { it == '>' })
    }

    @Test
    fun friendlyNameIsEscaped() {
        val xml = Upnp.deviceXml(device.copy(friendlyName = "A<B>&\"C\""))
        assertFalse(xml.contains("<friendlyName>A<B>"))
        assertTrue(xml.contains("&lt;"))
        assertTrue(xml.contains("&amp;"))
    }

    @Test
    fun scpdPairsEveryArgumentWithAStateVariable() {
        for (serviceId in listOf(Upnp.ID_AVTRANSPORT, Upnp.ID_RENDERING_CONTROL, Upnp.ID_CONNECTION_MANAGER)) {
            val xml = requireNotNull(Upnp.scpdXml(serviceId))
            assertEquals("$serviceId brackets", xml.count { it == '<' }, xml.count { it == '>' })
            val declared = Regex("<stateVariable[^>]*><name>([^<]+)</name>")
                .findAll(xml).map { it.groupValues[1] }.toSet()
            val referenced = Regex("<relatedStateVariable>([^<]+)</relatedStateVariable>")
                .findAll(xml).map { it.groupValues[1] }.toSet()
            assertTrue("undeclared state variables in $serviceId", (referenced - declared).isEmpty())
        }
    }

    @Test
    fun scpdCoversActionsControlPointsProbe() {
        for ((serviceId, required) in listOf(
            Upnp.ID_AVTRANSPORT to listOf(
                "SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetPositionInfo",
                "GetTransportInfo", "GetMediaInfo", "GetCurrentTransportActions", "GetPlayMode",
            ),
            Upnp.ID_RENDERING_CONTROL to listOf("GetVolume", "SetVolume", "GetMute", "SetMute"),
            Upnp.ID_CONNECTION_MANAGER to listOf("GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo"),
        )) {
            val xml = requireNotNull(Upnp.scpdXml(serviceId))
            for (action in required) {
                assertTrue("$serviceId missing $action", xml.contains("<name>$action</name>"))
            }
        }
    }

    @Test
    fun scpdDeclaresPushUriMetadataAndSeekArguments() {
        val av = Upnp.scpdXml(Upnp.ID_AVTRANSPORT)!!
        assertTrue(av.contains("<argument><name>CurrentURIMetaData</name><direction>in</direction>"))
        assertTrue(av.contains("<argument><name>SeekMode</name><direction>in</direction>"))
        assertTrue(av.contains("<argument><name>CurrentTransportState</name><direction>out</direction>"))
        assertFalse("SCPD 里没有 serviceType 这个元素", av.contains("<serviceType>"))
    }

    @Test
    fun scpdRejectsUnknownService() {
        assertNull(Upnp.scpdXml("urn:upnp-org:serviceId:Foo"))
    }
}

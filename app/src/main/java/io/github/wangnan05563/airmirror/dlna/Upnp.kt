package io.github.wangnan05563.airmirror.dlna

import java.nio.charset.Charset
import java.util.Locale

/**
 * UPnP/DLNA 报文构造与解析（纯字符串处理，不依赖 android.jar，可在 JVM 单测中直接跑）。
 *
 * 只做本端真正需要的子集：设备描述、SCPD、SOAP 动作解析与应答、DIDL-Lite 元数据读取。
 */
object Upnp {

    /** 设备身份与网络位置，用于生成设备描述。 */
    data class Device(
        val uuid: String,
        val friendlyName: String,
        val urlBase: String,
        val modelName: String = "AirMirror",
        val serialNumber: String = "1",
    )

    /** 从 DIDL-Lite 里读出的媒体信息。 */
    data class MediaMeta(
        val uri: String,
        val title: String,
        val upnpClass: String,
        val durationMs: Long,
        val mime: String,
    )

    const val AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

    const val ID_AVTRANSPORT = "urn:upnp-org:serviceId:AVTransport"
    const val ID_RENDERING_CONTROL = "urn:upnp-org:serviceId:RenderingControl"
    const val ID_CONNECTION_MANAGER = "urn:upnp-org:serviceId:ConnectionManager"

    /** SOAP 错误码（UPnP AVTransport 规范段 700~714 与通用 5xx/600~699）。 */
    const val FAULT_INVALID_ACTION = 401
    const val FAULT_INVALID_ARGS = 402
    const val FAULT_ACTION_FAILED = 501
    const val FAULT_NO_MEDIA = 701
    const val FAULT_TRANSPORT_BUSY = 702
    const val FAULT_NOT_PLAYING = 705
    const val FAULT_SEEK_MODE_NOT_SUPPORTED = 711

    fun escape(text: String): String {
        val out = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    fun unescape(text: String): String {
        if (text.indexOf('&') < 0) return text
        return decodeNumeric(text)
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    /** 控制点常用数字实体表示非 ASCII 标题，先还原成字符再走命名实体表。 */
    private fun decodeNumeric(text: String): String {
        if (!text.contains("&#")) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("&#", i)
            if (start < 0) {
                out.append(text, i, text.length)
                break
            }
            out.append(text, i, start)
            val semicolon = text.indexOf(';', start + 2)
            val body = if (semicolon < 0) null else text.substring(start + 2, semicolon)
            val code = body?.removePrefix("x")?.toIntOrNull(radix = if (body.startsWith("x")) 16 else 10)
            if (code == null || code !in 1..0x10FFFF) {
                out.append(text, start, minOf(semicolon.coerceAtLeast(0) + 1, text.length))
                i = if (semicolon < 0) text.length else semicolon + 1
                continue
            }
            out.append(String(Character.toChars(code)))
            i = semicolon + 1
        }
        return out.toString()
    }

    fun deviceXml(device: Device): String {
        val base = device.urlBase.trimEnd('/') + "/"
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\" ")
            append("xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">\n")
            append("<specVersion><major>1</major><minor>0</minor></specVersion>\n")
            append("<URLBase>").append(escape(base)).append("</URLBase>\n")
            append("<device>\n")
            append("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>\n")
            append("<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>\n")
            append("<friendlyName>").append(escape(device.friendlyName)).append("</friendlyName>\n")
            append("<manufacturer>AirMirror</manufacturer>\n")
            append("<modelName>").append(escape(device.modelName)).append("</modelName>\n")
            append("<serialNumber>").append(escape(device.serialNumber)).append("</serialNumber>\n")
            append("<UDN>uuid:").append(escape(device.uuid)).append("</UDN>\n")
            append("<presentationURL>").append(escape(base)).append("</presentationURL>\n")
            append("<serviceList>\n")
            appendService(ID_AVTRANSPORT, AVTRANSPORT, "AVTransport")
            appendService(ID_RENDERING_CONTROL, RENDERING_CONTROL, "RenderingControl")
            appendService(ID_CONNECTION_MANAGER, CONNECTION_MANAGER, "ConnectionManager")
            append("</serviceList>\n")
            append("</device>\n")
            append("</root>\n")
        }
    }

    private fun StringBuilder.appendService(id: String, type: String, short: String) {
        append("<service>")
            .append("<serviceType>").append(type).append("</serviceType>")
            .append("<serviceId>").append(id).append("</serviceId>")
            .append("<SCPDURL>/scpd/").append(short).append(".xml</SCPDURL>")
            .append("<controlURL>/ctl/").append(short).append("</controlURL>")
            .append("<eventSubURL>/evt/").append(short).append("</eventSubURL>")
            .append("</service>\n")
    }

    /** 三个服务声明的动作（含入参/出参），控制点据此决定能下发什么、期待读回什么。 */
    private data class Arg(val name: String, val related: String)

    private data class Action(val name: String, val inArgs: List<Arg> = emptyList(), val outArgs: List<Arg> = emptyList())

    private val INSTANCE = Arg("InstanceID", "A_ARG_TYPE_InstanceID")

    private val AVTRANSPORT_ACTIONS = listOf(
        Action("SetAVTransportURI", listOf(INSTANCE, Arg("CurrentURI", "A_ARG_TYPE_URI"), Arg("CurrentURIMetaData", "A_ARG_TYPE_URI"))),
        Action(
            "GetTransportInfo", listOf(INSTANCE),
            listOf(Arg("CurrentTransportState", "TransportState"), Arg("CurrentTransportStatus", "TransportStatus"), Arg("CurrentSpeed", "TransportPlaySpeed")),
        ),
        Action(
            "GetPositionInfo", listOf(INSTANCE),
            listOf(
                Arg("Track", "CurrentTrack"), Arg("TrackDuration", "CurrentTrackDuration"),
                Arg("TrackMetaData", "CurrentTrackMetaData"), Arg("TrackURI", "CurrentTrackURI"),
                Arg("RelTime", "A_ARG_TYPE_PositionLastUI4"), Arg("RelCount", "A_ARG_TYPE_PositionLastUI4"),
                Arg("AbsTime", "A_ARG_TYPE_PositionLastUI4"), Arg("AbsCount", "A_ARG_TYPE_PositionLastUI4"),
            ),
        ),
        Action(
            "GetTransportSettings", listOf(INSTANCE),
            listOf(Arg("PlayMode", "TransportPlayMode"), Arg("RecQualityMode", "A_ARG_TYPE_RecQualityMode")),
        ),
        Action(
            "GetDeviceCapabilities", listOf(INSTANCE),
            listOf(Arg("PrintSink", "A_ARG_TYPE_PrintSink"), Arg("InputSources", "A_ARG_TYPE_InputSources"), Arg("OutputPorts", "A_ARG_TYPE_OutputPorts")),
        ),
        Action("Play", listOf(INSTANCE, Arg("Speed", "TransportPlaySpeed"))),
        Action("Pause", listOf(INSTANCE)),
        Action("Stop", listOf(INSTANCE)),
        Action("Seek", listOf(INSTANCE, Arg("SeekMode", "A_ARG_TYPE_SeekMode"), Arg("Target", "A_ARG_TYPE_SeekTarget"))),
        Action(
            "GetMediaInfo", listOf(INSTANCE),
            listOf(
                Arg("NrTracks", "NumberOfTracks"), Arg("MediaDuration", "CurrentTrackDuration"),
                Arg("CurrentURI", "A_ARG_TYPE_URI"), Arg("NextURI", "A_ARG_TYPE_URI"),
                Arg("TrackMetaData", "CurrentTrackMetaData"),
            ),
        ),
        Action("GetCurrentTransportActions", listOf(INSTANCE), listOf(Arg("Actions", "CurrentTransportActions"))),
        Action("SetPlayMode", listOf(INSTANCE, Arg("NewPlayMode", "TransportPlayMode"))),
        Action("GetPlayMode", listOf(INSTANCE), listOf(Arg("PlayMode", "TransportPlayMode"))),
        Action("Next", listOf(INSTANCE)),
        Action("Previous", listOf(INSTANCE)),
    )

    private val RENDERING_CONTROL_ACTIONS = listOf(
        Action("GetVolume", listOf(INSTANCE, Arg("Channel", "A_ARG_TYPE_Channel")), listOf(Arg("CurrentVolume", "Volume"))),
        Action("SetVolume", listOf(INSTANCE, Arg("Channel", "A_ARG_TYPE_Channel"), Arg("DesiredVolume", "Volume"))),
        Action("GetMute", listOf(INSTANCE, Arg("Channel", "A_ARG_TYPE_Channel")), listOf(Arg("CurrentMute", "Mute"))),
        Action("SetMute", listOf(INSTANCE, Arg("Channel", "A_ARG_TYPE_Channel"), Arg("DesiredMute", "Mute"))),
        Action("ListPresets", listOf(INSTANCE, Arg("Channel", "A_ARG_TYPE_Channel")), listOf(Arg("PresetNameList", "A_ARG_TYPE_PresetNameList"))),
    )

    private val CONNECTION_MANAGER_ACTIONS = listOf(
        Action("GetProtocolInfo", outArgs = listOf(Arg("Source", "SourceProtocols"), Arg("Sink", "SinkProtocols"))),
        Action("GetCurrentConnectionIDs", outArgs = listOf(Arg("ConnectionIDs", "ConnectionIDs"))),
        Action(
            "GetCurrentConnectionInfo",
            listOf(INSTANCE, Arg("ConnectionID", "A_ARG_TYPE_ConnectionID"), Arg("RcgControl", "A_ARG_TYPE_RcgControl")),
            listOf(Arg("ConnectionReference", "A_ARG_TYPE_ConnectionReference"), Arg("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"), Arg("ConnectionStatus", "A_ARG_TYPE_ConnectionStatus")),
        ),
    )

    /** 只有 GetPositionInfo 之外的动作参数用 A_ARG_TYPE_* 承载，声明齐了控制点才不会判「服务不完整」。 */
    private val AVTRANSPORT_STATE_VARS = buildString {
        for (name in listOf("LastChange", "TransportState", "TransportStatus", "TransportPlayMode")) {
            append("<stateVariable sendEvents=\"yes\"><name>").append(name)
                .append("</name><dataType>string</dataType></stateVariable>\n")
        }
        for (name in listOf("CurrentTransportActions", "CurrentTrackURI", "CurrentTrackDuration", "CurrentTrackMetaData")) {
            append("<stateVariable sendEvents=\"no\"><name>").append(name)
                .append("</name><dataType>string</dataType></stateVariable>\n")
        }
        append("<stateVariable sendEvents=\"no\"><name>NumberOfTracks</name>")
            .append("<dataType>ui4</dataType></stateVariable>\n")
        append("<stateVariable sendEvents=\"no\"><name>CurrentTrack</name>")
            .append("<dataType>ui4</dataType><allowedValueRange>0,99999,1</allowedValueRange>")
            .append("</stateVariable>\n")
        for ((name, type) in listOf(
            "A_ARG_TYPE_InstanceID" to "ui4",
            "A_ARG_TYPE_URI" to "uri",
            "A_ARG_TYPE_PositionLastUI4" to "ui4",
            "A_ARG_TYPE_RecQualityMode" to "string",
            "A_ARG_TYPE_PrintSink" to "string",
            "A_ARG_TYPE_InputSources" to "string",
            "A_ARG_TYPE_OutputPorts" to "string",
            "A_ARG_TYPE_SeekMode" to "string",
            "A_ARG_TYPE_SeekTarget" to "string",
        )) {
            append("<stateVariable sendEvents=\"no\"><name>").append(name)
                .append("</name><dataType>").append(type).append("</dataType></stateVariable>\n")
        }
        append("<stateVariable sendEvents=\"no\"><name>TransportPlaySpeed</name><dataType>string</dataType>")
            .append("<allowedValueList><allowedValue>1</allowedValue><allowedValue>0</allowedValue></allowedValueList>")
            .append("</stateVariable>\n")
    }

    private val RENDERING_CONTROL_STATE_VARS = buildString {
        append("<stateVariable sendEvents=\"yes\"><name>LastChange</name>")
            .append("<dataType>string</dataType></stateVariable>\n")
        append("<stateVariable sendEvents=\"no\"><name>Volume</name>")
            .append("<dataType>ui2</dataType><allowedValueRange>0,100,0</allowedValueRange>")
            .append("</stateVariable>\n")
        append("<stateVariable sendEvents=\"no\"><name>Mute</name>")
            .append("<dataType>boolean</dataType></stateVariable>\n")
        append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_InstanceID</name>")
            .append("<dataType>ui4</dataType></stateVariable>\n")
        append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Channel</name>")
            .append("<dataType>string</dataType><allowedValueList><allowedValue>Master</allowedValue></allowedValueList>")
            .append("</stateVariable>\n")
        append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_PresetNameList</name>")
            .append("<dataType>string</dataType></stateVariable>\n")
    }

    private val CONNECTION_MANAGER_STATE_VARS = buildString {
        for ((name, type) in listOf(
            "SourceProtocols" to "string",
            "SinkProtocols" to "string",
            "ConnectionIDs" to "string",
            "A_ARG_TYPE_InstanceID" to "ui4",
            "A_ARG_TYPE_ConnectionID" to "i4",
            "A_ARG_TYPE_RcgControl" to "string",
            "A_ARG_TYPE_ConnectionReference" to "string",
            "A_ARG_TYPE_ProtocolInfo" to "string",
            "A_ARG_TYPE_ConnectionStatus" to "string",
        )) {
            append("<stateVariable sendEvents=\"no\"><name>").append(name)
                .append("</name><dataType>").append(type).append("</dataType></stateVariable>\n")
        }
    }

    /**
     * SCPD 报文。控制点多数不取，但部分老实现取不到或结构不合就判设备不可用，
     * 所以动作与状态变量的配对一次做全。
     */
    fun scpdXml(serviceId: String): String? {
        val actions: List<Action>
        val stateVars: String
        when (serviceId) {
            ID_AVTRANSPORT -> {
                actions = AVTRANSPORT_ACTIONS
                stateVars = AVTRANSPORT_STATE_VARS
            }

            ID_RENDERING_CONTROL -> {
                actions = RENDERING_CONTROL_ACTIONS
                stateVars = RENDERING_CONTROL_STATE_VARS
            }

            ID_CONNECTION_MANAGER -> {
                actions = CONNECTION_MANAGER_ACTIONS
                stateVars = CONNECTION_MANAGER_STATE_VARS
            }

            else -> return null
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n")
            append("<specVersion><major>1</major><minor>0</minor></specVersion>\n")
            append("<actionList>\n")
            for (action in actions) {
                append("<action><name>").append(action.name).append("</name>")
                if (action.inArgs.isNotEmpty() || action.outArgs.isNotEmpty()) {
                    append("<argumentList>")
                    for ((argName, related) in action.inArgs) appendArgument(argName, "in", related)
                    for ((argName, related) in action.outArgs) appendArgument(argName, "out", related)
                    append("</argumentList>")
                }
                append("</action>\n")
            }
            append("</actionList>\n")
            append("<serviceStateTable>\n").append(stateVars).append("</serviceStateTable>\n")
            append("</scpd>\n")
        }
    }

    private fun StringBuilder.appendArgument(name: String, direction: String, related: String) {
        append("<argument><name>").append(name).append("</name><direction>").append(direction)
            .append("</direction><relatedStateVariable>").append(related).append("</relatedStateVariable></argument>")
    }

    /** 取 SOAP Body 里的动作名（去命名空间前缀）；结构不合法返回 null。 */
    fun soapAction(envelope: String): String? {
        val body = elementText(envelope, "Body") ?: return null
        return childElements(body).firstOrNull()?.name
    }

    /** 取动作的一级子元素（UPnP 参数均为简单类型，不嵌套）。 */
    fun soapArgs(envelope: String): Map<String, String> {
        val body = elementText(envelope, "Body") ?: return emptyMap()
        val action = childElements(body).firstOrNull() ?: return emptyMap()
        val args = LinkedHashMap<String, String>()
        for (arg in childElements(action.text)) {
            args[arg.name] = unescape(stripCdata(arg.text).trim())
        }
        return args
    }

    /**
     * 从 Content-Type 取正文编码，认不出来一律按 UTF-8 解。
     *
     * 控制点送来的 DIDL 里带中文节目名，用 ASCII 解正文会让大屏和状态栏一起变成乱码方块。
     */
    fun charsetOf(contentType: String?): Charset {
        val text = contentType?.lowercase(Locale.US).orEmpty()
        val marker = "charset="
        val at = text.indexOf(marker)
        if (at < 0) return Charsets.UTF_8
        val name = text.substring(at + marker.length).split(';').first().trim().trim('"', '\'')
        if (name.isEmpty()) return Charsets.UTF_8
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    /** UPnP 控制走 SOAP 1.1，多数老控制点认不了 1.2 的 Code/Reason 结构。 */
    private const val SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/"

    fun soapResponse(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<s:Envelope xmlns:s=\"").append(SOAP_NS).append("\" ")
            .append("xmlns:u=\"").append(serviceType).append("\">\n")
        append("<s:Body><u:").append(action).append("Response>")
        for ((name, value) in args) {
            append("<").append(name).append(">").append(escape(value)).append("</").append(name).append(">")
        }
        append("</u:").append(action).append("Response></s:Body>\n</s:Envelope>\n")
    }

    fun soapFault(code: Int, message: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<s:Envelope xmlns:s=\"").append(SOAP_NS).append("\"><s:Body>\n")
        append("<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>")
        append("<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">")
            .append("<errorCode>").append(code).append("</errorCode>")
            .append("<errorDescription>").append(escape(message)).append("</errorDescription>")
            .append("</UPnPError></detail></s:Fault>\n</s:Body></s:Envelope>\n")
    }

    /**
     * 解析 DIDL-Lite 元数据。控制点常把整段 DIDL 再包一层 CDATA，这里两种都吃。
     * 缺 res 或 uri 为空返回 null（调用方按「无媒体」处理）。
     */
    fun parseDidl(raw: String): MediaMeta? = parseDidlItems(raw).firstOrNull()

    /**
     * 解析 DIDL-Lite 里的**全部** item（PRD FS-02 图片轮播需要一次推送多张图）。
     * 只取带 res 地址的条目：没有地址的容器对象对渲染端没有意义。
     */
    fun parseDidlItems(raw: String): List<MediaMeta> {
        val didl = stripCdata(raw)
        val top = childElements(didl)
        val direct = top.filter { it.name == "item" || it.name == "object" }
        val nodes = direct.ifEmpty {
            // 正常形态是 <DIDL-Lite> 包着 item；childElements 会把 DIDL-Lite 当成一个顶层元素
            top.filter { it.name == "DIDL-Lite" }.flatMap { childElements(it.text) }
        }
        return nodes.filter { it.name == "item" || it.name == "object" }.mapNotNull { parseItem(it.text) }
    }

    private fun parseItem(item: String): MediaMeta? {
        val children = childElements(item)
        var title = ""
        var upnpClass = ""
        var uri = ""
        var durationMs = -1L
        var mime = ""
        for (child in children) {
            when (child.name) {
                "title" -> title = unescape(stripCdata(child.text).trim())
                "class" -> upnpClass = unescape(child.text.trim())
                "res" -> {
                    uri = unescape(stripCdata(child.text).trim())
                    durationMs = child.attrs["duration"]?.let { parseClock(it) } ?: -1L
                    mime = child.attrs["protocolInfo"]?.let { protocolInfoMime(it) }.orEmpty()
                }
            }
        }
        if (uri.isEmpty()) return null
        if (title.isEmpty()) title = uri.substringAfterLast('/').substringBefore('?')
        return MediaMeta(uri, title, upnpClass, durationMs, mime)
    }

    /** 时长格式 `H:MM:SS(.ms)` / `MM:SS`，解析失败返回 -1。 */
    fun parseClock(text: String): Long {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.any { it.isEmpty() }) return -1L
        var hours = 0L
        var minutes = 0L
        var seconds = 0.0
        when (parts.size) {
            3 -> {
                hours = parts[0].toLongOrNull() ?: return -1L
                minutes = parts[1].toLongOrNull() ?: return -1L
                seconds = parts[2].toDoubleOrNull() ?: return -1L
            }
            2 -> {
                minutes = parts[0].toLongOrNull() ?: return -1L
                seconds = parts[1].toDoubleOrNull() ?: return -1L
            }
            else -> return -1L
        }
        return ((hours * 3600 + minutes * 60) * 1000L) + (seconds * 1000).toLong()
    }

    fun formatClock(ms: Long): String {
        if (ms < 0) return "00:00:00"
        val total = ms / 1000
        return "%02d:%02d:%02d".format(Locale.US, total / 3600, (total / 60) % 60, total % 60)
    }

    private fun protocolInfoMime(protocolInfo: String): String {
        val parts = protocolInfo.split(':')
        return if (parts.size >= 3) parts[2] else ""
    }

    /** 反向构造一段 DIDL-Lite，供 GetMediaInfo / GetPositionInfo 回报元数据。 */
    fun didlLite(meta: MediaMeta): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            .append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            .append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        append("<dc:title>").append(escape(meta.title)).append("</dc:title>")
        append("<upnp:class>").append(escape(meta.upnpClass.ifEmpty { "object.item.videoItem" })).append("</upnp:class>")
        append("<res protocolInfo=\"http-get:*:").append(escape(meta.mime.ifEmpty { "*"})).append(":*\"")
        if (meta.durationMs >= 0) append(" duration=\"").append(formatClock(meta.durationMs)).append('"')
        append(">").append(escape(meta.uri)).append("</res>")
        append("</item></DIDL-Lite>")
    }

    /** `http-get:*:video/mp4:*` 形式的协议串列表，用于判定可播来源。 */
    fun sinkProtocols(): String = listOf(
        "http-get:*:video/mp4:*",
        "http-get:*:video/quicktime:*",
        "http-get:*:video/mpeg:*",
        "http-get:*:video/x-matroska:*",
        "http-get:*:audio/mpeg:*",
        "http-get:*:audio/mp4:*",
        "http-get:*:audio/x-m4a:*",
        "http-get:*:image/jpeg:*",
        "http-get:*:image/png:*",
    ).joinToString(",")

    fun stripCdata(text: String): String {
        val start = text.indexOf("<![CDATA[")
        if (start < 0) return text
        val end = text.indexOf("]]>", start)
        if (end < 0) return text.substring(start + 9)
        return text.substring(start + 9, end)
    }

    /** 元素文本（取第一个同名元素，含嵌套同名时的配对处理）。 */
    fun elementText(xml: String, name: String): String? {
        var i = 0
        while (true) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) return null
            if (xml.startsWith("<!--", lt)) {
                i = xml.indexOf("-->", lt).let { if (it < 0) xml.length else it + 3 }
                continue
            }
            if (xml.startsWith("<?", lt) || xml.startsWith("<!", lt)) {
                i = xml.indexOf('>', lt).let { if (it < 0) xml.length else it + 1 }
                continue
            }
            val gt = xml.indexOf('>', lt)
            if (gt < 0) return null
            val raw = xml.substring(lt + 1, gt).trim()
            val tag = tagName(raw)
            if (tag == name && !raw.endsWith("/")) {
                val end = findClose(xml, gt + 1, name)
                return if (end < 0) xml.substring(gt + 1) else xml.substring(gt + 1, end)
            }
            i = gt + 1
        }
    }

    private fun findClose(xml: String, from: Int, name: String): Int {
        var depth = 1
        var i = from
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) return -1
            if (xml.startsWith("<!--", lt)) {
                i = xml.indexOf("-->", lt).let { if (it < 0) xml.length else it + 3 }
                continue
            }
            if (xml.startsWith("<?", lt) || xml.startsWith("<!", lt)) {
                i = xml.indexOf('>', lt).let { if (it < 0) xml.length else it + 1 }
                continue
            }
            val gt = xml.indexOf('>', lt)
            if (gt < 0) return -1
            val raw = xml.substring(lt + 1, gt).trim()
            val tag = tagName(raw)
            when {
                raw.startsWith("/") -> if (tag == name) {
                    depth--
                    if (depth == 0) return lt
                }
                raw.endsWith("/") -> Unit
                tag == name -> depth++
            }
            i = gt + 1
        }
        return -1
    }

    /** 一级子元素：名字（去前缀）、开标签属性（去前缀）、内部文本。 */
    data class Element(val name: String, val attrs: Map<String, String>, val text: String)

    fun childElements(xml: String): List<Element> {
        val out = ArrayList<Element>()
        var i = 0
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            if (xml.startsWith("<!--", lt)) {
                i = xml.indexOf("-->", lt).let { if (it < 0) xml.length else it + 3 }
                continue
            }
            if (xml.startsWith("<?", lt) || xml.startsWith("<!", lt)) {
                i = xml.indexOf('>', lt).let { if (it < 0) xml.length else it + 1 }
                continue
            }
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break
            val raw = xml.substring(lt + 1, gt).trim()
            if (raw.startsWith("/")) {
                i = gt + 1
                continue
            }
            val tag = tagName(raw)
            if (tag.isEmpty()) {
                i = gt + 1
                continue
            }
            // 属性只在开标签上（DIDL 的 res 带 duration/protocolInfo/size）
            val attrs = attributes(raw)
            if (raw.endsWith("/")) {
                out += Element(tag, attrs, "")
                i = gt + 1
                continue
            }
            val end = findClose(xml, gt + 1, tag)
            if (end < 0) {
                out += Element(tag, attrs, xml.substring(gt + 1))
                break
            }
            out += Element(tag, attrs, xml.substring(gt + 1, end))
            val closeGt = xml.indexOf('>', end)
            i = if (closeGt < 0) xml.length else closeGt + 1
        }
        return out
    }

    private val attrPattern = Regex("([A-Za-z_][A-Za-z0-9_.:-]*)\\s*=\\s*\"([^\"]*)\"")

    private fun attributes(openTag: String): Map<String, String> =
        attrPattern.findAll(openTag).associate { localName(it.groupValues[1]) to unescape(it.groupValues[2]) }

    /** 开标签名：去命名空间前缀，也要吃掉自闭合标签尾巴上的 `/`。 */
    private fun tagName(openTag: String): String = localName(openTag.substringBefore(' ').removeSuffix("/"))

    private fun localName(tag: String): String {
        val name = if (tag.startsWith("/")) tag.substring(1) else tag
        val colon = name.indexOf(':')
        return if (colon < 0) name else name.substring(colon + 1)
    }
}

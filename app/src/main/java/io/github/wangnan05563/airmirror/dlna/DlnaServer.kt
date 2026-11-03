package io.github.wangnan05563.airmirror.dlna

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import io.github.wangnan05563.airmirror.media.DlnaPlayer
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/**
 * DLNA 媒体渲染端（DMR）：SSDP 广播 + HTTP 描述文件 + SOAP 控制 + MediaPlayer 播放。
 *
 * 只做 M0「DLNA 推送跑通」所需的最小子集：AVTransport / RenderingControl / ConnectionManager
 * 三个服务，GENA 事件只接受订阅不推送（控制点按 GetPositionInfo 轮询取状态）。
 */
class DlnaServer(
    context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val machine = TransportMachine()
    private val player = DlnaPlayer()
    private val uuid: String = persistedUuid(appContext)
    private val types = listOf(
        Upnp.AVTRANSPORT,
        Upnp.RENDERING_CONTROL,
        Upnp.CONNECTION_MANAGER,
        "urn:schemas-upnp-org:device:MediaRenderer:1",
    )

    private var server: ServerSocket? = null
    private var responder: SsdpResponder? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var volume = 60

    @Volatile
    private var muted = false

    val httpPort: Int get() = server?.localPort ?: 0

    /** 界面据此收起待连接页：投屏中整屏只保留画面。 */
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    init {
        player.onPreparedCallback = {
            machine.onLoaded()
            onStatus("DLNA 已载入 ${machine.media?.title.orEmpty()}")
        }
        player.onCompletedCallback = {
            machine.onCompleted(loop = false)
            onPlayingChanged?.invoke(false)
            onStatus("DLNA 播放结束")
        }
        player.onErrorCallback = { what, extra ->
            // 状态不回落，控制点就会一直读到 PLAYING 并接着轮询进度；大屏则完全不知道出了什么事
            machine.onPlaybackFailed()
            onPlayingChanged?.invoke(false)
            val text = PlayerFailure.textFor(what, extra)
            Log.w(TAG, "playback failed what=$what extra=$extra -> $text")
            onStatus("DLNA 播放失败 · $text")
        }
    }

    fun start(friendlyName: String) {
        if (running) return
        val socket = ServerSocket(0).apply { reuseAddress = true }
        server = socket
        running = true
        val ip = localIpv4() ?: "127.0.0.1"
        val location = "http://$ip:${socket.localPort}/description.xml"
        responder = SsdpResponder(appContext, location, "uuid:$uuid", types).also {
            it.start()
            it.startAliveBeacon()
        }
        worker = thread(name = "airmirror-dlna") { acceptLoop(socket, friendlyName, ip) }
        onStatus("DLNA 就绪 · 可接收推送")
        Log.i(TAG, "DMR up at $location")
    }

    fun attach(holder: SurfaceHolder) {
        player.attach(holder)
    }

    fun detach() {
        player.attach(null)
    }

    private fun acceptLoop(socket: ServerSocket, friendlyName: String, ip: String) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${e.message}") else return
                continue
            }
            thread(name = "airmirror-dlna-conn", isDaemon = true) {
                runCatching { serve(client, friendlyName, ip) }
                    .onFailure { Log.w(TAG, "request failed: ${it.message}") }
                    .also { runCatching { client.close() } }
            }
        }
    }

    private fun serve(client: Socket, friendlyName: String, ip: String) {
        val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase(Locale.US)
        val path = parts[1]
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        val body = headers["content-length"]?.toIntOrNull()?.let { length ->
            if (length <= 0 || length > MAX_BODY) "" else reader.readText(length)
        }.orEmpty()

        if (method == "SUBSCRIBE" && path.substringBefore('?').startsWith("/evt/")) {
            // GENA 订阅：接受但不推事件，控制点靠 GetPositionInfo 轮询取状态
            write(client, 200, "text/plain", "", extra = listOf(
                "SID: uuid:$uuid-${serviceOf(path)}",
                "TIMEOUT: ${headers["timeout"].orEmpty().ifEmpty { "Second-1800" }}",
            ))
            return
        }
        val response = route(method, path, body, friendlyName, ip) ?: return write(client, 404, "text/plain", "not found")
        write(client, response.first, response.second, response.third)
    }

    private fun serviceOf(path: String): String = path.substringAfter("/evt/").substringBefore('?')

    private fun route(method: String, path: String, body: String, friendlyName: String, ip: String): Triple<Int, String, String>? {
        val clean = path.substringBefore('?')
        return when {
            method == "GET" && (clean == "/description.xml" || clean == "/root.xml" || clean == "/device.xml") ->
                Triple(
                    200, "text/xml",
                    Upnp.deviceXml(Upnp.Device(uuid, friendlyName, "http://$ip:$httpPort/")),
                )

            method == "GET" && clean.startsWith("/scpd/") -> {
                val serviceId = when (clean.substringAfterLast('/').removeSuffix(".xml")) {
                    "AVTransport" -> Upnp.ID_AVTRANSPORT
                    "RenderingControl" -> Upnp.ID_RENDERING_CONTROL
                    "ConnectionManager" -> Upnp.ID_CONNECTION_MANAGER
                    else -> ""
                }
                val xml = Upnp.scpdXml(serviceId)
                if (xml == null) null else Triple(200, "text/xml", xml)
            }

            method == "GET" && clean == "/" -> Triple(200, "text/html", page(friendlyName))

            method == "POST" && clean == "/ctl/AVTransport" ->
                Triple(200, "text/xml", handleSoap(Upnp.AVTRANSPORT, body))

            method == "POST" && clean == "/ctl/RenderingControl" ->
                Triple(200, "text/xml", handleSoap(Upnp.RENDERING_CONTROL, body))

            method == "POST" && clean == "/ctl/ConnectionManager" ->
                Triple(200, "text/xml", handleSoap(Upnp.CONNECTION_MANAGER, body))

            else -> null
        }
    }

    private fun page(friendlyName: String): String =
        "<html><body><h1>$friendlyName</h1><p>AirMirror DLNA renderer</p></body></html>"

    private fun handleSoap(serviceType: String, body: String): String {
        val action = Upnp.soapAction(body)
            ?: return Upnp.soapFault(Upnp.FAULT_INVALID_ACTION, "missing soap action")
        val args = Upnp.soapArgs(body)
        return try {
            val result: List<Pair<String, String>> = when (serviceType) {
                Upnp.AVTRANSPORT -> avTransport(action, args)
                Upnp.RENDERING_CONTROL -> renderingControl(action, args)
                else -> connectionManager(action)
            }
            Upnp.soapResponse(serviceType, action, result)
        } catch (e: SoapError) {
            Log.i(TAG, "$action failed: ${e.message}")
            Upnp.soapFault(e.code, e.message)
        }
    }

    /** 动作处理返回「应答参数」；不可执行时抛 SoapError，由 handleSoap 统一封装成 Fault。 */
    private fun avTransport(action: String, args: Map<String, String>): List<Pair<String, String>> = when (action) {
        "SetAVTransportURI" -> {
            val uri = args["CurrentURI"].orEmpty()
            if (uri.isEmpty()) throw SoapError(Upnp.FAULT_INVALID_ARGS, "empty CurrentURI")
            val parsed = Upnp.parseDidl(args["CurrentURIMetaData"].orEmpty())
            val meta = parsed ?: Upnp.MediaMeta(uri, titleFromUri(uri), "", -1L, "")
            if (meta.upnpClass.startsWith("object.item.imageItem")) {
                throw SoapError(Upnp.FAULT_ACTION_FAILED, "image push is not implemented in M0")
            }
            machine.setUri(meta)
            if (!player.load(uri)) throw SoapError(Upnp.FAULT_ACTION_FAILED, player.lastError ?: "load failed")
            onStatus("DLNA 载入 ${meta.title}")
            emptyList()
        }

        "Play" -> {
            machine.canPlay()?.let { throw SoapError(it, "no media present") }
            player.play()
            machine.onPlayInvoked()
            onPlayingChanged?.invoke(true)
            emptyList()
        }

        "Pause" -> {
            machine.canPauseOrSeek()?.let { throw SoapError(it, "not playing") }
            player.pause()
            machine.onPauseInvoked()
            onPlayingChanged?.invoke(false)
            emptyList()
        }

        "Stop" -> {
            player.stop()
            machine.onStopInvoked()
            onPlayingChanged?.invoke(false)
            emptyList()
        }

        "Seek" -> {
            machine.canPauseOrSeek()?.let { throw SoapError(it, "not seekable") }
            val mode = args["SeekMode"].orEmpty()
            val target = args["Target"].orEmpty()
            val ms = when (mode) {
                "ABS_TIME", "REL_TIME" -> Upnp.parseClock(target)
                else -> throw SoapError(Upnp.FAULT_SEEK_MODE_NOT_SUPPORTED, "seek mode $mode unsupported")
            }
            if (ms < 0) throw SoapError(Upnp.FAULT_INVALID_ARGS, "bad seek target")
            player.seekTo(ms)
            emptyList()
        }

        "GetTransportInfo" -> listOf(
            "CurrentTransportState" to machine.transportState(),
            "CurrentTransportStatus" to machine.transportStatus(),
            "CurrentSpeed" to machine.currentSpeed(),
        )

        "GetPositionInfo" -> {
            val duration = player.durationMs().let { if (it > 0) it else machine.media?.durationMs ?: -1L }
            val position = player.positionMs()
            listOf(
                "Track" to "1",
                "TrackDuration" to clock(duration),
                "TrackMetaData" to machine.media?.let { Upnp.didlLite(it) }.orEmpty(),
                "TrackURI" to machine.media?.uri.orEmpty(),
                "RelTime" to clock(position),
                "RelCount" to "-1",
                "AbsTime" to clock(position),
                "AbsCount" to "-1",
            )
        }

        "GetMediaInfo" -> listOf(
            "NrTracks" to "1",
            "MediaDuration" to clock(machine.media?.durationMs ?: -1L),
            "CurrentURI" to (machine.media?.uri ?: "NULL"),
            "NextURI" to "NULL",
            "TrackMetaData" to machine.media?.let { Upnp.didlLite(it) }.orEmpty(),
        )

        "GetTransportSettings" -> listOf("PlayMode" to machine.playMode, "RecQualityMode" to "NOT_IMPLEMENTED")
        "SetPlayMode" -> {
            machine.setPlayMode(args["NewPlayMode"].orEmpty())?.let { throw SoapError(it, "bad play mode") }
            emptyList()
        }

        "GetPlayMode" -> listOf("PlayMode" to machine.playMode)
        "GetDeviceCapabilities" -> listOf(
            "PrintSink" to "",
            "InputSources" to "",
            "OutputPorts" to "",
        )

        "GetCurrentTransportActions" -> listOf("Actions" to machine.currentActions())
        "Next", "Previous" -> throw SoapError(Upnp.FAULT_ACTION_FAILED, "single item only")
        else -> throw SoapError(Upnp.FAULT_INVALID_ACTION, "unknown action $action")
    }

    private fun renderingControl(action: String, args: Map<String, String>): List<Pair<String, String>> = when (action) {
        "GetVolume" -> listOf("CurrentVolume" to volume.toString())
        "SetVolume" -> {
            val desired = args["DesiredVolume"]?.toIntOrNull()
                ?: throw SoapError(Upnp.FAULT_INVALID_ARGS, "bad DesiredVolume")
            volume = desired.coerceIn(0, 100)
            player.setVolume(volume, muted)
            emptyList()
        }

        "GetMute" -> listOf("CurrentMute" to if (muted) "1" else "0")
        "SetMute" -> {
            muted = args["DesiredMute"]?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
            player.setVolume(volume, muted)
            emptyList()
        }

        "ListPresets" -> listOf("PresetNameList" to "")
        else -> throw SoapError(Upnp.FAULT_INVALID_ACTION, "unknown action $action")
    }

    private fun connectionManager(action: String): List<Pair<String, String>> = when (action) {
        "GetProtocolInfo" -> listOf("Source" to "", "Sink" to Upnp.sinkProtocols())
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> listOf(
            "ConnectionReference" to "",
            "ProtocolInfo" to "",
            "ConnectionStatus" to "OK",
        )

        else -> throw SoapError(Upnp.FAULT_INVALID_ACTION, "unknown action $action")
    }

    private fun titleFromUri(uri: String): String =
        uri.substringAfterLast('/').substringBefore('?').ifEmpty { uri }

    private fun clock(ms: Long): String = if (ms < 0) "00:00:00" else Upnp.formatClock(ms)

    private fun write(client: Socket, status: Int, contentType: String, payload: String, extra: List<String> = emptyList()) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(if (status == 200) " OK" else " Error").append("\r\n")
            append("Content-Type: ").append(contentType).append("; charset=\"utf-8\"\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Server: ").append(Ssdp.SERVER).append("\r\n")
            append("EXT: \r\n")
            for (line in extra) append(line).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    fun stop() {
        running = false
        responder?.stop()
        responder = null
        runCatching { server?.close() }
        server = null
        player.release()
        worker?.join(1_000)
        worker = null
        Log.i(TAG, "DMR stopped")
    }

    private class SoapError(val code: Int, override val message: String) : Exception(message)

    private companion object {
        const val TAG = "AirMirrorDlna"
        const val MAX_BODY = 64 * 1024
        const val PREFS = "airmirror-dlna"
        const val PREF_UUID = "uuid"

        fun persistedUuid(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { prefs ->
                prefs.getString(PREF_UUID, null) ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString(PREF_UUID, it).apply()
                }
            }
    }
}

/** 按字节数读取剩余内容（HTTP body 长度已知，避免 readText() 卡在 keep-alive 上）。 */
private fun java.io.Reader.readText(count: Int): String {
    val chars = CharArray(count)
    var read = 0
    while (read < count) {
        val n = read(chars, read, count - read)
        if (n < 0) break
        read += n
    }
    return String(chars, 0, read)
}

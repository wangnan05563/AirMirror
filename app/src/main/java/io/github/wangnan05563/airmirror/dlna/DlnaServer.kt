package io.github.wangnan05563.airmirror.dlna

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import io.github.wangnan05563.airmirror.error.ErrorNotice
import io.github.wangnan05563.airmirror.media.DlnaPlayer
import io.github.wangnan05563.airmirror.media.ImageFetcher
import io.github.wangnan05563.airmirror.privacy.Redactor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
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

    /**
     * 浮层提示通道：投屏中待连接页已经让位给画面，`onStatus` 那种写在页面上的字看不见，
     * 「画面未就绪」这类要让用户当场看到的话走这里（与镜像侧的接管浮层同一渲染路径）。
     */
    var onNotice: ((String) -> Unit)? = null

    /**
     * 有控制点请求进来（描述文件 / SOAP / GENA 订阅）。C 级的闲置释放靠它翻新计时（PRD 1152）：
     * 只要还有人来找这台设备，就不能算「闲置」。
     */
    var onActivity: (() -> Unit)? = null

    /** PRD FS-02 图片推送：画面不走 Surface，交给界面上的 ImageView 渲染。 */
    var onImageShow: ((Bitmap, String) -> Unit)? = null
    var onImageClear: (() -> Unit)? = null

    /** 已经提示过「画面未就绪」，等 Surface 回来收口；只在 SOAP 线程与主线程读改写，故 Volatile */
    @Volatile
    private var awaitingSurface = false

    /** 图片轮播/切换的定时器；与 Surface 渲染无关，只在主线程队列里排程。 */
    private val main = Handler(Looper.getMainLooper())
    private var imageRotation: Runnable? = null

    /** 当前图与它的片名；控制点 Next/Previous 或 15 秒轮播后立刻换掉。 */
    @Volatile
    private var imageBitmap: Bitmap? = null
    @Volatile
    private var imageTitle: String = ""

    /**
     * 取图请求的代号：切图/换源/Stop 都会让它自增，迟到的下载结果据此丢弃，
     * 否则慢的那张图会在新图之后才上屏（控制点眼里就是「切了又跳回去」）。
     */
    @Volatile
    private var imageToken = 0

    /** 是否有取图请求在途；Play 据此决定「排队等这张」还是「重发一次」。 */
    @Volatile
    private var imageLoading = false

    init {
        player.onPreparedCallback = {
            machine.onLoaded()
            onStatus("DLNA 已载入 ${machine.media?.title.orEmpty()}")
            // Play 早于就绪时提示是在 Play 那一刻打的，那时还没有画面可言；
            // 真正 start 之后再说一次，才能区分「还在取流」和「在放但没面」
            warnIfNoSurface()
        }
        player.onCompletedCallback = {
            val loop = machine.loopEnabled
            machine.onCompleted(loop = loop)
            if (loop) {
                // MediaPlayer 自带 looping 时正常不会走到这里；个别机型仍会报一次 PlaybackCompleted，
                // 这时补一次 start（PlaybackCompleted 状态下 start 会从头播），免得循环在第二遍断掉
                player.play()
            } else {
                awaitingSurface = false
                onPlayingChanged?.invoke(false)
                onStatus("DLNA 播放结束")
            }
        }
        player.onErrorCallback = { what, extra ->
            // 状态不回落，控制点就会一直读到 PLAYING 并接着轮询进度；大屏则完全不知道出了什么事
            machine.onPlaybackFailed()
            awaitingSurface = false
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
        // 描述文件地址是 URL，按 14.4 不写日志；排查只需要端口
        Log.i(TAG, "DMR up on port ${socket.localPort}")
    }

    fun attach(holder: SurfaceHolder) {
        player.attach(holder)
        announceSurfaceBack()
    }

    fun detach() {
        player.attach(null)
        // 播放中途被摘掉面（按 Home / 息屏）同样要说一句：不然后台只剩「在播但没画面」这种没人知道的状态
        warnIfNoSurface(hasSurface = false)
    }

    /**
     * 推送正在放、但接收端此刻没有可用渲染面（退后台 / 息屏）。
     *
     * 这不是失败：有声片源照常出声，回到前台 Surface 重建后画面自动接上。
     * 但也不能默默当没事——判据见 [RenderSurfaceWatch]，这里只负责把它说出去。
     */
    private fun warnIfNoSurface(hasSurface: Boolean = player.surfaceReady) {
        val cause = RenderSurfaceWatch.causeFor(machine.media?.upnpClass, machine.wantsPlayback, hasSurface)
            ?: return
        awaitingSurface = true
        val text = ErrorNotice.textFor(cause)
        // 片名属于用户内容：日志里连标题都不写；大屏提示仍走 onStatus 展示原文
        Log.w(TAG, "playing without a render surface")
        onStatus("DLNA 播放中 · $text")
        onNotice?.invoke("DLNA $text")
    }

    /** Surface 回来时把「画面未就绪」的提示收掉，否则大屏会一直挂着一条已经过时的话。 */
    private fun announceSurfaceBack() {
        if (!awaitingSurface) return
        awaitingSurface = false
        val title = machine.media?.title.orEmpty()
        Log.i(TAG, "render surface back, picture resumes")
        onStatus("DLNA 播放中 · 画面已接上 $title")
        // 提示要在人回到屏幕前的那一刻出现，而不是留在后台没人看见的那三秒
        if (machine.wantsPlayback) onNotice?.invoke("DLNA 画面已接上 $title")
    }

    private fun acceptLoop(socket: ServerSocket, friendlyName: String, ip: String) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${Redactor.throwable(e)}") else return
                continue
            }
            thread(name = "airmirror-dlna-conn", isDaemon = true) {
                runCatching { serve(client, friendlyName, ip) }
                    .onFailure { Log.w(TAG, "request failed: ${Redactor.throwable(it)}") }
                    .also { runCatching { client.close() } }
            }
        }
    }

    private fun serve(client: Socket, friendlyName: String, ip: String) {
        onActivity?.invoke()
        // 逐字节读头：BufferedReader 会预读，把 UTF-8 正文吃进缓冲区后就没法按原编码解了
        val input = client.getInputStream()
        val requestLine = input.readAsciiLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase(Locale.US)
        val path = parts[1]
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = input.readAsciiLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        val body = headers["content-length"]?.toIntOrNull()?.let { length ->
            if (length <= 0 || length > MAX_BODY) ""
            else input.readBody(length, Upnp.charsetOf(headers["content-type"]))
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
            Log.i(TAG, "$action failed: ${Redactor.throwable(e)}")
            Upnp.soapFault(e.code, e.message)
        }
    }

    /** 动作处理返回「应答参数」；不可执行时抛 SoapError，由 handleSoap 统一封装成 Fault。 */
    private fun avTransport(action: String, args: Map<String, String>): List<Pair<String, String>> = when (action) {
        "SetAVTransportURI" -> {
            val uri = args["CurrentURI"].orEmpty()
            if (uri.isEmpty()) throw SoapError(Upnp.FAULT_INVALID_ARGS, "empty CurrentURI")
            val items = Upnp.parseDidlItems(args["CurrentURIMetaData"].orEmpty())
            val meta = items.firstOrNull() ?: Upnp.MediaMeta(uri, titleFromUri(uri), "", -1L, "")
            if (MediaKinds.of(meta) == MediaKind.IMAGE) {
                // 图片可能一次推多张：只保留图片项，混进来的其它类型会破坏 15 秒轮播的计数
                val images = items.filter { MediaKinds.of(it) == MediaKind.IMAGE }
                prepareImages(if (images.isEmpty()) listOf(meta) else images)
            } else {
                machine.setUri(meta)
                if (!player.load(uri)) throw SoapError(Upnp.FAULT_ACTION_FAILED, player.lastError ?: "load failed")
                onStatus("DLNA 载入 ${meta.title}")
            }
            emptyList()
        }

        "Play" -> {
            machine.canPlay()?.let { throw SoapError(it, "no media present") }
            if (isImage()) {
                machine.onPlayInvoked()
                val bitmap = imageBitmap
                if (bitmap == null) {
                    // 图还没到手：状态留在 TRANSITIONING（onPlayInvoked 只记意图），
                    // 只有确实没有在途请求时才重发一次取图
                    if (!imageLoading) {
                        machine.reloadCurrent()
                        fetchImage(machine.tracks, machine.index)
                    }
                } else {
                    onImageShow?.invoke(bitmap, imageTitle)
                    onPlayingChanged?.invoke(true)
                    startImageRotation()
                }
            } else {
                player.play()
                machine.onPlayInvoked()
                onPlayingChanged?.invoke(true)
                Log.i(TAG, "play: surfaceReady=${player.surfaceReady} prepared=${player.prepared}")
                warnIfNoSurface()
            }
            emptyList()
        }

        "Pause" -> {
            machine.canPauseOrSeek()?.let { throw SoapError(it, "not playing") }
            if (isImage()) {
                // 暂停只是停住轮播，画面留在屏上：收回待连接页会让人以为推送已经结束
                stopImageRotation()
                machine.onPauseInvoked()
            } else {
                player.pause()
                machine.onPauseInvoked()
                awaitingSurface = false
                onPlayingChanged?.invoke(false)
            }
            emptyList()
        }

        "Stop" -> {
            stopImageRotation()
            val image = isImage()
            player.stop()
            machine.onStopInvoked()
            if (image) clearImage()
            awaitingSurface = false
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
            // 图片没有播放进度：报 0 而不是把上一段视频的毫秒数带过去
            val duration = if (isImage()) -1L
            else player.durationMs().let { if (it > 0) it else machine.media?.durationMs ?: -1L }
            val position = if (isImage()) 0L else player.positionMs()
            listOf(
                "Track" to (machine.index + 1).toString(),
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
            "NrTracks" to machine.trackCount.toString(),
            "MediaDuration" to clock(machine.media?.durationMs ?: -1L),
            "CurrentURI" to (machine.media?.uri ?: "NULL"),
            "NextURI" to (machine.tracks.getOrNull(machine.index + 1)?.uri ?: "NULL"),
            "TrackMetaData" to machine.media?.let { Upnp.didlLite(it) }.orEmpty(),
        )

        "GetTransportSettings" -> listOf("PlayMode" to machine.playMode, "RecQualityMode" to "NOT_IMPLEMENTED")
        "SetPlayMode" -> {
            machine.setPlayMode(args["NewPlayMode"].orEmpty())?.let { throw SoapError(it, "bad play mode") }
            // 循环开关要立刻作用到正在放的媒体，不能等下一次 SetAVTransportURI
            player.setLooping(machine.loopEnabled)
            emptyList()
        }

        "GetPlayMode" -> listOf("PlayMode" to machine.playMode)
        "GetDeviceCapabilities" -> listOf(
            "PrintSink" to "",
            "InputSources" to "",
            "OutputPorts" to "",
        )

        "GetCurrentTransportActions" -> listOf("Actions" to machine.currentActions())
        "Next", "Previous" -> {
            // 单文件（视频/音频/单张图）没有上一条下一条；多图队列才有
            if (!isImage() || machine.trackCount <= 1) {
                throw SoapError(Upnp.FAULT_ACTION_FAILED, "single item only")
            }
            val target = if (action == "Next") machine.index + 1 else machine.index - 1
            if (!machine.select(target)) throw SoapError(Upnp.FAULT_ACTION_FAILED, "no more items")
            stopImageRotation()
            fetchImage(machine.tracks, target)
            emptyList()
        }
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

    // ---- 图片推送（PRD FS-02：15 秒轮播 / 手动切换） ----

    private fun isImage(meta: Upnp.MediaMeta? = machine.media): Boolean =
        meta != null && MediaKinds.of(meta) == MediaKind.IMAGE

    /**
     * 浏览器访客上传的本地文件直接播放（PRD §5.9 浏览器直连）。
     *
     * 走与 DLNA SetAVTransportURI 相同的机器与播放器，只是 URI 换成 `file://`：
     * 上传文件已经在本机缓存，再绕一圈 URL 只会多一次磁盘到网络的往返。
     * 调用方负责先确认文件类型；图片复用 15 秒轮播的既有链路。
     */
    fun playLocal(file: File, title: String, mime: String): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        val uri = "file://" + file.absolutePath
        val meta = Upnp.MediaMeta(uri, title, mime, file.length(), "")
        if (MediaKinds.of(meta) == MediaKind.IMAGE) {
            prepareImages(listOf(meta))
            machine.onPlayInvoked()
            return true
        }
        // 换本地文件同样要让上一张图退出：否则图片层会压在视频上
        clearImage()
        machine.setUri(meta)
        if (!player.load(uri)) return false
        machine.onPlayInvoked()
        player.play()
        onPlayingChanged?.invoke(true)
        onStatus("浏览器上传 ${meta.title}")
        return true
    }

    /** 接收一组图片：图片不走 MediaPlayer，上一个视频的播放器要立刻放掉，别占着解码器。 */
    private fun prepareImages(items: List<Upnp.MediaMeta>) {
        stopImageRotation()
        clearImage()
        player.release()
        machine.setPlaylist(items)
        onStatus("DLNA 载入 ${items.first().title}")
        fetchImage(items, 0)
    }

    private fun fetchImage(items: List<Upnp.MediaMeta>, index: Int) {
        val meta = items.getOrNull(index) ?: return
        val token = ++imageToken
        imageLoading = true
        thread(name = "airmirror-dlna-image") {
            val bitmap = ImageFetcher.fetch(meta.uri)
            main.post {
                // 已经被换源 / 切图 / Stop 取代的请求到此为止
                if (token != imageToken) return@post
                imageLoading = false
                if (bitmap == null) {
                    failImage()
                } else {
                    imageBitmap = bitmap
                    imageTitle = meta.title
                    machine.onLoaded()
                    // 首次推图还没 Play 时留在待连接页；手动切换或已在播时立刻换画面
                    if (machine.wantsPlayback || machine.state == TransportMachine.State.PAUSED) {
                        onImageShow?.invoke(bitmap, meta.title)
                        if (machine.state == TransportMachine.State.PLAYING) {
                            onPlayingChanged?.invoke(true)
                            startImageRotation()
                        }
                    }
                    onStatus("DLNA 图片 ${meta.title}")
                }
            }
        }
    }

    private fun failImage() {
        machine.onPlaybackFailed()
        awaitingSurface = false
        onPlayingChanged?.invoke(false)
        clearImage()
        val text = ErrorNotice.textFor(ErrorNotice.Cause.SOURCE_NOT_FOUND)
        // 片名与地址都属于用户内容：日志只记事件本身
        Log.w(TAG, "image fetch failed")
        onStatus("DLNA 图片显示失败 · $text")
        onNotice?.invoke("DLNA $text")
    }

    private fun clearImage() {
        imageToken++
        imageLoading = false
        imageBitmap = null
        imageTitle = ""
        onImageClear?.invoke()
    }

    private fun startImageRotation() {
        stopImageRotation()
        if (!ImageRotation.shouldRotate(MediaKind.IMAGE, machine.trackCount, machine.state == TransportMachine.State.PLAYING)) return
        val ticker = object : Runnable {
            override fun run() {
                val next = ImageRotation.nextIndex(machine.index, machine.trackCount) ?: return
                if (!machine.select(next)) return
                fetchImage(machine.tracks, next)
                main.postDelayed(this, ImageRotation.INTERVAL_MS)
            }
        }
        imageRotation = ticker
        main.postDelayed(ticker, ImageRotation.INTERVAL_MS)
    }

    private fun stopImageRotation() {
        imageRotation?.let { main.removeCallbacks(it) }
        imageRotation = null
    }

    /** 界面重新挂载时把当前图补回去（后台收到的图片推送不会因为 Activity 重建就丢掉）。 */
    fun replayImage() {
        val bitmap = imageBitmap ?: return
        onImageShow?.invoke(bitmap, imageTitle)
    }

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
        stopImageRotation()
        clearImage()
        player.release()
        // 会话内容只活在内存：服务停掉时把片名/片源地址一并清掉，不留给下次启动
        machine.clear()
        worker?.join(1_000)
        worker = null
        Log.i(TAG, "DMR stopped")
    }

    /** 这一刻释放播放器是否安全：没在播、没排队起播、也没有「画面未就绪」在等面。 */
    val idleSafe: Boolean get() = !player.playing && !machine.wantsPlayback && !awaitingSurface

    /**
     * C 级闲置释放（PRD 1152）：只放播放器/解码器与已载入媒体，**保留 SSDP 与 HTTP 监听**。
     * 监听一旦停掉，控制点就再也找不到这台设备——那是拿功能残废换内存，不是「按需加载」。
     * 下一次 SetAVTransportURI 会按需重建播放器（[DlnaPlayer] 本来就是懒建），所以这里不需要重新拉起整个模块。
     * @return 真正释放了返回 true；正在播/等起播/等渲染面时返回 false，交给下一轮巡检。
     */
    fun releaseIdle(): Boolean {
        if (!idleSafe) return false
        stopImageRotation()
        clearImage()
        player.release()
        machine.clear()
        awaitingSurface = false
        onPlayingChanged?.invoke(false)
        Log.i(TAG, "idle release: player released, discovery kept")
        return true
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

/** 逐字节读一行（HTTP 请求行与头是 ASCII）；换行符本身不返回，行尾 \r 一并去掉。 */
private fun InputStream.readAsciiLine(): String? {
    val line = ByteArrayOutputStream(96)
    while (true) {
        when (val b = read()) {
            -1 -> return if (line.size() == 0) null else line.toString("US-ASCII")
            10 -> return line.toString("US-ASCII")
            13 -> Unit
            else -> line.write(b)
        }
    }
}

/** 读满 count 个字节再按正文编码解码（HTTP body 长度已知，避免 readText() 卡在 keep-alive 上）。 */
private fun InputStream.readBody(count: Int, charset: Charset): String {
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(bytes, read, count - read)
        if (n < 0) break
        read += n
    }
    return String(bytes, 0, read, charset)
}

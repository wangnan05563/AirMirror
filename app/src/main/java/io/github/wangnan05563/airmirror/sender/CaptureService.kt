package io.github.wangnan05563.airmirror.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import io.github.wangnan05563.airmirror.device.DeviceProbe
import io.github.wangnan05563.airmirror.device.EncodeProfile
import io.github.wangnan05563.airmirror.device.NetworkBand
import io.github.wangnan05563.airmirror.device.QualityNote
import io.github.wangnan05563.airmirror.device.QualityTier
import io.github.wangnan05563.airmirror.device.QualityTiers
import io.github.wangnan05563.airmirror.dlna.localIpv4
import io.github.wangnan05563.airmirror.error.ErrorNotice
import io.github.wangnan05563.airmirror.media.VideoEncoder
import io.github.wangnan05563.airmirror.net.ServiceLocator
import io.github.wangnan05563.airmirror.net.StreamConnection
import io.github.wangnan05563.airmirror.protocol.Auth
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.Limit
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.Resume
import io.github.wangnan05563.airmirror.protocol.SessionControl
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** 发送端最近一次投屏的结论文案，界面轮询取用；空串表示没有异常。 */
object SenderReport {
    @Volatile
    var last: String = ""

    /** 成功建立会话后的画质说明（如「已按 1080p60 投屏 · 对方上限」）；会话结束即清空 */
    @Volatile
    var quality: String = ""
}

/** 发送端控制播放（PRD FS-09 步骤 1）：暂停只停送帧，编码器保持热态，恢复时补一个关键帧。 */
object SenderControl {
    @Volatile
    var paused: Boolean = false

    /** 当前发送端收到的接管请求（PRD FS-09 步骤 4）；空串表示没有待处理请求 */
    @Volatile
    var takeoverFrom: String = ""

    /** 界面展示倒计时用；到点仍未表态由接收端按 60 秒超时拒绝 */
    @Volatile
    var takeoverDeadline: Long = 0L

    private val takeoverAnswer = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)

    fun requestTakeover(from: String, deadline: Long) {
        takeoverAnswer.set(null)
        takeoverDeadline = deadline
        takeoverFrom = from
    }

    /** 用户点了「让出」或「拒绝」；界面立即收起提示，由采集循环把回答发回接收端。 */
    fun answerTakeover(allow: Boolean) {
        takeoverFrom = ""
        takeoverDeadline = 0L
        takeoverAnswer.set(allow)
    }

    fun takeTakeoverAnswer(): Boolean? = takeoverAnswer.getAndSet(null)

    fun clearTakeover() {
        takeoverFrom = ""
        takeoverDeadline = 0L
        takeoverAnswer.set(null)
    }
}

/**
 * 发送端前台服务：MediaProjection 采集 → H.264 硬编码 → 私有通道发送。
 * 音频、触摸注入与弱网重连不在 M0 范围。
 */
class CaptureService : Service() {

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var peerReader: Thread? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: VideoEncoder? = null
    @Volatile
    private var connection: StreamConnection? = null

    /** 对端读线程发现断链后置位，主循环据此进入 30 秒恢复流程（PRD FS-10） */
    @Volatile
    private var peerGone = false

    /** 重连后的画质回档：先低一档，10 秒内回到协商档位 */
    private val recoveryQuality = RecoveryQuality()

    /** 发现时的服务名与本次会话的本地地址：切换 Wi-Fi 后要按服务名重新解析，不能用旧地址硬连 */
    private var serviceName = ""
    private var sessionLocalIp = ""

    /** 采集源尺寸与 dpi，重建编码器时要用，不能只在首次开流时记住 */
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var densityDpi = 0

    @Volatile
    private var ownCeiling: QualityTier = QualityTier.FHD60

    @Volatile
    private var band: NetworkBand = NetworkBand.UNKNOWN

    @Volatile
    private var peerCeiling: Limit.Ceiling = Limit.empty()

    @Volatile
    private var yielded = false

    private var displayManager: DisplayManager? = null

    /** 手机旋转时交换采集宽高并重建编码器，大屏才能跟着换方向（PRD FS-08 步骤 2）。 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY) return
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels and 0xFFFFFFFE.toInt()
            val height = metrics.heightPixels and 0xFFFFFFFE.toInt()
            if (width == sourceWidth && height == sourceHeight) return
            Log.i(TAG, "display rotated: ${sourceWidth}x$sourceHeight -> ${width}x$height")
            sourceWidth = width
            sourceHeight = height
            reconfigure.set(true)
        }
    }

    private val reconfigure = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (host == null || port <= 0 || resultData == null) {
            Log.w(TAG, "missing capture parameters")
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        running = true
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        val credential = intent.getStringExtra(EXTRA_CREDENTIAL).orEmpty()
        val serviceName = intent.getStringExtra(EXTRA_SERVICE_NAME).orEmpty()
        worker = thread(name = "airmirror-capture") {
            runSession(host, port, resultCode, resultData, deviceId, credential, serviceName)
        }
        return START_NOT_STICKY
    }

    /** 交凭据并等接收端表态；被拒时把原因回写界面，一帧都不推。 */
    private fun authorize(conn: StreamConnection, deviceId: String, credential: String): Boolean {
        conn.send(Protocol.Frame(Protocol.Type.AUTH, payload = Auth.request(deviceId, credential)))
        val reply = conn.receive()
        if (reply == null || reply.type != Protocol.Type.AUTH_RESULT) {
            SenderReport.last = "接收端未应答授权"
            return false
        }
        val result = Auth.parseResult(reply.payload)
        SenderReport.last = when {
            result.accepted -> ""
            result.reason.isBlank() -> ErrorNotice.textFor(ErrorNotice.Cause.REFUSED_BY_USER)
            else -> ErrorNotice.textFor(ErrorNotice.causeOfLegacyReason(result.reason))
        }
        return result.accepted
    }

    private fun runSession(
        host: String,
        port: Int,
        resultCode: Int,
        resultData: Intent,
        deviceId: String,
        credential: String,
        serviceName: String,
    ) {
        try {
            SenderReport.last = ""
            SenderReport.quality = ""
            SenderControl.paused = false
            SenderControl.clearTakeover()
            yielded = false
            peerGone = false
            recoveryQuality.stop()
            this.serviceName = serviceName
            // 先握手、先授权，通过后才开始采集屏幕：被拒时不该已经在录屏（PRD 14.4 最小采集）
            var conn = openConnection(host, port, deviceId, credential)
            connection = conn
            sessionLocalIp = localIpv4().orEmpty()
            var currentHost = host
            var currentPort = port

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection denied")
            projection = mediaProjection
            mediaProjection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        running = false
                    }
                },
                null,
            )

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            sourceWidth = metrics.widthPixels and 0xFFFFFFFE.toInt()
            sourceHeight = metrics.heightPixels and 0xFFFFFFFE.toInt()
            densityDpi = metrics.densityDpi
            ownCeiling = DeviceProbe.policy(this).maxTier
            band = DeviceProbe.band(this)
            peerCeiling = awaitCeiling(conn)
            if (!negotiatedTier().isUsable) {
                SenderReport.last = ErrorNotice.textFor(ErrorNotice.Cause.DECODE_UNSUPPORTED)
                return
            }
            SenderReport.quality = qualityNote().text()
            watchPeer(conn)
            displayManager = (getSystemService(DISPLAY_SERVICE) as DisplayManager).also {
                it.registerDisplayListener(displayListener, null)
            }

            openEncoder(mediaProjection, currentProfile())

            var sentFrames = 0
            var lastOutputAt = System.currentTimeMillis()
            var pauseApplied = SenderControl.paused
            while (running) {
                // 断链后先续会再继续送帧；续不上已经把结论文案写好，直接收尾
                if (peerGone) {
                    val resumed = recover(conn, currentHost, currentPort, deviceId) ?: break
                    conn = resumed.conn
                    connection = conn
                    currentHost = resumed.host
                    currentPort = resumed.port
                    watchPeer(conn)
                    pauseApplied = SenderControl.paused
                    if (pauseApplied) {
                        sendOrGone(conn, Protocol.Frame(Protocol.Type.CONTROL, payload = SessionControl.pause()))
                    }
                    continue
                }
                // 用户在大屏外的手机界面上选择了让出/拒绝（PRD FS-09 步骤 4）
                SenderControl.takeTakeoverAnswer()?.let { allow ->
                    Log.i(TAG, "takeover answer allow=$allow")
                    sendOrGone(conn, Protocol.Frame(Protocol.Type.CONTROL, payload = SessionControl.takeoverResult(allow)))
                    if (allow) {
                        yielded = true
                        SenderReport.quality = "已让出投屏"
                    } else {
                        SenderReport.quality = qualityText()
                    }
                }
                if (SenderControl.paused != pauseApplied) {
                    pauseApplied = SenderControl.paused
                    val payload = if (pauseApplied) SessionControl.pause() else SessionControl.resume()
                    sendOrGone(conn, Protocol.Frame(Protocol.Type.CONTROL, payload = payload))
                    SenderReport.quality = if (pauseApplied) {
                        "已暂停投屏 · 大屏保留最后一帧"
                    } else {
                        qualityText()
                    }
                    // 恢复后解码器需要关键帧才能继续出画，否则会等下一个自然 IDR
                    if (!pauseApplied) runCatching { encoder?.requestKeyFrame() }
                }
                if (recoveryQuality.tick()) {
                    Log.i(TAG, "recovery quality restored to ${recoveryQuality.fullTier().label}")
                    SenderReport.quality = qualityNote().text()
                    reconfigure.set(true)
                }
                if (reconfigure.getAndSet(false)) restartEncoder()
                val videoEncoder = encoder
                if (videoEncoder == null) {
                    Thread.sleep(20)
                    continue
                }
                val frames = videoEncoder.drain()
                if (frames.isEmpty()) {
                    val now = System.currentTimeMillis()
                    if (now - lastOutputAt >= 5_000) {
                        Log.w(TAG, "no encoder output for ${now - lastOutputAt}ms (sent=$sentFrames)")
                        lastOutputAt = now
                    }
                    Thread.sleep(4)
                    continue
                }
                lastOutputAt = System.currentTimeMillis()
                // 暂停期间继续取帧但丢弃，避免编码器缓冲堆满、恢复时一次性涌出
                if (pauseApplied) continue
                var delivered = true
                for (frame in frames) {
                    val flags = when {
                        frame.config -> Protocol.Flags.CONFIG
                        frame.keyFrame -> Protocol.Flags.KEY_FRAME
                        else -> 0
                    }
                    val type = Protocol.Type.VIDEO
                    if (!sendOrGone(conn, Protocol.Frame(type, flags = flags, ptsUs = frame.ptsUs, payload = frame.data))) {
                        delivered = false
                        break
                    }
                    sentFrames++
                    if (sentFrames == 1 || sentFrames % 60 == 0) {
                        // 帧时间戳即采集时刻（nanoTime 基准），这里的差值就是「采集→编码出帧」
                        val encodeLagMs = (System.nanoTime() / 1_000 - frame.ptsUs) / 1_000
                        Log.i(
                            TAG,
                            "sent $sentFrames frames, last=${frame.data.size}B key=${frame.keyFrame} " +
                                "config=${frame.config} encode+${encodeLagMs}ms",
                        )
                    }
                }
                if (!running) break
                if (!delivered) continue
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture session ended: ${e.message}")
            if (SenderReport.last.isEmpty()) SenderReport.last = ErrorNotice.textFor(ErrorNotice.Cause.UNREACHABLE)
        } finally {
            cleanup()
            stopSelf()
        }
    }

    /** 首次接入：握手 + 授权。被拒时 [authorize] 已把原因写进界面。 */
    private fun openConnection(
        host: String,
        port: Int,
        deviceId: String,
        credential: String,
    ): StreamConnection {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        val conn = StreamConnection(socket)
        val ack = runCatching { conn.connectAsClient(Build.MODEL, Handshake.Capability.VIDEO_H264) }
            .getOrElse {
                conn.close()
                throw it
            }
        Log.i(TAG, "connected to $host:$port version=${ack.chosenVersion}")
        if (!authorize(conn, deviceId, credential)) {
            conn.close()
            throw IllegalStateException("authorization refused")
        }
        return conn
    }

    private class ResumedLink(val conn: StreamConnection, val host: String, val port: Int)

    private sealed interface ResumeAttempt {
        class Ok(val conn: StreamConnection) : ResumeAttempt
        object Failed : ResumeAttempt

        /** 接收端会话已丢失（重启 / 保留窗口过期），再试也没用，要让用户重新连接 */
        object Restarted : ResumeAttempt
    }

    /**
     * PRD FS-10 断线恢复：保留窗口内最多 3 次续会，凭设备标识重新握手、不重走投屏码。
     * 切换 Wi-Fi 后旧地址不可达，先按服务名重新解析；失败给出可执行结论，不无限转圈。
     */
    private fun recover(
        dead: StreamConnection,
        host: String,
        port: Int,
        deviceId: String,
    ): ResumedLink? {
        val lostAt = System.currentTimeMillis()
        if (connection === dead) connection = null
        runCatching { dead.close() }
        peerReader?.join(500)
        peerReader = null
        peerGone = false
        Log.i(TAG, "link lost, entering ${RecoverySchedule.WINDOW_MS}ms recovery window")
        SenderReport.quality = "连接中断，正在恢复"
        updateNotification("连接中断，正在恢复")

        var targetHost = host
        var targetPort = port
        val currentIp = localIpv4().orEmpty()
        if (sessionLocalIp.isNotEmpty() && currentIp.isNotEmpty() && currentIp != sessionLocalIp) {
            // IP 变了旧地址不可达：按设备标识所在的服务名重新解析（PRD FS-10 场景「切换 Wi-Fi」）
            Log.i(TAG, "local ip $sessionLocalIp -> $currentIp, relocating $serviceName")
            SenderReport.quality = "网络已切换，正在重新连接"
            updateNotification("网络已切换，正在重新连接")
            ServiceLocator(this).locate(serviceName)?.let {
                targetHost = it.host
                targetPort = it.port
            }
        }

        var attempt = 0
        while (running && attempt < RecoverySchedule.ATTEMPTS) {
            val wait = lostAt + RecoverySchedule.delayFor(attempt) - System.currentTimeMillis()
            if (wait > 0) idle(wait)
            if (!running) return null
            attempt++
            SenderReport.quality = "正在恢复连接（$attempt/${RecoverySchedule.ATTEMPTS}）"
            updateNotification("正在恢复连接（$attempt/${RecoverySchedule.ATTEMPTS}）")
            when (val result = tryResume(targetHost, targetPort, deviceId)) {
                is ResumeAttempt.Ok -> {
                    sessionLocalIp = localIpv4().orEmpty()
                    recoveryQuality.start(negotiatedTier())
                    Log.i(
                        TAG,
                        "resumed at $targetHost:$targetPort, quality ${recoveryQuality.tier().label} until ${recoveryQuality.fullTier().label}",
                    )
                    SenderReport.quality = recoveryQuality.text()
                    updateNotification("已恢复投屏")
                    // 恢复后按低一档重开编码器，关键帧让大屏立刻续上而不是等自然 IDR
                    restartEncoder()
                    return ResumedLink(result.conn, targetHost, targetPort)
                }
                ResumeAttempt.Restarted -> {
                    Log.i(TAG, "receiver session lost, resume refused")
                    SenderReport.quality = ""
                    SenderReport.last = ErrorNotice.textFor(ErrorNotice.Cause.PEER_RESTARTED)
                    return null
                }
                ResumeAttempt.Failed -> Log.w(TAG, "resume attempt $attempt failed")
            }
        }
        if (running) {
            val elapsed = System.currentTimeMillis() - lostAt
            SenderReport.quality = ""
            SenderReport.last = ErrorNotice.textFor(RecoverySchedule.exhaustedCause(elapsed))
            Log.i(TAG, "recovery gave up after $attempt attempts (elapsed=${elapsed}ms)")
        }
        return null
    }

    /** 续会握手：HELLO → AUTH(resume=1)，接收端认保留窗口里的设备标识，不再弹授权。 */
    private fun tryResume(host: String, port: Int, deviceId: String): ResumeAttempt {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val conn = StreamConnection(socket)
            conn.connectAsClient(Build.MODEL, Handshake.Capability.VIDEO_H264)
            conn.send(Protocol.Frame(Protocol.Type.AUTH, payload = Auth.request(deviceId, "", resume = true)))
            conn.setReadTimeout(AUTH_WAIT_MS)
            val reply = try {
                conn.receive()
            } finally {
                runCatching { conn.setReadTimeout(0) }
            }
            if (reply == null || reply.type != Protocol.Type.AUTH_RESULT) {
                conn.close()
                return ResumeAttempt.Failed
            }
            val result = Auth.parseResult(reply.payload)
            if (!result.accepted) {
                conn.close()
                return if (result.reason == Resume.REASON_LOST) ResumeAttempt.Restarted else ResumeAttempt.Failed
            }
            peerCeiling = awaitCeiling(conn)
            ResumeAttempt.Ok(conn)
        } catch (e: Exception) {
            Log.w(TAG, "resume to $host:$port failed: ${e.message}")
            runCatching { socket.close() }
            ResumeAttempt.Failed
        }
    }

    /** 恢复等待期间编码器还在出帧，持续取走丢弃，避免输入 Surface 堆满后卡住屏幕采集。 */
    private fun idle(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (running && System.currentTimeMillis() < until) {
            runCatching { encoder?.drain() }
            try {
                Thread.sleep(RECOVERY_IDLE_STEP_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun sendOrGone(conn: StreamConnection, frame: Protocol.Frame): Boolean = try {
        conn.send(frame)
        true
    } catch (e: IOException) {
        Log.w(TAG, "send failed: ${e.message}")
        if (connection === conn) peerGone = true
        false
    }

    /**
     * 等接收端把画质上限发过来（PRD 8.4 协商）。老版本不发或发得慢就按本机分级投，
     * 不能因为缺一条控制帧把整个投屏卡住。
     */
    private fun awaitCeiling(conn: StreamConnection): Limit.Ceiling {
        val deadline = System.currentTimeMillis() + CEILING_WAIT_MS
        return try {
            conn.setReadTimeout(CEILING_WAIT_MS)
            while (System.currentTimeMillis() < deadline) {
                val frame = try {
                    conn.receive()
                } catch (e: InterruptedIOException) {
                    Log.i(TAG, "no ceiling in time, using local grade")
                    return Limit.empty()
                } ?: return Limit.empty()
                if (frame.type == Protocol.Type.CONTROL) return Limit.decode(frame.payload)
            }
            Limit.empty()
        } catch (e: Exception) {
            Log.w(TAG, "ceiling wait failed: ${e.message}")
            Limit.empty()
        } finally {
            runCatching { conn.setReadTimeout(0) }
        }
    }

    /** 对端的控制帧在这里落地：降档指令换一个更低的档重开编码器 */
    private fun watchPeer(conn: StreamConnection) {
        peerReader = thread(name = "airmirror-sender-rx") {
            try {
                while (running) {
                    val frame = conn.receive() ?: break
                    when (frame.type) {
                        Protocol.Type.CONTROL -> {
                            val signal = SessionControl.decode(frame.payload)
                            when (signal.op) {
                                SessionControl.OP_TAKEOVER -> {
                                    Log.i(TAG, "takeover requested by ${signal.from}")
                                    SenderControl.requestTakeover(
                                        signal.from,
                                        System.currentTimeMillis() + TAKEOVER_WINDOW_MS,
                                    )
                                    SenderReport.quality = "${signal.from} 请求接管投屏"
                                }
                                // 没有 op 的 CONTROL 仍是本机画质上限（PRD 8.4）
                                "" -> applyCeiling(Limit.decode(frame.payload))
                                else -> Unit
                            }
                        }

                        Protocol.Type.BYE -> {
                            SenderReport.last = if (yielded) {
                                "已让出投屏"
                            } else {
                                ErrorNotice.textFor(ErrorNotice.Cause.SESSION_ENDED)
                            }
                            running = false
                            break
                        }

                        else -> Unit
                    }
                }
                // 读不出来且没有收过 BYE：属于意外断链，交给主循环进入恢复流程
                if (running && connection === conn) peerGone = true
            } catch (e: Exception) {
                if (running) {
                    Log.i(TAG, "peer reader ended: ${e.message}")
                    if (connection === conn) peerGone = true
                }
            }
        }
    }

    private fun applyCeiling(newCeiling: Limit.Ceiling) {
        if (newCeiling.isEmpty) return
        val before = negotiatedTier()
        peerCeiling = newCeiling
        val after = negotiatedTier()
        if (after != before) {
            Log.i(TAG, "tier ${before.label} -> ${after.label}")
            SenderReport.quality = qualityText()
            // 恢复期收到新上限，回档目标也跟着改，但计时从头算，仍然是「先低一档」
            if (recoveryQuality.active) recoveryQuality.start(after)
            reconfigure.set(true)
        }
    }

    /** 协商档位 = 本机分级上限 ∩ 对端上限 ∩ 网络频段（PRD 8.7 三档适用条件） */
    private fun negotiatedTier(): QualityTier {
        val ceiling = peerCeiling
        val peer = if (ceiling.isEmpty) QualityTier.UHD60 else QualityTiers.tierWithin(ceiling.width, ceiling.height)
        return QualityTiers.negotiate(ownCeiling, peer, band)
    }

    /** 界面只展示真正可达的档位，并说明被谁压下来（PRD 13.1、T03939 4K 协商） */
    private fun qualityNote(): QualityNote {
        val ceiling = peerCeiling
        val peer = if (ceiling.isEmpty) null else QualityTiers.tierWithin(ceiling.width, ceiling.height)
        return QualityTiers.note(negotiatedTier(), ownCeiling, peer, band)
    }

    private fun qualityText(): String =
        if (recoveryQuality.active) recoveryQuality.text() else qualityNote().text()

    private fun currentProfile(): EncodeProfile {
        val profile = QualityTiers.profile(recoveryQuality.tier(), sourceWidth, sourceHeight)
        Log.i(TAG, "encode ${profile.width}x${profile.height}@${profile.fps} tier=${profile.tier.label} br=${profile.bitRateKbps}kbps")
        return profile
    }

    private fun openEncoder(mediaProjection: MediaProjection, profile: EncodeProfile): VideoEncoder {
        val videoEncoder = VideoEncoder(profile.width, profile.height, profile.fps, profile.bitRate)
        encoder = videoEncoder
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AirMirror",
            profile.width,
            profile.height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            videoEncoder.inputSurface,
            null,
            null,
        )
        videoEncoder.requestKeyFrame()
        return videoEncoder
    }

    /** 换档要重建编码器与虚拟屏：采集尺寸变了，旧 Surface 不能再复用 */
    private fun restartEncoder() {
        val mediaProjection = projection ?: return
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { encoder?.release() }
        encoder = null
        runCatching { openEncoder(mediaProjection, currentProfile()) }
            .onFailure {
                Log.w(TAG, "reconfigure failed: ${it.message}")
                SenderReport.last = ErrorNotice.textFor(ErrorNotice.Cause.DECODE_UNSUPPORTED)
                running = false
            }
    }

    private fun cleanup() {
        running = false
        peerGone = false
        recoveryQuality.stop()
        SenderReport.quality = ""
        SenderControl.paused = false
        SenderControl.clearTakeover()
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        peerReader?.join(500)
        peerReader = null
        runCatching { connection?.close() }
        connection = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { encoder?.release() }
        encoder = null
        runCatching { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        running = false
        worker?.join(1_000)
        cleanup()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String = "私有通道传输中"): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "投屏中", NotificationManager.IMPORTANCE_LOW),
                )
            }
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AirMirror 正在投屏")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    /** 断线恢复期间通知栏也要能看出状态（PRD FS-10 步骤 2：切后台保留控制条）。 */
    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_CREDENTIAL = "credential"
        const val EXTRA_SERVICE_NAME = "serviceName"
        const val ACTION_STOP = "io.github.wangnan05563.airmirror.STOP_CAPTURE"
        private const val TAG = "AirMirrorCapture"
        private const val CHANNEL_ID = "airmirror-capture"
        private const val NOTIFICATION_ID = 1
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val AUTH_WAIT_MS = 4_000
        private const val CEILING_WAIT_MS = 1_500
        private const val TAKEOVER_WINDOW_MS = 60_000L
        private const val RECOVERY_IDLE_STEP_MS = 50L
    }
}

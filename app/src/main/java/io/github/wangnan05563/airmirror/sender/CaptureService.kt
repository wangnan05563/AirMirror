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
import io.github.wangnan05563.airmirror.error.ErrorNotice
import io.github.wangnan05563.airmirror.media.VideoEncoder
import io.github.wangnan05563.airmirror.net.StreamConnection
import io.github.wangnan05563.airmirror.protocol.Auth
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.Limit
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.SessionControl
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
    private var connection: StreamConnection? = null

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
        worker = thread(name = "airmirror-capture") { runSession(host, port, resultCode, resultData, deviceId, credential) }
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
    ) {
        try {
            SenderReport.last = ""
            SenderReport.quality = ""
            SenderControl.paused = false
            // 先握手、先授权，通过后才开始采集屏幕：被拒时不该已经在录屏（PRD 14.4 最小采集）
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val conn = StreamConnection(socket)
            connection = conn
            val ack = conn.connectAsClient(Build.MODEL, Handshake.Capability.VIDEO_H264)
            if (!authorize(conn, deviceId, credential)) return
            Log.i(TAG, "connected to $host:$port version=${ack.chosenVersion}")

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

            openEncoder(mediaProjection, currentProfile())

            var sentFrames = 0
            var lastOutputAt = System.currentTimeMillis()
            var pauseApplied = false
            while (running) {
                if (SenderControl.paused != pauseApplied) {
                    pauseApplied = SenderControl.paused
                    val payload = if (pauseApplied) SessionControl.pause() else SessionControl.resume()
                    runCatching { conn.send(Protocol.Frame(Protocol.Type.CONTROL, payload = payload)) }
                    SenderReport.quality = if (pauseApplied) {
                        "已暂停投屏 · 大屏保留最后一帧"
                    } else {
                        qualityNote().text()
                    }
                    // 恢复后解码器需要关键帧才能继续出画，否则会等下一个自然 IDR
                    if (!pauseApplied) runCatching { encoder?.requestKeyFrame() }
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
                for (frame in frames) {
                    val flags = when {
                        frame.config -> Protocol.Flags.CONFIG
                        frame.keyFrame -> Protocol.Flags.KEY_FRAME
                        else -> 0
                    }
                    val type = Protocol.Type.VIDEO
                    conn.send(Protocol.Frame(type, flags = flags, ptsUs = frame.ptsUs, payload = frame.data))
                    sentFrames++
                    if (sentFrames == 1 || sentFrames % 60 == 0) {
                        Log.i(
                            TAG,
                            "sent $sentFrames frames, last=${frame.data.size}B key=${frame.keyFrame} config=${frame.config}",
                        )
                    }
                }
                if (!running) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture session ended: ${e.message}")
            if (SenderReport.last.isEmpty()) SenderReport.last = ErrorNotice.textFor(ErrorNotice.Cause.UNREACHABLE)
        } finally {
            cleanup()
            stopSelf()
        }
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
                        Protocol.Type.CONTROL -> applyCeiling(Limit.decode(frame.payload))
                        Protocol.Type.BYE -> {
                            SenderReport.last = ErrorNotice.textFor(ErrorNotice.Cause.SESSION_ENDED)
                            running = false
                            break
                        }

                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                if (running) Log.i(TAG, "peer reader ended: ${e.message}")
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
            SenderReport.quality = qualityNote().text()
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

    private fun currentProfile(): EncodeProfile {
        val profile = QualityTiers.profile(negotiatedTier(), sourceWidth, sourceHeight)
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
        SenderReport.quality = ""
        SenderControl.paused = false
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

    private fun buildNotification(): Notification {
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
            .setContentText("私有通道传输中")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_CREDENTIAL = "credential"
        const val ACTION_STOP = "io.github.wangnan05563.airmirror.STOP_CAPTURE"
        private const val TAG = "AirMirrorCapture"
        private const val CHANNEL_ID = "airmirror-capture"
        private const val NOTIFICATION_ID = 1
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val CEILING_WAIT_MS = 1_500
    }
}

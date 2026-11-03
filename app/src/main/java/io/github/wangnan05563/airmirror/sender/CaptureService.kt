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
import io.github.wangnan05563.airmirror.media.VideoEncoder
import io.github.wangnan05563.airmirror.net.StreamConnection
import io.github.wangnan05563.airmirror.protocol.Auth
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.Protocol
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** 发送端最近一次投屏的结论文案，界面轮询取用；空串表示没有异常。 */
object SenderReport {
    @Volatile
    var last: String = ""
}

/**
 * 发送端前台服务：MediaProjection 采集 → H.264 硬编码 → 私有通道发送。
 * 音频、触摸注入与弱网重连不在 M0 范围。
 */
class CaptureService : Service() {

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: VideoEncoder? = null
    private var connection: StreamConnection? = null

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
            result.reason.isBlank() -> "接收端拒绝投屏"
            else -> result.reason
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
            val width = metrics.widthPixels and 0xFFFFFFFE.toInt()
            val height = metrics.heightPixels and 0xFFFFFFFE.toInt()

            val videoEncoder = VideoEncoder(width, height)
            encoder = videoEncoder
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "AirMirror",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                videoEncoder.inputSurface,
                null,
                null,
            )

            videoEncoder.requestKeyFrame()

            var sentFrames = 0
            var lastOutputAt = System.currentTimeMillis()
            while (running) {
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
            if (SenderReport.last.isEmpty()) SenderReport.last = "连不上对方，确认两台设备在同一网络"
        } finally {
            cleanup()
            stopSelf()
        }
    }

    private fun cleanup() {
        running = false
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
    }
}

package io.github.wangnan05563.airmirror.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import io.github.wangnan05563.airmirror.device.CodecProbe
import io.github.wangnan05563.airmirror.device.DeviceProbe
import io.github.wangnan05563.airmirror.device.EncodeProfile
import io.github.wangnan05563.airmirror.device.NetworkBand
import io.github.wangnan05563.airmirror.device.QualityNote
import io.github.wangnan05563.airmirror.device.QualityTier
import io.github.wangnan05563.airmirror.device.QualityTiers
import io.github.wangnan05563.airmirror.dlna.localIpv4
import io.github.wangnan05563.airmirror.error.ErrorNotice
import io.github.wangnan05563.airmirror.media.AudioEncoder
import io.github.wangnan05563.airmirror.media.AudioFormat
import io.github.wangnan05563.airmirror.media.CodecChoice
import io.github.wangnan05563.airmirror.media.VideoCodec
import io.github.wangnan05563.airmirror.media.VideoEncoder
import io.github.wangnan05563.airmirror.net.ServiceLocator
import io.github.wangnan05563.airmirror.net.StreamConnection
import io.github.wangnan05563.airmirror.perf.AvSync
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
import io.github.wangnan05563.airmirror.privacy.Redactor

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

    /** 镜像是否带声音（PRD FS-01 参数「音频」，默认开启）；关掉后只送画面 */
    @Volatile
    var audio: Boolean = true

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

    /**
     * 本次会话实际在用的编码（PRD 8.7）。默认 H.264：对端没上报过硬解能力时，
     * 任何一帧都不该用 H.265 发出去，否则老接收端只会收到一条解不出来的码流。
     */
    @Volatile
    private var videoCodec: VideoCodec = VideoCodec.AVC

    @Volatile
    private var yielded = false

    /** 系统声音采集线程与它的结论文案（PRD FS-01「持续发送画面与音频」） */
    private var audioThread: Thread? = null

    /** 录音句柄由服务持有释放：断线重连要能换一条新的采集流水线 */
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var audioStop = false

    @Volatile
    private var audioNote = ""

    /** 对端握手时认了音频能力，才去开采集——老接收端不认就别白占一个录音权限 */
    @Volatile
    private var peerAcceptsAudio = false

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

    /** 接收端换了解码器流水线时会请求关键帧；编码器只能在采集线程上操作，所以先置位再由循环消费。 */
    private val syncRequested = java.util.concurrent.atomic.AtomicBoolean(false)

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
        // 14.1「投屏码校验：提交到结果 ≤1 秒」的取样点，含网络往返与接收端判定
        val sentAt = System.nanoTime()
        conn.send(Protocol.Frame(Protocol.Type.AUTH, payload = Auth.request(deviceId, credential)))
        val reply = conn.receive()
        if (reply == null || reply.type != Protocol.Type.AUTH_RESULT) {
            Log.w(TAG, "credential check timed out after ${(System.nanoTime() - sentAt) / 1_000_000}ms")
            SenderReport.last = "接收端未应答授权"
            return false
        }
        val result = Auth.parseResult(reply.payload)
        Log.i(
            TAG,
            "credential check ${(System.nanoTime() - sentAt) / 1_000_000}ms accepted=${result.accepted}",
        )
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
            syncRequested.set(false)
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
            chooseCodec()
            if (!negotiatedTier().isUsable) {
                SenderReport.last = ErrorNotice.textFor(ErrorNotice.Cause.DECODE_UNSUPPORTED)
                return
            }
            SenderReport.quality = qualityText()
            watchPeer(conn)
            displayManager = (getSystemService(DISPLAY_SERVICE) as DisplayManager).also {
                it.registerDisplayListener(displayListener, null)
            }

            openEncoder(mediaProjection, currentProfile())
            startAudio(mediaProjection, conn)

            var sentFrames = 0
            var lastOutputAt = System.currentTimeMillis()
            var pauseApplied = SenderControl.paused
            var audioWanted = SenderControl.audio
            while (running) {
                // 断链后先续会再继续送帧；续不上已经把结论文案写好，直接收尾
                if (peerGone) {
                    val resumed = recover(conn, currentHost, currentPort, deviceId) ?: break
                    conn = resumed.conn
                    connection = conn
                    currentHost = resumed.host
                    currentPort = resumed.port
                    watchPeer(conn)
                    // 声音线程绑的是上一条连接，重连后不换过来就只剩哑巴画面
                    restartAudio(mediaProjection, conn)
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
                if (SenderControl.audio != audioWanted) {
                    // 投屏中拨声音开关（PRD FS-01 参数「音频」）：要真的收放采集通道并告知大屏，
                    // 只停发送会让大屏在 1 秒后误报「声音被占用」
                    audioWanted = SenderControl.audio
                    Log.i(TAG, "audio switch -> $audioWanted")
                    sendOrGone(conn, Protocol.Frame(Protocol.Type.CONTROL, payload = SessionControl.audio(audioWanted)))
                    if (audioWanted) {
                        startAudio(mediaProjection, conn)
                    } else {
                        stopAudio()
                        audioNote = "已按要求关闭声音投屏"
                    }
                    SenderReport.quality = qualityText()
                }
                if (recoveryQuality.tick()) {
                    Log.i(TAG, "recovery quality restored to ${recoveryQuality.fullTier().label}")
                    SenderReport.quality = qualityText()
                    reconfigure.set(true)
                }
                if (syncRequested.getAndSet(false)) {
                    // 只记「收到了」还不够：真正的证据是随后这一帧 IDR 有没有出去
                    val encoderHere = encoder
                    Log.i(TAG, "sync requested, encoder=${if (encoderHere == null) "null" else "alive"}")
                    runCatching { encoderHere?.requestKeyFrame() }
                        .onFailure { Log.w(TAG, "sync frame request failed: ${Redactor.throwable(it)}") }
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
                        frame.config -> Protocol.Flags.CONFIG or hevcFlag(videoEncoder.codec)
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
                        // 帧时间戳即采集时刻（nanoTime 基准），这里的差值就是「采集→编码出帧」。
                        // 编码配置帧的 pts 是 0，不参与计算，否则报出一个假的上亿毫秒
                        val lagText = if (frame.ptsUs > 0L) {
                            " encode+${(System.nanoTime() / 1_000 - frame.ptsUs) / 1_000}ms"
                        } else {
                            ""
                        }
                        Log.i(
                            TAG,
                            "sent $sentFrames frames, last=${frame.data.size}B key=${frame.keyFrame} " +
                                "config=${frame.config}$lagText",
                        )
                    }
                }
                if (!running) break
                if (!delivered) continue
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture session ended: ${Redactor.throwable(e)}")
            if (SenderReport.last.isEmpty()) SenderReport.last = ErrorNotice.textFor(ErrorNotice.Cause.UNREACHABLE)
        } finally {
            cleanup()
            stopSelf()
        }
    }

    /**
     * 系统声音采集（PRD FS-01「持续发送画面与音频」）。
     *
     * 采不到声音不终止会话：按 PRD 异常表「系统禁止采集内部音频 → 画面继续，不中断会话」收口，
     * 只把原因写在发送端界面上，让人知道现在投的是哑巴画面而不是链路坏了。
     */
    private fun startAudio(projection: MediaProjection, conn: StreamConnection) {
        if (!peerAcceptsAudio) {
            Log.i(TAG, "audio skipped: peer did not take the audio capability")
            return
        }
        if (!SenderControl.audio) {
            audioNote = "已按要求关闭声音投屏"
            SenderReport.quality = qualityText()
            return
        }
        when (val opened = SystemAudio.open(projection, SystemAudio.hasPermission(this))) {
            is SystemAudio.Opened.NoPermission -> {
                audioNote = ErrorNotice.textFor(ErrorNotice.Cause.PERMISSION_AUDIO)
                Log.w(TAG, "audio skipped: RECORD_AUDIO not granted")
            }

            is SystemAudio.Opened.Unsupported -> {
                audioNote = ErrorNotice.textFor(ErrorNotice.Cause.AUDIO_UNAVAILABLE)
                Log.i(TAG, "audio unsupported: ${opened.reason}")
            }

            is SystemAudio.Opened.Failed -> {
                audioNote = ErrorNotice.textFor(ErrorNotice.Cause.AUDIO_UNAVAILABLE)
                Log.w(TAG, "audio open failed: ${opened.reason}")
            }

            is SystemAudio.Opened.Ready -> {
                audioNote = ""
                val record = opened.record
                val spec = opened.spec
                val via = opened.via
                audioStop = false
                audioRecord = record
                audioThread = thread(name = "airmirror-audio") { runAudio(record, spec, via, conn) }
            }
        }
        SenderReport.quality = qualityText()
    }

    /**
     * 停掉声音采集：断线重连（连接已换）与收尾都要先停这一路。
     * 录音句柄在这里统一释放，采集线程只负责不再往外发。
     */
    private fun stopAudio() {
        audioStop = true
        audioThread?.let {
            runCatching { it.interrupt() }
            runCatching { it.join(500) }
        }
        audioThread = null
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    /** 重连后连接换了新的，旧线程绑在死连接上，必须换一条采集流水线（PRD FS-10 恢复后画面与音频都要回来）。 */
    private fun restartAudio(projection: MediaProjection, conn: StreamConnection) {
        stopAudio()
        startAudio(projection, conn)
    }

    /**
     * 采集 → AAC 编码 → AUDIO 帧。时间戳起点取当前 nanoTime，与画面编码器同一基准，
     * 大屏才可能拿音频主时钟给画面对表（PRD FR-09）。
     */
    private fun runAudio(record: AudioRecord, spec: AudioFormat.Spec, via: String, conn: StreamConnection) {
        var encoder = AudioEncoder(spec, System.nanoTime() / 1_000)
        if (!sendAudioConfig(conn, spec)) {
            runCatching { encoder.release() }
            return
        }
        Log.i(TAG, "audio capture started via $via ${spec.sampleRate}Hz/${spec.channels}ch")
        val buffer = ByteArray(SystemAudio.READ_BYTES)
        var sent = 0
        var lastSampleAt = System.currentTimeMillis()
        var interrupted = false
        while (running && !audioStop) {
            val read = runCatching { record.read(buffer, 0, buffer.size) }.getOrDefault(0)
            if (read <= 0) {
                // 采不到样本超过 1 秒：按「声音被占用」写结论，画面照投（PRD FS-06 异常表）
                if (!interrupted && System.currentTimeMillis() - lastSampleAt > AUDIO_GAP_MS) {
                    interrupted = true
                    audioNote = ErrorNotice.textFor(ErrorNotice.Cause.AUDIO_INTERRUPTED)
                    SenderReport.quality = qualityText()
                    Log.w(TAG, "audio stalled for ${AUDIO_GAP_MS}ms, keeping video only")
                }
                sleepQuietly(AUDIO_IDLE_MS)
                continue
            }
            lastSampleAt = System.currentTimeMillis()
            if (interrupted) {
                // 声音回来了要重新对表：旧基准已经把缺口算进样本数里，继续用会一路追帧
                interrupted = false
                audioNote = ""
                SenderReport.quality = qualityText()
                runCatching { encoder.release() }
                encoder = AudioEncoder(spec, System.nanoTime() / 1_000)
                if (!sendAudioConfig(conn, spec)) return
                Log.i(TAG, "audio resumed, clock re-anchored")
            }
            // 关掉声音开关或被暂停时继续把采集读干净，只是不发，免得缓冲区堆满
            if (!SenderControl.audio || SenderControl.paused) continue
            for (frame in encoder.feed(buffer.copyOf(read))) {
                val ok = sendOrGone(
                    conn,
                    Protocol.Frame(Protocol.Type.AUDIO, ptsUs = frame.ptsUs, payload = frame.data),
                )
                if (!ok) {
                    runCatching { encoder.release() }
                    return
                }
                sent++
                if (sent == 1 || sent % 150 == 0) {
                    Log.i(TAG, "sent $sent audio frames, pts=${frame.ptsUs}")
                }
            }
        }
        runCatching { encoder.release() }
        Log.i(TAG, "audio capture stopped after $sent frames")
    }

    /** 音频描述帧：接收端靠它建解码器与声卡，重新锚定时间戳时要再发一次。 */
    private fun sendAudioConfig(conn: StreamConnection, spec: AudioFormat.Spec): Boolean =
        sendOrGone(conn, Protocol.Frame(Protocol.Type.AUDIO, flags = Protocol.Flags.CONFIG, payload = AudioFormat.encode(spec)))

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            // 只影响这一路声音：停它靠 audioStop，不能顺手把整个会话判死
            Thread.currentThread().interrupt()
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
        val ack = runCatching { conn.connectAsClient(Build.MODEL, MIRROR_CAPABILITIES) }
            .getOrElse {
                conn.close()
                throw it
            }
        peerAcceptsAudio = ack.capabilities and Handshake.Capability.AUDIO_AAC != 0
        Log.i(
            TAG,
            "connected to $host:$port version=${ack.chosenVersion} audio=$peerAcceptsAudio " +
                "media=${if (conn.secure) "encrypted" else "in clear, legacy peer"}",
        )
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
            val ack = conn.connectAsClient(Build.MODEL, MIRROR_CAPABILITIES)
            peerAcceptsAudio = ack.capabilities and Handshake.Capability.AUDIO_AAC != 0
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
            chooseCodec()
            ResumeAttempt.Ok(conn)
        } catch (e: Exception) {
            Log.w(TAG, "resume failed: ${Redactor.throwable(e)}")
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
        Log.w(TAG, "send failed: ${Redactor.throwable(e)}")
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
            Log.w(TAG, "ceiling wait failed: ${Redactor.throwable(e)}")
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
                                SessionControl.OP_SYNC -> {
                                    Log.i(TAG, "peer restarted its decoder, sending a key frame")
                                    syncRequested.set(true)
                                }
                                // 没有 op 的 CONTROL 仍是本机画质上限（PRD 8.4）
                                "" -> applyCeiling(Limit.decode(frame.payload))
                                else -> Unit
                            }
                        }

                        Protocol.Type.BYE -> {
                            // 大屏按了「结束投屏」：这是有序收尾，不进恢复窗口
                            Log.i(TAG, "peer ended session (bye), yielded=$yielded")
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
                    Log.i(TAG, "peer reader ended: ${Redactor.throwable(e)}")
                    if (connection === conn) peerGone = true
                }
            }
        }
    }

    private fun applyCeiling(newCeiling: Limit.Ceiling) {
        if (newCeiling.isEmpty) return
        val before = negotiatedPlan()
        peerCeiling = newCeiling
        chooseCodec()
        val after = negotiatedPlan()
        if (after != before) {
            Log.i(TAG, "plan ${before.first.label}@${before.second}/${before.third.display} -> " +
                "${after.first.label}@${after.second}/${after.third.display}")
            SenderReport.quality = qualityText()
            // 恢复期收到新上限，回档目标也跟着改，但计时从头算，仍然是「先低一档」
            if (recoveryQuality.active) recoveryQuality.start(after.first)
            reconfigure.set(true)
        }
    }

    /**
     * 按对端声明的硬解能力重选编码（PRD 8.7 编码选择顺序）。
     *
     * 每次收到上限都要重算，而不是只在连上时算一次：接收端「解不动 H.265」的回复
     * 就是把 H.265 从声明里去掉的下一条上限，本机要立刻换回 H.264 重开编码器。
     */
    private fun chooseCodec(): VideoCodec {
        val local = CodecProbe.hardwareEncoders()
        val chosen = CodecChoice.select(peerCeiling.codecs, local, band)
        if (chosen != videoCodec) {
            Log.i(TAG, "codec ${videoCodec.display} -> ${chosen.display} · ${CodecChoice.reason(chosen, peerCeiling.codecs, local, band)}")
            videoCodec = chosen
        } else {
            // 首次选定（默认值就是 H.264）也要留一句成因，否则「为什么没用 H.265」只能靠猜
            Log.i(TAG, "codec ${chosen.display} · ${CodecChoice.reason(chosen, peerCeiling.codecs, local, band)}")
        }
        return chosen
    }

    /** 只有 H.265 需要额外标记位；H.264 保持老接收端的默认解读路径。 */
    private fun hevcFlag(codec: VideoCodec): Int =
        if (codec == VideoCodec.HEVC) Protocol.Flags.CODEC_HEVC else 0

    /** 协商档位 = 本机分级上限 ∩ 对端上限 ∩ 网络频段（PRD 8.7 三档适用条件） */
    private fun negotiatedTier(): QualityTier {
        val ceiling = peerCeiling
        val peer = if (ceiling.isEmpty) QualityTier.UHD60 else QualityTiers.tierWithin(ceiling.width, ceiling.height)
        return QualityTiers.negotiate(ownCeiling, peer, band)
    }

    /**
     * 协商到的「档位 + 帧率 + 编码」组合：接收端按延迟降级时只改帧率、档位标签不变（PRD REQ-077），
     * 只看档位会漏掉这次变更，编码器就永远停在原帧率上。编码也在比较里——
     * 接收端解不动 H.265 后去掉声明，画面尺寸可能一档没变，但流水线必须换。
     */
    private fun negotiatedPlan(): Triple<QualityTier, Int, VideoCodec> =
        Triple(negotiatedTier(), QualityTiers.effectiveFps(negotiatedTier(), peerCeiling.fps), videoCodec)

    /** 界面只展示真正可达的档位，并说明被谁压下来（PRD 13.1、T03939 4K 协商） */
    private fun qualityNote(): QualityNote {
        val ceiling = peerCeiling
        val peer = if (ceiling.isEmpty) null else QualityTiers.tierWithin(ceiling.width, ceiling.height)
        return QualityTiers.note(negotiatedTier(), ownCeiling, peer, band)
    }

    private fun qualityText(): String {
        val base = if (recoveryQuality.active) recoveryQuality.text() else qualityNote().text()
        val tier = currentTier()
        val fps = QualityTiers.effectiveFps(tier, peerCeiling.fps)
        val withFps = if (fps in 1 until tier.fps) "$base · ${fps}fps" else base
        // 编码写在档位后面（PRD 13.1「说清用的什么编码」）：省带宽换成 H.265 时用户要能看出来
        val withCodec = "$withFps · ${videoCodec.display}"
        // 声音的结论跟在画质后面同一条展示：投屏中界面不额外弹窗，但「现在有没有声音」必须看得见
        return if (audioNote.isEmpty()) withCodec else "$withCodec · $audioNote"
    }

    /**
     * 当前该用的档位：恢复期用降一档，其余时候用协商档。
     * 按 OFF 建编码器会得到 0x0，整条链路起流即失败。
     */
    private fun currentTier(): QualityTier = recoveryQuality.tierOr(negotiatedTier())

    private fun currentProfile(): EncodeProfile {
        val tier = currentTier()
        val profile = QualityTiers.profile(tier, sourceWidth, sourceHeight)
        val fps = QualityTiers.effectiveFps(tier, peerCeiling.fps)
        val capped = if (fps == profile.fps) profile else profile.copy(fps = fps)
        Log.i(TAG, "encode ${capped.width}x${capped.height}@${capped.fps} tier=${capped.tier.label} br=${capped.bitRateKbps}kbps codec=${videoCodec.wire}")
        return capped
    }

    private fun openEncoder(mediaProjection: MediaProjection, profile: EncodeProfile): VideoEncoder {
        val videoEncoder = newEncoder(profile)
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

    /**
     * 换档：编码尺寸/帧率变了，旧编码器的输入面不能复用，要换一条新的。
     *
     * 但虚拟屏不能重建：Android 14 起一个 MediaProjection 只允许调用一次
     * `createVirtualDisplay`（「一次授权 = 一次采集会话」），release 之后再建会抛
     * SecurityException，整条流当场断掉（模拟器取证时降档就是这样把投屏弄没的）。
     * 所以这里保留同一条虚拟屏，只把它的输出面切到新编码器（API 21+ 的 setSurface/resize），
     * 用户不需要重新授权，画面也不会因为会话重建而黑掉。
     */
    private fun restartEncoder() {
        val profile = currentProfile()
        if (profile.width <= 0 || profile.height <= 0) {
            Log.w(TAG, "reconfigure skipped: unusable tier ${profile.tier.label}")
            return
        }
        val display = virtualDisplay
        if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val mediaProjection = projection ?: return
            runCatching { display?.release() }
            virtualDisplay = null
            runCatching { encoder?.release() }
            encoder = null
            runCatching { openEncoder(mediaProjection, profile) }
                .onFailure { failReconfigure(it) }
            return
        }
        runCatching {
            val previous = encoder
            val videoEncoder = newEncoder(profile)
            encoder = videoEncoder
            display.setSurface(videoEncoder.inputSurface)
            display.resize(profile.width, profile.height, densityDpi)
            // 新编码器手上没有 IDR，切面之后第一帧必须是关键帧，否则接收端只能等下一个 GOP
            videoEncoder.requestKeyFrame()
            runCatching { previous?.release() }
        }.onFailure { failReconfigure(it) }
    }

    /**
     * 按当前协商编码建编码器。
     *
     * H.265 建不起来时换回 H.264 重试，而不是直接判死整场投屏：能力表在某些机型上会把
     * 「有 HEVC 编码器实例」写成「能配这个尺寸」，尤其分辨率换档时更容易踩到。
     * 编码是可选优化，档位是底线——宁可多耗带宽，也不能让画面断掉（PRD 8.7「否则使用 H.264」）。
     */
    private fun newEncoder(profile: EncodeProfile): VideoEncoder {
        val codec = videoCodec
        if (codec == VideoCodec.AVC) {
            return VideoEncoder(profile.width, profile.height, profile.fps, profile.bitRate, codec)
        }
        return try {
            VideoEncoder(profile.width, profile.height, profile.fps, profile.bitRate, codec)
        } catch (e: Exception) {
            Log.w(TAG, "no working ${codec.display} encoder at ${profile.width}x${profile.height}" +
                "@${profile.fps}, fall back to ${VideoCodec.AVC.display}", e)
            videoCodec = VideoCodec.AVC
            SenderReport.quality = qualityText()
            VideoEncoder(profile.width, profile.height, profile.fps, profile.bitRate, VideoCodec.AVC)
        }
    }

    private fun failReconfigure(error: Throwable) {
        Log.w(TAG, "reconfigure failed: ${error.message}")
        SenderReport.last = ErrorNotice.textFor(ErrorNotice.Cause.DECODE_UNSUPPORTED)
        running = false
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
        // 声音线程也在往同一个连接写，先停它再关连接，否则收尾期会往已关的 socket 上丢帧
        stopAudio()
        audioNote = ""
        peerAcceptsAudio = false
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

        /** 镜像一路带画面与声音（PRD FS-01 状态「STREAMING 持续发送画面与音频」），能力位按两样报 */
        private const val MIRROR_CAPABILITIES = Handshake.Capability.VIDEO_H264 or Handshake.Capability.AUDIO_AAC

        /** 采不到样本超过这个时长判为「声音被占用」（PRD FS-06 异常表 1 秒） */
        private const val AUDIO_GAP_MS = AvSync.INTERRUPT_MS

        /** 空转时的取样间隔：太密是白耗 CPU，太疏会让 1 秒判定迟一拍 */
        private const val AUDIO_IDLE_MS = 10L
    }
}

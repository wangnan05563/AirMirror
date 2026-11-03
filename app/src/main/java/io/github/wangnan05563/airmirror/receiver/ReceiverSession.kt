package io.github.wangnan05563.airmirror.receiver

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import io.github.wangnan05563.airmirror.device.DeviceFacts
import io.github.wangnan05563.airmirror.device.DeviceGrading
import io.github.wangnan05563.airmirror.device.DeviceProbe
import io.github.wangnan05563.airmirror.device.GradePolicy
import io.github.wangnan05563.airmirror.device.QualityTier
import io.github.wangnan05563.airmirror.device.QualityTiers
import io.github.wangnan05563.airmirror.error.ErrorNotice
import io.github.wangnan05563.airmirror.media.AudioDevices
import io.github.wangnan05563.airmirror.media.AudioFormat
import io.github.wangnan05563.airmirror.media.AudioOut
import io.github.wangnan05563.airmirror.media.H264
import io.github.wangnan05563.airmirror.media.VideoDecoder
import io.github.wangnan05563.airmirror.net.ServiceAdvertiser
import io.github.wangnan05563.airmirror.net.StreamConnection
import io.github.wangnan05563.airmirror.pairing.AuthGate
import io.github.wangnan05563.airmirror.pairing.Pairing
import io.github.wangnan05563.airmirror.perf.AvSync
import io.github.wangnan05563.airmirror.perf.LatencyLadder
import io.github.wangnan05563.airmirror.perf.LatencyMove
import io.github.wangnan05563.airmirror.perf.LatencyPolicy
import io.github.wangnan05563.airmirror.protocol.Auth
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.Limit
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.Resume
import io.github.wangnan05563.airmirror.protocol.SessionControl
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 接收端会话：固定端口 TCP 服务（占用则退回随机端口）+ NSD 广播 + 版本握手 + 授权门禁 + H.264 解码渲染。
 *
 * 会话生命周期与 Activity 的 Surface 解耦：界面切到后台时继续收流，
 * 有 Surface 才建解码器；回到前台重新挂 Surface 后用缓存的编码配置重建解码器。
 */
class ReceiverSession(
    private val context: Context,
    private val pairing: Pairing,
    private val onStatus: (String) -> Unit,
    private val onPrompt: (Prompt) -> Unit,
    private val onCasting: (Boolean, String) -> Unit,
    /** 投屏中待连接页不可见：暂停、接管这类一次性事件单独走浮层，周期性统计不进来。 */
    private val onNotice: (String) -> Unit = onStatus,
    /** 新编码配置的宽高：大屏据此等比摆放画面并跟随手机旋转（PRD FS-08）。 */
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
    /** 断线保留窗口的开关：true 显示「连接中断，正在恢复」，恢复或收口后收起（PRD FS-10）。 */
    private val onRecovery: (Boolean) -> Unit = {},
) {
    /** 首次连接的大屏确认请求；界面必须在 [autoAllowAt] 之前调用 [ReceiverSession.resolve]。 */
    data class Prompt(val deviceId: String, val deviceName: String, val autoAllowAt: Long)

    // 固定端口优先：mDNS 被路由器屏蔽时，手机端手动输 IP 也要能连上；占用则退回随机端口
    private val server: ServerSocket = try {
        ServerSocket(PREFERRED_PORT)
    } catch (e: Exception) {
        ServerSocket(0)
    }
    val port: Int = server.localPort
    private var advertiser: ServiceAdvertiser? = null
    private var worker: Thread? = null

    /** 每个连接跑在自己的线程上，拒绝第二个设备时要能立刻答复，不能等前一个会话结束。 */
    private val clients: MutableSet<Socket> = java.util.Collections.synchronizedSet(HashSet())
    private val decision = LinkedBlockingQueue<Boolean>(1)

    /** 当前发送端对「让出投屏」的回答；新设备连接线程在此等待（PRD FS-09 步骤 4） */
    private val takeoverAnswers = LinkedBlockingQueue<Boolean>(1)

    @Volatile
    private var running = false

    @Volatile
    private var decoder: VideoDecoder? = null

    @Volatile
    private var pendingConfig: ByteArray? = null

    /**
     * 音频输出流水线（PRD FS-01「持续发送画面与音频」）。它就是音画同步的主时钟：
     * 画面按它给出的播放时刻对齐，而不是各放各的。
     */
    @Volatile
    private var audioOut: AudioOut? = null

    @Volatile
    private var lastAudioAtMs = 0L

    /** 声音轨是否已被判为中断（超 1 秒没帧），恢复时要把提示收掉并重新对表 */
    @Volatile
    private var audioGone = false

    /** 最近一次采到的音画偏差（毫秒，正=声音比画面晚），打点与大屏状态栏都用它 */
    @Volatile
    private var lastDriftMs = 0

    private var avLoggedAtMs = 0L
    private var avDowngrades = 0

    /** 同步状态机与调整节拍器按会话重建：上一场留下的次数不该影响新一场（PRD「连续三次」的口径） */
    private var avSync = AvSync.Tracker()
    private var pacer = AvSync.Pacer()

    /** 本机分级与画质上限，扫能力表有开销，放到首次真正用到时才算（PRD FS-12 步骤 1） */
    private val gradeFacts: DeviceFacts by lazy { DeviceProbe.facts(context) }
    private val gradePolicy: GradePolicy by lazy { DeviceGrading.policyFor(gradeFacts) }

    /** 当前会话的画质上限：协商结果，解不动时逐档下调并回写对端 */
    @Volatile
    private var ceiling: QualityTier = QualityTier.FHD60

    /**
     * 延迟降级用的档位序列与策略（PRD REQ-077「不达标时先降帧率再降分辨率，不出现黑屏」）。
     * 序列按本机上限生成，上限变了（新会话、或解不动降档）就重建并把当前档夹回来。
     */
    @Volatile
    private var ladder = LatencyLadder(QualityTier.FHD60)

    private val latencyPolicy = LatencyPolicy()

    @Volatile
    private var activeConn: StreamConnection? = null

    @Volatile
    private var activeDeviceId: String? = null

    /** 接管进行中时，旧会话的「连接结束」状态不覆盖大屏上的换人提示 */
    @Volatile
    private var handoverInProgress = false

    /** 旧会话处理线程结束的信号，换人时先等它彻底收尾再授权新设备 */
    @Volatile
    private var sessionDone: CountDownLatch? = null

    /** 发送端暂停后不再解码，Surface 上保留最后一帧（PRD FS-09 步骤 1） */
    @Volatile
    private var paused = false

    /** 断线后的 30 秒保留窗口（PRD FS-10）：其间拒绝新设备，只接受原设备续会 */
    private val recovery = RecoveryWindow()

    /** 大屏按「结束投屏」时置位：随后的断开属于主动收尾，不能进保留窗口 */
    @Volatile
    private var endRequested = false

    /** 是否真的上过屏（收到过 CONFIG）：没出过画面的连接断开不该弹恢复提示 */
    @Volatile
    private var hasCast = false

    /** 授权通过与编码配置到达的时刻，用来算首帧时延（PRD FS-01「首帧时延」、14.1 起播） */
    @Volatile
    private var grantedAtNano = 0L

    @Volatile
    private var configAtNano = 0L

    /** 已打点的首帧时刻：换配置后解码器会重置首帧，这里按新时刻再打一次（降档后的首帧同样要量） */
    @Volatile
    private var loggedFirstRenderNano = 0L

    /** 新解码器流水线要先拿到 IDR 才能喂帧；置位后 P 帧只丢不解。 */
    @Volatile
    private var awaitKeyFrame = false

    /** 界面当前挂着的 Surface；会话可能在它之后才建立，所以两边都要记住。 */
    @Volatile
    private var surface: Surface? = null

    fun start(advertisedName: String) {
        if (running) return
        running = true
        advertiser = ServiceAdvertiser(context, advertisedName, port).also { it.start() }
        onStatus("等待连接 · $advertisedName")
        worker = thread(name = "airmirror-receiver") {
            try {
                while (running) {
                    val socket = server.accept()
                    clients.add(socket)
                    thread(name = "airmirror-conn") {
                        val served = runCatching { handle(socket) }
                            .onFailure { if (running) Log.w(TAG, "connection failed: ${it.message}") }
                            .getOrNull()
                        clients.remove(socket)
                        // 保留窗口内不提示「连接结束」，大屏该显示「正在恢复」
                        if (served != null && !handoverInProgress && recovery.active() == null) {
                            onStatus("$served 连接结束，继续等待")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) onStatus("服务异常：${e.message}")
            }
        }
    }

    /** 用户在屏幕上点了允许/拒绝，或 5 秒无操作由界面代为默认放行。 */
    fun resolve(allowed: Boolean) {
        decision.offer(allowed)
    }

    /**
     * 接收端主动结束当前会话（PRD FS-09 步骤 2/3）：先告知对端再断开，
     * 对端应立即回设备列表，而不是等心跳超时。
     * 大屏按键在主线程上，主线程不能收发网络，所以整段挪到一次性线程里，
     * 并保证「先写完 BYE 再关连接」的顺序，否则对端只会看到意外断链而进入恢复窗口。
     */
    fun endActiveSession() {
        val conn = activeConn ?: return
        Log.i(TAG, "end requested on screen")
        endRequested = true
        recovery.close()
        onRecovery(false)
        thread(name = "airmirror-end") {
            runCatching { conn.send(Protocol.Frame(Protocol.Type.BYE)) }
                .onFailure { Log.w(TAG, "bye not delivered: ${it.message}") }
            conn.close()
        }
    }

    /** Surface 就绪：若已有缓存的编码配置，立刻建解码器。 */
    fun attachSurface(surface: Surface) {
        this.surface = surface
        startPipeline(pendingConfig)
    }

    /**
     * 用当前 Surface 起一条解码流水线。Surface 可能比会话先到（页面一直在前台），
     * 也可能比编码配置后到（回前台重挂），两个方向都要能接上。
     */
    private fun startPipeline(config: ByteArray?) {
        val holder = surface ?: return
        val codec = VideoDecoder(holder)
        if (config != null) {
            runCatching { codec.configure(config) }
                .onFailure { fallBackToOneTierLower(it) }
        }
        decoder = codec
        // 新起的解码器手上没有 IDR，直接喂后续 P 帧会永远不出画（回前台黑屏就是这么来的）：
        // 先只等关键帧，同时请发送端立刻补一个
        awaitKeyFrame = true
        requestKeyFrame()
    }

    /** 请发送端补一个关键帧（新解码器流水线要用）。 */
    private fun requestKeyFrame() = sendControl(SessionControl.sync(), "key frame request")

    /**
     * 发给对端的信令统一走一次性线程：挂 Surface、降档这类调用点来自主线程，
     * 而主线程收发网络会直接被系统拦掉——写在线程外的信令只会静默丢掉，
     * 表现成新解码器等不到 IDR、或降档通知发不出去。
     */
    private fun sendControl(payload: ByteArray, label: String) {
        val conn = activeConn
        if (conn == null) {
            Log.i(TAG, "$label skipped: no active connection")
            return
        }
        thread(name = "airmirror-peer-tx") {
            runCatching { conn.send(Protocol.Frame(Protocol.Type.CONTROL, payload = payload)) }
                .onSuccess { Log.i(TAG, "$label sent") }
                .onFailure { Log.w(TAG, "$label not sent: ${it.message}") }
        }
    }

    fun detachSurface() {
        // 先摘引用再释放：收流线程拿到已释放的解码器时只会静默丢帧，不会抛异常
        surface = null
        val codec = decoder
        decoder = null
        codec?.release()
    }

    /** @return 真正被放行并服务的发送端名称；未授权的连接返回 null，不打扰界面上的推流状态。 */
    private fun handle(socket: Socket): String? {
        StreamConnection(socket).use { conn ->
            // 能力位带音频：老发送端不带时协商结果自动只剩画面，不会硬起一条播不出声的流水线
            val hello = conn.acceptAsServer(
                Build.MODEL,
                Handshake.Capability.VIDEO_H264 or Handshake.Capability.AUDIO_AAC,
            )
            avSync = AvSync.Tracker()
            pacer = AvSync.Pacer()
            lastAudioAtMs = 0L
            audioGone = false
            lastDriftMs = 0
            avDowngrades = 0
            var audioFrames = 0L
            var droppedLate = 0
            val request = readAuth(conn, socket) ?: run {
                refuse(conn, "缺少授权")
                return null
            }
            val deviceId = admit(conn, request, hello.deviceName) ?: return null
            val done = CountDownLatch(1)
            sessionDone = done
            activeConn = conn
            activeDeviceId = deviceId
            endRequested = false
            var received = 0
            var decoded = 0
            var droppedUntilKey = 0
            var byeReceived = false
            try {
                while (running) {
                    val frame = conn.receive()
                    if (frame == null) {
                        Log.i(TAG, "peer closed after received=$received decoded=$decoded")
                        break
                    }
                    when (frame.type) {
                        Protocol.Type.VIDEO -> {
                            if (frame.flags and Protocol.Flags.CONFIG != 0) {
                                hasCast = true
                                configAtNano = System.nanoTime()
                                pendingConfig = frame.payload
                                runCatching { H264.unpackConfig(frame.payload) }
                                    .onSuccess {
                                        onVideoSize(it.width, it.height)
                                        rebuildLadder(QualityTiers.tierWithin(it.width, it.height))
                                    }
                                val existing = decoder
                                if (existing == null) {
                                    // Surface 可能早就在前台等着了，配置到了才建流水线
                                    startPipeline(frame.payload)
                                } else {
                                    runCatching { existing.configure(frame.payload) }
                                        .onFailure { error -> fallBackToOneTierLower(error) }
                                    // 配置后面紧跟着的就是 IDR，从 IDR 起才算新流水线的第一帧
                                    awaitKeyFrame = true
                                }
                                Log.i(TAG, "config received: ${frame.payload.size}B, decoder=${decoder != null}")
                                onStatus("${hello.deviceName} 推流中")
                                onCasting(true, hello.deviceName)
                            } else {
                                received++
                                val codec = decoder
                                val key = frame.flags and Protocol.Flags.KEY_FRAME != 0
                                if (awaitKeyFrame && !key) {
                                    // 新流水线没拿到 IDR 前喂 P 帧，解码器会一直不出画，只会把延迟样本拖没
                                    droppedUntilKey++
                                } else {
                                    if (awaitKeyFrame) Log.i(TAG, "key frame after $droppedUntilKey drops")
                                    awaitKeyFrame = false
                                    if (codec == null) {
                                        if (received == 1) Log.w(TAG, "video frame without surface decoder")
                                    } else if (!paused) {
                                        val clock = audioOut?.clockUs() ?: 0L
                                        if (clock > 0L && AvSync.videoAction(frame.ptsUs, clock, key) == AvSync.VideoAction.DROP) {
                                            // 晚到超容忍区只能「丢到下一个 IDR」：单独抽掉一个中间帧会断参考链、画面发花，
                                            // 关键帧不参与丢弃（PRD FR-09「丢非关键帧，避免花屏」）
                                            awaitKeyFrame = true
                                            requestKeyFrame()
                                            droppedLate++
                                            Log.i(TAG, "video late beyond tolerance, dropping until key frame")
                                        } else {
                                            runCatching { codec.decode(frame.payload, frame.ptsUs) }
                                                .onSuccess { decoded++ }
                                                .onFailure { Log.w(TAG, "decode failed", it) }
                                        }
                                    }
                                }
                                if (received % 30 == 0) {
                                    val rendered = codec?.renderedFrames ?: 0L
                                    logFirstFrame(codec)
                                    val latency = codec?.latency
                                    Log.i(
                                        TAG,
                                        "received=$received decoded=$decoded rendered=$rendered " +
                                            "等关键帧丢 $droppedUntilKey 晚到丢 $droppedLate 音画偏差 ${lastDriftMs}ms " +
                                            "音帧 $audioFrames/已播 ${audioOut?.playedFrames ?: 0} " +
                                            "${latency?.summary() ?: ""}",
                                    )
                                    onStatus(
                                        "${hello.deviceName} · 收 $received / 解码 $decoded / 渲染 $rendered 帧" +
                                            " · P90 ${latency?.percentile(90) ?: 0}ms · 偏差 ${lastDriftMs}ms" +
                                            qualitySuffix(),
                                    )
                                    pollLatency(codec, hello.deviceName)
                                    watchAudioInterrupt(hello.deviceName)
                                }
                            }
                        }

                        Protocol.Type.AUDIO -> {
                            if (frame.flags and Protocol.Flags.CONFIG != 0) {
                                openAudio(frame.payload, hello.deviceName)
                            } else {
                                audioFrames++
                                lastAudioAtMs = System.currentTimeMillis()
                                val out = audioOut
                                if (out == null) {
                                    if (audioFrames == 1L) Log.i(TAG, "audio data before a usable config, ignored")
                                } else if (!paused) {
                                    runCatching { out.write(frame.payload, frame.ptsUs) }
                                        .onFailure {
                                            Log.w(TAG, "audio output failed: ${it.message}")
                                            closeAudio()
                                        }
                                    if (audioFrames % AV_SAMPLE_FRAMES == 0L) sampleAvSync(hello.deviceName)
                                }
                            }
                        }

                        Protocol.Type.PING -> conn.send(Protocol.Frame(Protocol.Type.PONG, payload = frame.payload))
                        Protocol.Type.CONTROL -> {
                            val signal = SessionControl.decode(frame.payload)
                            when (signal.op) {
                                SessionControl.OP_PAUSE -> {
                                    paused = true
                                    Log.i(TAG, "paused by peer")
                                    onStatus("${hello.deviceName} 已暂停")
                                    onNotice("${hello.deviceName} 已暂停")
                                }
                                SessionControl.OP_RESUME -> {
                                    paused = false
                                    lastAudioAtMs = System.currentTimeMillis()
                                    audioGone = false
                                    // 暂停期间两条流各自停了一段，恢复即重新对齐，1 秒内要回到容忍区（PRD FR-09 状态迁移）
                                    resyncAvClock("${hello.deviceName} 恢复投屏")
                                    Log.i(TAG, "resumed by peer")
                                    onStatus("${hello.deviceName} 推流中")
                                    onNotice("${hello.deviceName} 推流中")
                                }
                                SessionControl.OP_TAKEOVER_RESULT -> {
                                    Log.i(TAG, "takeover answer allow=${signal.allow}")
                                    takeoverAnswers.offer(signal.allow)
                                }
                                SessionControl.OP_AUDIO -> {
                                    // 对方主动关了声音：这是选择，不是故障，不能等到 1 秒后弹「被占用」
                                    if (!signal.allow) {
                                        closeAudio()
                                        Log.i(TAG, "peer switched audio off")
                                        onNotice("${hello.deviceName} 已关闭声音")
                                    } else {
                                        Log.i(TAG, "peer switched audio on, waiting for its config frame")
                                        onNotice("${hello.deviceName} 已打开声音")
                                    }
                                }
                                else -> Unit
                            }
                        }
                        Protocol.Type.BYE -> {
                            byeReceived = true
                            return hello.deviceName
                        }
                        else -> Unit
                    }
                }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "connection dropped: ${e.message}")
            } finally {
                // 接管时旧线程可能晚于新会话收尾，只有仍属于自己才清空共享状态并撤回投屏态
                val owns = activeConn === conn
                val dropped = owns && running && !byeReceived && !endRequested && !handoverInProgress &&
                    activeDeviceId != null && hasCast
                if (owns) {
                    activeConn = null
                    paused = false
                    // 声音跟着会话走：留着会让断线后的主时钟继续往前跑，续会时按假偏差乱抽帧
                    closeAudio()
                }
                if (dropped) {
                    // PRD FS-10：短暂中断不重来，保留最后一帧并等原设备在 30 秒内回来
                    startRecovery(activeDeviceId ?: deviceId, hello.deviceName)
                } else {
                    if (owns) {
                        activeDeviceId = null
                        endRequested = false
                        hasCast = false
                    }
                    pairing.trust.releaseSession(deviceId)
                    if (owns) onCasting(false, "")
                }
                done.countDown()
                if (sessionDone === done) sessionDone = null
            }
            return hello.deviceName
        }
    }

    /**
     * HELLO 之后的第一道判定（PRD FS-10）：恢复请求只认保留窗口内的原设备；
     * 窗口期内其他设备一律「正在恢复中，请稍候」，避免断线一瞬被抢占。
     */
    private fun admit(conn: StreamConnection, request: Auth.Request, senderName: String): String? {
        val window = recovery.active()
        if (request.resume) {
            if (window == null || window.deviceId != request.deviceId) {
                Log.i(TAG, "resume refused for ${request.deviceId}, window=${window?.deviceId}")
                refuse(conn, Resume.REASON_LOST)
                onStatus("$senderName 恢复失败，需要重新连接")
                return null
            }
            return resumeSession(conn, window, senderName)
        }
        if (window != null && window.deviceId != request.deviceId) {
            Log.i(TAG, "recovery busy, ${request.deviceId} denied")
            refuse(conn, Resume.REASON_BUSY)
            onStatus("$senderName 连接被拒绝：正在恢复中")
            return null
        }
        return authorize(conn, request, senderName)
    }

    /** 原设备在保留窗口内回来：不弹授权、不重发投屏码，直接续上会话。 */
    private fun resumeSession(conn: StreamConnection, window: RecoveryWindow.Holder, senderName: String): String {
        Log.i(TAG, "session resumed by ${window.deviceId}")
        grant(conn, senderName, permanent = false, window.deviceId)
        onStatus("$senderName 已恢复投屏")
        onNotice("$senderName 已恢复投屏")
        return window.deviceId
    }

    /**
     * 连接意外中断：开 30 秒保留窗口，期间大屏保持最后一帧、拒绝新设备；
     * 到点仍未续会则收口并给出结论（PRD FS-10 步骤 4）。
     */
    private fun startRecovery(deviceId: String, deviceName: String) {
        val window = recovery.open(deviceId, deviceName)
        Log.i(TAG, "link lost, holding ${window.deadline - System.currentTimeMillis()}ms for $deviceId")
        onRecovery(true)
        onStatus("连接中断，正在恢复")
        thread(name = "airmirror-recovery") {
            while (running) {
                val entry = recovery.peek() ?: return@thread
                if (entry.deviceId != deviceId) return@thread
                val left = entry.deadline - System.currentTimeMillis()
                if (left <= 0) break
                Thread.sleep(minOf(left, RECOVERY_POLL_MS))
            }
            if (!running) return@thread
            // 已被续会或主动收尾时 expire 返回 false，不能误判成超时
            if (!recovery.expire(deviceId)) return@thread
            Log.i(TAG, "recovery window expired for $deviceId")
            activeDeviceId = null
            hasCast = false
            pairing.trust.releaseSession(deviceId)
            onRecovery(false)
            onCasting(false, "")
            onStatus("断线超过 30 秒，已结束投屏")
        }
    }

    /** 未授权设备不上屏：HELLO 之后必须先过 AUTH，过不了就直接回错误并断开。 @return 放行后的设备标识 */
    private fun authorize(conn: StreamConnection, request: Auth.Request, senderName: String): String? {
        val verdict = pairing.gate.authorize(request.deviceId, senderName, request.credential)
        return when (verdict) {
            is AuthGate.Verdict.Authorized -> {
                grant(conn, verdict.deviceName, verdict.permanent, request.deviceId)
                request.deviceId
            }
            is AuthGate.Verdict.PendingPrompt -> {
                onPrompt(Prompt(verdict.deviceId, verdict.deviceName, verdict.autoAllowAt))
                val allowed = awaitDecision(verdict.autoAllowAt)
                if (allowed) {
                    val confirmed = pairing.gate.confirm(verdict.deviceId, verdict.deviceName, permanent = true)
                    grant(conn, confirmed.deviceName, confirmed.permanent, verdict.deviceId)
                    verdict.deviceId
                } else {
                    pairing.gate.decline(verdict.deviceId)
                    refuse(conn, "已拒绝")
                    onStatus("已拒绝 $senderName 的投屏请求")
                    null
                }
            }
            is AuthGate.Verdict.TakeoverRequired -> handOver(conn, verdict)
            is AuthGate.Verdict.Rejected -> {
                val reason = when (verdict.reason) {
                    AuthGate.Refused.TOO_MANY_ATTEMPTS -> "投屏码错误次数过多"
                    AuthGate.Refused.ALREADY_USED -> "投屏码已用过"
                    AuthGate.Refused.SESSION_BUSY -> "已有设备在投屏"
                    else -> "投屏码不正确"
                }
                if (verdict.reason == AuthGate.Refused.TOO_MANY_ATTEMPTS) {
                    onStatus("有设备正在尝试连接，已临时锁定投屏码")
                }
                refuse(conn, reason)
                onStatus("$senderName 连接被拒绝：$reason")
                null
            }
        }
    }

    /**
     * 第二台设备凭据有效但已有会话（PRD FS-09 步骤 4/5）：先请当前发送端表态；
     * 允许则「先停旧会话，再让新设备上屏」，拒绝或 60 秒无响应则保留原会话。
     */
    private fun handOver(conn: StreamConnection, verdict: AuthGate.Verdict.TakeoverRequired): String? {
        val old = activeConn
        val oldId = activeDeviceId
        if (old == null || oldId == null) return promoteDirectly(conn, verdict)
        handoverInProgress = true
        try {
            Log.i(TAG, "takeover: ${verdict.deviceName} requests, current=$oldId")
            onStatus("${verdict.deviceName} 请求接管")
            onNotice("${verdict.deviceName} 请求接管")
            takeoverAnswers.clear()
            val notified = runCatching {
                old.send(Protocol.Frame(Protocol.Type.CONTROL, payload = SessionControl.takeover(verdict.deviceName)))
            }.isSuccess
            if (!notified) {
                // 旧连接已经写不进去：按等价于旧会话结束处理，释放后再放行新设备
                Log.w(TAG, "takeover request not delivered, dropping dead session $oldId")
                runCatching { old.close() }
                sessionDone?.await(TAKEOVER_STOP_WAIT_MS, TimeUnit.MILLISECONDS)
                pairing.trust.releaseSession(oldId)
                return promoteDirectly(conn, verdict)
            }
            when (awaitTakeoverAnswer(old)) {
                false -> {
                    Log.i(TAG, "takeover refused by $oldId")
                    onStatus("已拒绝 ${verdict.deviceName} 的接管请求，继续投屏")
                    onNotice("已拒绝 ${verdict.deviceName} 的接管请求")
                    refuse(conn, "已有设备在投屏")
                    return null
                }
                null -> {
                    // PRD FS-09「接管超时」：60 秒无响应按拒绝处理，原会话不动
                    Log.i(TAG, "takeover timed out, keeping $oldId")
                    onStatus("对方未响应，已保留当前会话")
                    onNotice("对方未响应，已保留当前会话")
                    refuse(conn, "已有设备在投屏")
                    return null
                }
                true -> Unit
            }
            Log.i(TAG, "takeover approved by $oldId")
            // 先执行停止：旧会话彻底结束并释放占用后才给新设备授权（PRD「先执行停止，再处理接管请求」）
            runCatching { old.send(Protocol.Frame(Protocol.Type.BYE)) }
            old.close()
            sessionDone?.await(TAKEOVER_STOP_WAIT_MS, TimeUnit.MILLISECONDS)
            pairing.trust.releaseSession(oldId)
            return promoteDirectly(conn, verdict)
        } finally {
            handoverInProgress = false
        }
    }

    /** 没有可通知的旧发送端（会话刚好结束）或旧会话已释放时，直接按接管结果建立新会话。 */
    private fun promoteDirectly(conn: StreamConnection, verdict: AuthGate.Verdict.TakeoverRequired): String? {
        val promoted = pairing.gate.promote(verdict.deviceId, verdict.deviceName, verdict.permanent)
        if (promoted == null) {
            refuse(conn, "已有设备在投屏")
            onStatus("${verdict.deviceName} 接管失败，已保留当前会话")
            onNotice("${verdict.deviceName} 接管失败，已保留当前会话")
            return null
        }
        Log.i(TAG, "takeover complete: ${promoted.deviceName}")
        grant(conn, promoted.deviceName, promoted.permanent, verdict.deviceId)
        onCasting(true, promoted.deviceName)
        return verdict.deviceId
    }

    /**
     * 等当前发送端的回答：true=让出，false=明确拒绝，null=60 秒超时。
     * 期间旧会话自行结束（网络断开或大屏按了结束）也视为可以换人。
     */
    private fun awaitTakeoverAnswer(old: StreamConnection): Boolean? {
        val deadline = System.currentTimeMillis() + TAKEOVER_TIMEOUT_MS
        while (running && System.currentTimeMillis() < deadline) {
            takeoverAnswers.poll(TAKEOVER_POLL_MS, TimeUnit.MILLISECONDS)?.let { return it }
            if (activeConn !== old) return true
        }
        return null
    }

    private fun grant(conn: StreamConnection, deviceName: String, permanent: Boolean, deviceId: String) {
        grantedAtNano = System.nanoTime()
        configAtNano = 0L
        if (permanent) pairing.persistTrusted()
        // 正常授权 / 恢复成功都代表窗口结束，收掉大屏上的恢复提示
        recovery.close()
        onRecovery(false)
        endRequested = false
        conn.send(Protocol.Frame(Protocol.Type.AUTH_RESULT, payload = Auth.result(true, if (permanent) "trusted" else "")))
        sendCeiling(conn)
        onStatus("$deviceName 已连接")
    }

    /**
     * 授权通过后立刻把本机画质上限告诉发端（PRD 8.4 能力协商）：
     * 发端原来按自己屏幕原样编码，1080x2400 这种尺寸老盒子根本解不动。
     */
    private fun sendCeiling(conn: StreamConnection) {
        val tier = QualityTiers.receiverCeiling(gradePolicy, gradeFacts)
        ceiling = tier
        latencyPolicy.reset()
        rebuildLadder(tier)
        runCatching { conn.send(Protocol.Frame(Protocol.Type.CONTROL, payload = Limit.encode(ceilingOf(tier)))) }
            .onFailure { Log.w(TAG, "ceiling not delivered: ${it.message}") }
    }

    /**
     * 解不动就降一档重谈（PRD FS-12「已切换到兼容模式」）：不弹窗，
     * 状态栏提示一次并把新的上限回写对端，对端下一个 CONFIG 会按低档发来。
     */
    private fun fallBackToOneTierLower(error: Throwable) {
        Log.w(TAG, "decoder configure failed: ${error.message}")
        val lower = QualityTiers.downgrade(ceiling)
        if (lower == ceiling) {
            onStatus(ErrorNotice.textFor(ErrorNotice.Cause.DECODE_UNSUPPORTED))
            onNotice(ErrorNotice.textFor(ErrorNotice.Cause.DECODE_UNSUPPORTED))
            return
        }
        ceiling = lower
        rebuildLadder(lower)
        sendControl(Limit.encode(ceilingOf(lower)), "ceiling ${lower.label}")
        onStatus("已切换到兼容模式 · ${lower.label}")
        onNotice("已切换到兼容模式 · ${lower.label}")
    }

    private fun ceilingOf(tier: QualityTier, fps: Int = tier.fps) =
        Limit.Ceiling(tier.width, tier.height, fps, tier.maxBitRateKbps, tier.label)

    /**
     * 降档序列的基线取「本机上限 ∩ 对端实际推流档位」中更低的一档：
     * 手机自己只编到 720p 时，从本机 1080p 上限起步的阶梯第一刀砍下去还是 720p，
     * 等于发了个不动画质的通知，延迟永远不会好转。
     */
    private fun rebuildLadder(base: QualityTier) {
        val start = if (base.rank > ceiling.rank) base else ceiling
        if (!start.isUsable || ladder.step(0).tier == start) return
        val current = ladder.step(latencyPolicy.stepIndex)
        ladder = LatencyLadder(start)
        latencyPolicy.anchorAt(ladder.indexNearest(current.tier, current.fps))
        Log.i(TAG, "latency ladder: ${ladder.steps.joinToString(" → ") { it.text() }} 当前第 ${latencyPolicy.stepIndex} 档")
    }

    /** 周期性统计里带上当前画质档，降过档后大屏不会以为还在原档位跑（PRD 13.1） */
    private fun qualitySuffix(): String {
        val index = latencyPolicy.stepIndex
        return if (index == 0) "" else " · ${ladder.step(index).text()}"
    }

    /**
     * 每个统计窗口判一次延迟，超标就按 REQ-077 的顺序动画质：先降帧率、帧率到底再降分辨率。
     * 只改上限、绝不停流——PRD 写明降级过程「不出现黑屏」，所以到最低档后原地保持。
     * 提示走一次性浮层（REQ-295「角标提示，不弹窗」）。
     */
    private fun pollLatency(codec: VideoDecoder?, deviceName: String) {
        val window = codec?.takeLatencyWindow() ?: return
        val decision = latencyPolicy.onWindow(window, ladder.steps.size)
        Log.i(TAG, "latency ${window.text()} ${decision.move} ${decision.reason}")
        if (decision.move == LatencyMove.KEEP) return
        val step = ladder.step(decision.stepIndex)
        sendControl(Limit.encode(ceilingOf(step.tier, step.fps)), "latency ${step.text()}")
        val text = when (decision.move) {
            LatencyMove.DEGRADE -> "延迟偏高，画质已降到 ${step.text()}"
            else -> "延迟恢复，画质升回 ${step.text()}"
        }
        onNotice("$deviceName · $text")
        onStatus("$deviceName · $text")
    }

    /**
     * 收到音频描述帧：按它建一条 AAC → 声卡的输出流水线（PRD FR-09「以音频为主时钟」）。
     *
     * 建不起来也不许动画面：PRD 的口径是声音出问题只提示，会话不中断。
     */
    private fun openAudio(payload: ByteArray, deviceName: String) {
        val spec = AudioFormat.decode(payload)
        if (spec == null) {
            Log.w(TAG, "audio config unusable: ${String(payload, Charsets.UTF_8)}")
            onNotice(ErrorNotice.textFor(ErrorNotice.Cause.DECODE_UNSUPPORTED))
            return
        }
        if (!AudioDevices.hasOutput(context)) {
            Log.w(TAG, "no audio output device, keep video only")
            onNotice(ErrorNotice.textFor(ErrorNotice.Cause.NO_SPEAKER))
            onStatus("$deviceName · ${ErrorNotice.noticeFor(ErrorNotice.Cause.NO_SPEAKER).headline}")
            return
        }
        closeAudio()
        val out = runCatching { AudioOut(spec) }.getOrNull()
        if (out == null) {
            Log.w(TAG, "audio pipeline unavailable")
            onNotice(ErrorNotice.textFor(ErrorNotice.Cause.DECODE_UNSUPPORTED))
            return
        }
        audioOut = out
        audioGone = false
        lastAudioAtMs = System.currentTimeMillis()
        resyncAvClock("$deviceName 投屏中")
        Log.i(TAG, "audio pipeline up: ${spec.sampleRate}Hz/${spec.channels}ch ${spec.codec}")
    }

    private fun closeAudio() {
        val out = audioOut
        audioOut = null
        audioGone = false
        lastDriftMs = 0
        out?.release()
    }

    /**
     * 主时钟重新对齐（暂停恢复、音频重新出现、换流水线都走这里）：
     * 清掉缓冲里的旧声音，画面与新基准从这一拍起算，1 秒的回到容忍区窗口从头计。
     */
    private fun resyncAvClock(statusText: String) {
        val now = System.currentTimeMillis()
        avSync.onResync(now)
        pacer.reset()
        audioOut?.hardAlign()
        Log.i(TAG, "av clock re-anchored, back in tolerance within ${AvSync.RESYNC_WINDOW_MS}ms")
        onStatus("$statusText · 重新对齐音画")
    }

    /**
     * 音画偏差取样（PRD FR-09）：两条流共用发送端的 nanoTime 基准，各自和「现在」比就是各自的端到端滞后，
     * 差值才是观众感知的偏差。正数=声音比画面晚。
     */
    private fun sampleAvSync(deviceName: String) {
        val out = audioOut ?: return
        val clock = out.clockUs()
        val videoPts = decoder?.lastRenderedPtsUs ?: 0L
        // 主时钟还没锚定、或画面一帧都没上屏时不比对：拿 0 当时间戳会算出一个假的巨大偏差，直接把声音抽花
        if (clock == 0L || videoPts == 0L) return
        val nowUs = System.nanoTime() / 1_000
        val videoLagMs = ((nowUs - videoPts) / 1_000).toInt()
        val audioLagMs = ((nowUs - clock) / 1_000).toInt()
        val driftMs = audioLagMs - videoLagMs
        lastDriftMs = driftMs
        val now = System.currentTimeMillis()
        val action = avSync.onDrift(kotlin.math.abs(driftMs), now)
        if (now - avLoggedAtMs >= AV_LOG_INTERVAL_MS) {
            avLoggedAtMs = now
            Log.i(
                TAG,
                "av-drift ${driftMs}ms 画面滞后 ${videoLagMs}ms 声音滞后 ${audioLagMs}ms " +
                    "缓冲 ${out.levelMs()}ms -> $action/${avSync.state}",
            )
        }
        when (action) {
            AvSync.Action.NONE, AvSync.Action.MICRO_ADJUST -> {
                val move = pacer.onBuffer(AvSync.correction(driftMs, out.levelMs()))
                when (move.action) {
                    AvSync.Pacer.Action.SKIP -> out.skip(move.amount)

                    AvSync.Pacer.Action.PAD -> out.pad(move.amount)
                    AvSync.Pacer.Action.NONE -> Unit
                }
            }

            AvSync.Action.HARD_ALIGN -> {
                // 一次跳变把声音拉到画面那一侧（PRD「允许一帧内的轻微跳变」），欠账按新基准重算
                out.hardAlign()
                pacer.reset()
                avSync.afterHardAlign()
                Log.i(TAG, "hard align at ${driftMs}ms drift, streak=${avSync.hardAlignStreak}")
                onNotice("$deviceName · 音画偏差过大，已重新对齐")
            }

            AvSync.Action.DOWNGRADE -> downgradeForAvDrift(deviceName, driftMs)
        }
    }

    /**
     * 连续三次硬对齐仍拉不回来（PRD FR-09「连续三次则降档」）：
     * 说明这条链路撑不住当前码率的缓冲深度，降一档比继续抽声音有效。
     */
    private fun downgradeForAvDrift(deviceName: String, driftMs: Int) {
        avDowngrades++
        val lower = QualityTiers.downgrade(ceiling)
        if (lower == ceiling) {
            Log.w(TAG, "av drift ${driftMs}ms at lowest tier ${ceiling.label}, keeping stream")
            onNotice("$deviceName · 已在最低档，音画仍有偏差")
            return
        }
        ceiling = lower
        rebuildLadder(lower)
        sendControl(Limit.encode(ceilingOf(lower)), "av-sync ceiling ${lower.label}")
        Log.i(TAG, "av drift ${driftMs}ms -> tier ${lower.label}")
        onNotice("$deviceName · 音画偏差过大，画质已降到 ${lower.label}")
        onStatus("$deviceName · 音画偏差过大，画质已降到 ${lower.label}")
        pacer.reset()
    }

    /** 声音轨超 1 秒没帧 = 被来电或其他应用占用：只提示，画面照投（PRD FS-01/FS-06 异常表）。 */
    private fun watchAudioInterrupt(deviceName: String) {
        if (audioOut == null || paused) return
        val now = System.currentTimeMillis()
        val interrupted = AvSync.audioInterrupted(lastAudioAtMs, now)
        if (interrupted && !audioGone) {
            audioGone = true
            Log.w(TAG, "audio stream silent for more than ${AvSync.INTERRUPT_MS}ms")
            onNotice("$deviceName · ${ErrorNotice.noticeFor(ErrorNotice.Cause.AUDIO_INTERRUPTED).headline}")
        } else if (!interrupted && audioGone) {
            audioGone = false
            resyncAvClock("$deviceName 声音已恢复")
        }
    }

    /**
     * 首帧时延打点（PRD 14.1 与 FS-01「在规定的时间内出现首帧」）：
     * 分别给「编码配置到达→上屏」和「授权通过→上屏」两个口径，后者含协商与首帧编码等待。
     */
    private fun logFirstFrame(decoder: VideoDecoder?) {
        val first = decoder?.firstRenderAtNano ?: 0L
        if (first == 0L || first == loggedFirstRenderNano) return
        loggedFirstRenderNano = first
        val fromConfig = if (configAtNano > 0L) (first - configAtNano) / 1_000_000 else -1L
        val fromAuth = if (grantedAtNano > 0L) (first - grantedAtNano) / 1_000_000 else -1L
        Log.i(TAG, "first frame config+${fromConfig}ms auth+${fromAuth}ms")
    }

    private fun refuse(conn: StreamConnection, reason: String) {
        runCatching { conn.send(Protocol.Frame(Protocol.Type.AUTH_RESULT, payload = Auth.result(false, reason))) }
    }

    private fun readAuth(conn: StreamConnection, socket: Socket): Auth.Request? {
        runCatching { socket.soTimeout = AUTH_TIMEOUT_MS }
        return try {
            while (running) {
                val frame = conn.receive() ?: return null
                if (frame.type == Protocol.Type.AUTH) return Auth.parseRequest(frame.payload)
            }
            null
        } catch (e: IOException) {
            Log.i(TAG, "no AUTH received: ${e.message}")
            null
        } finally {
            runCatching { socket.soTimeout = 0 }
        }
    }

    /** 自动允许开启时 5 秒无操作即放行；关闭时最长等 60 秒无人表态则拒绝。 */
    private fun awaitDecision(autoAllowAt: Long): Boolean {
        // 上一个提示超时后可能还留着没人取的表态，先丢掉，否则会被当成对当前提示的回答
        decision.clear()
        val autoAllow = autoAllowAt != Long.MAX_VALUE
        val budget = if (autoAllow) (autoAllowAt - System.currentTimeMillis()).coerceAtLeast(0L) else PROMPT_MAX_WAIT_MS
        return decision.poll(budget, TimeUnit.MILLISECONDS) ?: autoAllow
    }

    fun stop() {
        running = false
        decision.offer(false)
        takeoverAnswers.offer(false)
        recovery.close()
        endRequested = false
        runCatching { server.close() }
        clients.toList().forEach { socket -> runCatching { socket.close() } }
        clients.clear()
        advertiser?.stop()
        advertiser = null
        worker?.join(1_000)
        worker = null
        decoder?.release()
        decoder = null
        closeAudio()
        pairing.trust.active()?.let { pairing.trust.releaseSession(it) }
        Log.i(TAG, "session stopped")
    }

    private companion object {
        const val TAG = "AirMirrorReceiver"
        const val AUTH_TIMEOUT_MS = 10_000
        const val PROMPT_MAX_WAIT_MS = 60_000L
        const val TAKEOVER_TIMEOUT_MS = 60_000L
        const val TAKEOVER_STOP_WAIT_MS = 3_000L
        const val TAKEOVER_POLL_MS = 200L
        const val RECOVERY_POLL_MS = 200L
        const val PREFERRED_PORT = 41523

        /** 48kHz 下一个 AAC 访问单元约 21ms，5 帧一采约 105ms，与 PRD「每 100ms 采样」同口径 */
        const val AV_SAMPLE_FRAMES = 5L
        const val AV_LOG_INTERVAL_MS = 2_000L
    }
}

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
import io.github.wangnan05563.airmirror.media.VideoDecoder
import io.github.wangnan05563.airmirror.net.ServiceAdvertiser
import io.github.wangnan05563.airmirror.net.StreamConnection
import io.github.wangnan05563.airmirror.pairing.AuthGate
import io.github.wangnan05563.airmirror.pairing.Pairing
import io.github.wangnan05563.airmirror.protocol.Auth
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.Limit
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.SessionControl
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
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

    @Volatile
    private var running = false

    @Volatile
    private var decoder: VideoDecoder? = null

    @Volatile
    private var pendingConfig: ByteArray? = null

    /** 本机分级与画质上限，扫能力表有开销，放到首次真正用到时才算（PRD FS-12 步骤 1） */
    private val gradeFacts: DeviceFacts by lazy { DeviceProbe.facts(context) }
    private val gradePolicy: GradePolicy by lazy { DeviceGrading.policyFor(gradeFacts) }

    /** 当前会话的画质上限：协商结果，解不动时逐档下调并回写对端 */
    @Volatile
    private var ceiling: QualityTier = QualityTier.FHD60

    @Volatile
    private var activeConn: StreamConnection? = null

    /** 发送端暂停后不再解码，Surface 上保留最后一帧（PRD FS-09 步骤 1） */
    @Volatile
    private var paused = false

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
                        if (served != null) onStatus("$served 连接结束，继续等待")
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
     */
    fun endActiveSession() {
        val conn = activeConn ?: return
        Log.i(TAG, "end requested on screen")
        runCatching { conn.send(Protocol.Frame(Protocol.Type.BYE)) }
        conn.close()
    }

    /** Surface 就绪：若已有缓存的编码配置，立刻建解码器。 */
    fun attachSurface(surface: Surface) {
        val codec = VideoDecoder(surface)
        pendingConfig?.let { config ->
            runCatching { codec.configure(config) }
                .onFailure { fallBackToOneTierLower(it) }
        }
        decoder = codec
    }

    fun detachSurface() {
        decoder?.release()
        decoder = null
    }

    /** @return 真正被放行并服务的发送端名称；未授权的连接返回 null，不打扰界面上的推流状态。 */
    private fun handle(socket: Socket): String? {
        StreamConnection(socket).use { conn ->
            val hello = conn.acceptAsServer(Build.MODEL, Handshake.Capability.VIDEO_H264)
            val deviceId = authorize(conn, socket, hello.deviceName) ?: return null
            activeConn = conn
            var received = 0
            var decoded = 0
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
                                pendingConfig = frame.payload
                                decoder?.let {
                                    runCatching { it.configure(frame.payload) }
                                        .onFailure { error -> fallBackToOneTierLower(error) }
                                }
                                Log.i(TAG, "config received: ${frame.payload.size}B, decoder=${decoder != null}")
                                onStatus("${hello.deviceName} 推流中")
                                onCasting(true, hello.deviceName)
                            } else {
                                received++
                                val codec = decoder
                                if (codec == null) {
                                    if (received == 1) Log.w(TAG, "video frame without surface decoder")
                                } else if (!paused) {
                                    runCatching { codec.decode(frame.payload, frame.ptsUs) }
                                        .onSuccess { decoded++ }
                                        .onFailure { Log.w(TAG, "decode failed", it) }
                                }
                                if (received % 30 == 0) {
                                    val rendered = codec?.renderedFrames ?: 0L
                                    Log.i(TAG, "received=$received decoded=$decoded rendered=$rendered")
                                    onStatus("${hello.deviceName} · 收 $received / 解码 $decoded / 渲染 $rendered 帧")
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
                                }
                                SessionControl.OP_RESUME -> {
                                    paused = false
                                    Log.i(TAG, "resumed by peer")
                                    onStatus("${hello.deviceName} 推流中")
                                }
                                else -> Unit
                            }
                        }
                        Protocol.Type.BYE -> return hello.deviceName
                        else -> Unit
                    }
                }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "connection dropped: ${e.message}")
            } finally {
                activeConn = null
                paused = false
                // 解码器跟 Surface 同寿：会话结束时保留，下一次连接的 CONFIG 会重新配置，
                // 否则第二台设备连进来会因 decoder 已被置空而无法上屏
                pairing.trust.releaseSession(deviceId)
                onCasting(false, "")
            }
            return hello.deviceName
        }
    }

    /** 未授权设备不上屏：HELLO 之后必须先过 AUTH，过不了就直接回错误并断开。 @return 放行后的设备标识 */
    private fun authorize(conn: StreamConnection, socket: Socket, senderName: String): String? {
        val request = readAuth(conn, socket) ?: run {
            refuse(conn, "缺少授权")
            return null
        }
        val verdict = pairing.gate.authorize(request.first, senderName, request.second)
        return when (verdict) {
            is AuthGate.Verdict.Authorized -> {
                grant(conn, verdict.deviceName, verdict.permanent, request.first)
                request.first
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

    private fun grant(conn: StreamConnection, deviceName: String, permanent: Boolean, deviceId: String) {
        if (permanent) pairing.persistTrusted()
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
            return
        }
        ceiling = lower
        activeConn?.let { conn ->
            runCatching { conn.send(Protocol.Frame(Protocol.Type.CONTROL, payload = Limit.encode(ceilingOf(lower)))) }
        }
        onStatus("已切换到兼容模式 · ${lower.label}")
    }

    private fun ceilingOf(tier: QualityTier) = Limit.Ceiling(tier.width, tier.height, tier.fps, tier.maxBitRateKbps, tier.label)

    private fun refuse(conn: StreamConnection, reason: String) {
        runCatching { conn.send(Protocol.Frame(Protocol.Type.AUTH_RESULT, payload = Auth.result(false, reason))) }
    }

    private fun readAuth(conn: StreamConnection, socket: Socket): Pair<String, String>? {
        runCatching { socket.soTimeout = AUTH_TIMEOUT_MS }
        return try {
            while (running) {
                val frame = conn.receive() ?: return null
                if (frame.type == Protocol.Type.AUTH) {
                    val request = Auth.parseRequest(frame.payload)
                    return request.deviceId to request.credential
                }
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
        runCatching { server.close() }
        clients.toList().forEach { socket -> runCatching { socket.close() } }
        clients.clear()
        advertiser?.stop()
        advertiser = null
        worker?.join(1_000)
        worker = null
        decoder?.release()
        decoder = null
        pairing.trust.active()?.let { pairing.trust.releaseSession(it) }
        Log.i(TAG, "session stopped")
    }

    private companion object {
        const val TAG = "AirMirrorReceiver"
        const val AUTH_TIMEOUT_MS = 10_000
        const val PROMPT_MAX_WAIT_MS = 60_000L
        const val PREFERRED_PORT = 41523
    }
}

package io.github.wangnan05563.airmirror.pc

import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.SessionControl
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PC 发送端主循环：连接 → 握手/鉴权 → 采屏 → H.264 → 帧发送。
 *
 * 首版限制（未达 T05761 验收）：
 * - 采集用 AWT Robot，不是 Windows Graphics Capture；
 * - 编码用 JCodec 软编，720p 约 5–8fps，达不到 1080p30；
 * - 无音频（AAC/WASAPI 未实现）；
 * - 仅手动 IP + 投屏码，mDNS/扫码未实现。
 */
class SenderEngine(
    private val config: Config,
    private val onStatus: (String) -> Unit,
    private val onStats: (Stats) -> Unit = {},
) {
    data class Config(
        val host: String,
        val port: Int,
        val code: String,
        val deviceName: String,
        val width: Int,
        val height: Int,
        val fps: Int,
    )

    data class Stats(
        val frames: Long = 0,
        val bytes: Long = 0,
        val encodeMs: Long = 0,
        val fps: Double = 0.0,
    )

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var worker: Thread? = null
    private var reader: Thread? = null
    private var connection: PcConnection? = null
    private var capture: ScreenCapture? = null
    private var encoder: H264VideoEncoder? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ runSender() }, "airmirror-pc-sender").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { connection?.send(Protocol.Frame(Protocol.Type.BYE)) }
        runCatching { connection?.close() }
        runCatching { worker?.join(1500) }
        runCatching { reader?.join(800) }
        capture?.close()
        onStatus("已停止")
    }

    private fun runSender() {
        val cfg = config
        onStatus("连接 ${cfg.host}:${cfg.port} …")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(cfg.host, cfg.port), 5000)
            val conn = PcConnection(socket)
            connection = conn
            val ack = conn.connectAsClient(
                deviceName = cfg.deviceName,
                capabilities = Handshake.Capability.VIDEO_H264,
                credential = cfg.code,
            )
            onStatus("握手成功 v${ack.chosenVersion}，对方=${ack.deviceName}，${if (conn.secure) "已加密" else "明文"}")

            val auth = conn.authorize(PcIdentity.deviceId(), cfg.code)
            if (!auth.accepted) throw IOException("投屏码被拒：${auth.reason}")
            onStatus("已授权，开始采集 ${cfg.width}x${cfg.height}@${cfg.fps}")

            startReader(conn)
            val capture = ScreenCapture(cfg.width, cfg.height)
            this.capture = capture
            val encoder = H264VideoEncoder(cfg.width, cfg.height, keyInterval = cfg.fps * 2)
            this.encoder = encoder

            val frameNanos = 1_000_000_000L / cfg.fps.coerceAtLeast(1)
            val start = System.nanoTime()
            var next = start
            var frames = 0L
            var bytes = 0L
            var encodeTotal = 0L
            var statWindow = start
            var statFrames = 0

            while (running.get()) {
                if (paused.get()) {
                    Thread.sleep(30)
                    next = System.nanoTime()
                    continue
                }
                val frame = capture.capture()
                val began = System.nanoTime()
                val encoded = encoder.encode(frame)
                encodeTotal += System.nanoTime() - began
                encoded.config?.let {
                    conn.send(Protocol.Frame(Protocol.Type.VIDEO, flags = Protocol.Flags.CONFIG, payload = it))
                }
                if (encoded.payload.isNotEmpty()) {
                    var flags = 0
                    if (encoded.keyFrame) flags = flags or Protocol.Flags.KEY_FRAME
                    val ptsUs = (System.nanoTime() - start) / 1000
                    conn.send(Protocol.Frame(Protocol.Type.VIDEO, flags = flags, ptsUs = ptsUs, payload = encoded.payload))
                    frames++
                    bytes += encoded.payload.size
                    statFrames++
                }
                val now = System.nanoTime()
                if (now - statWindow >= 1_000_000_000L) {
                    val fps = statFrames * 1e9 / (now - statWindow)
                    onStats(Stats(frames, bytes, encodeTotal / frames.coerceAtLeast(1) / 1_000_000, fps))
                    statWindow = now
                    statFrames = 0
                }
                next += frameNanos
                val waitMs = (next - System.nanoTime()) / 1_000_000
                if (waitMs > 0) Thread.sleep(waitMs) else next = System.nanoTime()
            }
        } catch (e: Exception) {
            if (running.get()) onStatus("失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            running.set(false)
            capture?.close()
            runCatching { connection?.close() }
        }
    }

    private fun startReader(conn: PcConnection) {
        reader = Thread({
            try {
                while (running.get()) {
                    val frame = conn.receive() ?: break
                    when (frame.type) {
                        Protocol.Type.PING -> conn.send(Protocol.Frame(Protocol.Type.PONG, payload = frame.payload))
                        Protocol.Type.CONTROL -> {
                            val signal = SessionControl.decode(frame.payload)
                            when (signal.op) {
                                SessionControl.OP_PAUSE -> paused.set(true)
                                SessionControl.OP_RESUME -> paused.set(false)
                                SessionControl.OP_SYNC -> encoder?.requestKeyFrame()
                            }
                        }
                        Protocol.Type.BYE -> break
                        else -> Unit
                    }
                }
            } catch (_: Exception) {
                // 连接关闭/超时，发送主循环会在下一次写失败时收口
            } finally {
                running.set(false)
            }
        }, "airmirror-pc-reader").apply { isDaemon = true; start() }
    }
}

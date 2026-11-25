package io.github.wangnan05563.airmirror.pc

import io.github.wangnan05563.airmirror.pc.audio.AudioSender
import io.github.wangnan05563.airmirror.pc.audio.WasapiLoopbackCapture
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.protocol.SessionControl
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PC 发送端主循环：连接 → 握手/鉴权 → 采屏 → H.264 → 帧发送。
 *
 * 采集/编码优先走 FFmpeg 子进程（ddagrab/gdigrab + h264_mf 硬编，QSV/OpenH264 回退），
 * 缺失 FFmpeg 时回退 AWT Robot + JCodec（仅用于单链路兜底）。
 */
class SenderEngine(
    private val config: Config,
    private val onStatus: (String) -> Unit,
    private val onStats: (Stats) -> Unit = {},
    private val onAuthorized: (Session) -> Unit = {},
    private val onFirstFrame: () -> Unit = {},
) {
    data class Session(val deviceName: String, val host: String, val port: Int)
    data class Config(
        val host: String,
        val port: Int,
        val code: String,
        val deviceName: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateKbps: Int = 8000,
        /** 投送系统声音；关闭后视频照常。 */
        val audioEnabled: Boolean = true,
        /** 发送增益 0..100，仅影响音频，不改变系统播放音量。 */
        val volumePercent: Int = 100,
    )

    data class Stats(
        val frames: Long = 0,
        val bytes: Long = 0,
        val encodeMs: Long = 0,
        val fps: Double = 0.0,
    )

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val sessionFailed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private var worker: Thread? = null
    private var reader: Thread? = null
    private var connection: PcConnection? = null
    private var capture: ScreenCapture? = null
    private var encoder: H264VideoEncoder? = null
    private var ffmpegEncoder: FfmpegCaptureEncoder? = null
    private var audioSender: AudioSender? = null
    /** 同一会话的视频/音频时间戳基准；建连授权后固定，重连时重置。 */
    private var mediaEpochNanos = 0L
    private val frameCount = AtomicLong()
    private val byteCount = AtomicLong()
    private val firstFrameNotified = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ runSender() }, "airmirror-pc-sender").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { connection?.send(Protocol.Frame(Protocol.Type.BYE)) }
        runCatching { audioSender?.stop() }
        audioSender = null
        runCatching { ffmpegEncoder?.stop() }
        runCatching { connection?.close() }
        runCatching { worker?.join(1500) }
        runCatching { reader?.join(800) }
        capture?.close()
        onStatus("已停止")
    }

    /** 投屏中切换声音；关掉时只停发音频，画面与编码器不动。 */
    fun setAudioEnabled(on: Boolean) {
        audioSender?.setEnabled(on)
    }

    /** 发送增益 0..100；不改变接收端扬声器音量，也不改变系统音量。 */
    fun setVolume(percent: Int) {
        audioSender?.setVolume(percent)
    }

    private fun runSender() {
        while (running.get()) {
            try {
                connectAndRun()
            } catch (e: Exception) {
                if (!running.get()) break
                onStatus("连接中断：${e.message ?: e.javaClass.simpleName}；3 秒后重连")
                Thread.sleep(3000)
            }
        }
    }

    private fun connectAndRun() {
        val cfg = config
        val gen = generation.incrementAndGet()
        sessionFailed.set(false)
        onStatus("连接 ${cfg.host}:${cfg.port} …")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(cfg.host, cfg.port), 5000)
            val conn = PcConnection(socket)
            connection = conn
            val ffmpeg = FfmpegLocator.resolve()
            val audioCapable = ffmpeg != null && WasapiLoopbackCapture.isSupported()
            val ack = conn.connectAsClient(
                deviceName = cfg.deviceName,
                capabilities = Handshake.Capability.VIDEO_H264 or
                    (if (audioCapable) Handshake.Capability.AUDIO_AAC else 0),
                credential = cfg.code,
            )
            onStatus("握手成功 v${ack.chosenVersion}，对方=${ack.deviceName}，${if (conn.secure) "已加密" else "明文"}")

            val auth = conn.authorize(PcIdentity.deviceId(), cfg.code)
            if (!auth.accepted) throw IOException("投屏码被拒：${auth.reason}")
            onAuthorized(Session(ack.deviceName, cfg.host, cfg.port))
            onStatus("已授权，开始采集 ${cfg.width}x${cfg.height}@${cfg.fps}")

            mediaEpochNanos = System.nanoTime()
            startReader(conn, gen)
            if (audioCapable && ack.capabilities and Handshake.Capability.AUDIO_AAC != 0) {
                startAudio(conn, ffmpeg!!)
            } else if (!audioCapable) {
                onStatus("未找到 FFmpeg 或 WASAPI 不可用，本次只投画面")
            } else {
                onStatus("接收端未接受 AAC 音频能力，本次只投画面")
            }
            if (ffmpeg != null) {
                val ok = runFfmpeg(conn, ffmpeg)
                if (!ok && running.get() && !sessionFailed.get()) {
                    runCatching { ffmpegEncoder?.stop() }
                    ffmpegEncoder = null
                    runLegacy(conn)
                }
            } else {
                runLegacy(conn)
            }
            if (sessionFailed.get() && running.get()) throw IOException("接收端连接已断开")
        } finally {
            runCatching { connection?.close() }
            generation.incrementAndGet()
            sessionFailed.set(false)
            runCatching { audioSender?.stop() }
            audioSender = null
            runCatching { ffmpegEncoder?.stop() }
            ffmpegEncoder = null
            capture?.close()
            capture = null
            encoder = null
            connection = null
            mediaEpochNanos = 0L
        }
    }

    private fun startAudio(conn: PcConnection, ffmpeg: java.io.File) {
        val sender = AudioSender(
            ffmpeg = ffmpeg,
            conn = conn,
            initialEnabled = config.audioEnabled,
            initialVolume = config.volumePercent,
            epochNanos = mediaEpochNanos,
            onStatus = onStatus,
            onNotice = onStatus,
            onError = { message -> onStatus("音频：$message") },
        )
        audioSender = sender
        sender.start()
    }

    private fun runFfmpeg(conn: PcConnection, ffmpeg: java.io.File): Boolean {
        val cfg = config
        onStatus("FFmpeg 原生采集：${ffmpeg.absolutePath}")
        val start = mediaEpochNanos.takeIf { it > 0L } ?: System.nanoTime()
        var statWindow = start
        var statFrames = 0
        val encoder = FfmpegCaptureEncoder(
            ffmpeg = ffmpeg,
            width = cfg.width,
            height = cfg.height,
            fps = cfg.fps,
            bitrateKbps = cfg.bitrateKbps,
            onFrame = { encoded, ptsUs ->
                if (!paused.get() && running.get() && !sessionFailed.get()) {
                    try {
                        encoded.config?.let {
                            conn.send(Protocol.Frame(Protocol.Type.VIDEO, flags = Protocol.Flags.CONFIG, payload = it))
                        }
                        if (encoded.payload.isNotEmpty()) {
                            var flags = 0
                            if (encoded.keyFrame) flags = flags or Protocol.Flags.KEY_FRAME
                            conn.send(
                                Protocol.Frame(
                                    Protocol.Type.VIDEO,
                                    flags = flags,
                                    ptsUs = ptsUs - start / 1000,
                                    payload = encoded.payload,
                                ),
                            )
                            afterFrameSent()
                            byteCount.addAndGet(encoded.payload.size.toLong())
                            statFrames++
                        }
                    } catch (e: IOException) {
                        sessionFailed.set(true)
                        runCatching { conn.close() }
                    }
                }
                val now = System.nanoTime()
                if (now - statWindow >= 1_000_000_000L) {
                    val fps = statFrames * 1e9 / (now - statWindow)
                    onStats(Stats(frameCount.get(), byteCount.get(), 0, fps))
                    statWindow = now
                    statFrames = 0
                }
            },
            onError = { message ->
                onStatus(message)
            },
        )
        ffmpegEncoder = encoder
        encoder.start()
        val startedAt = System.nanoTime()
        val noFrameTimeoutNs = 5_000_000_000L
        while (running.get() && !sessionFailed.get()) {
            if (encoder.hasInvalidConfig()) return false
            if (!encoder.isAlive()) return false
            if (encoder.lastFrameNanos() == 0L && System.nanoTime() - startedAt >= noFrameTimeoutNs) {
                onStatus("FFmpeg 5 秒无帧，回退 JCodec")
                return false
            }
            Thread.sleep(100)
        }
        return true
    }

    private fun runLegacy(conn: PcConnection) {
        val cfg = config
        onStatus("未找到 FFmpeg，回退 JCodec（低帧率）：${cfg.width}x${cfg.height}@${cfg.fps}")
        val capture = ScreenCapture(cfg.width, cfg.height)
        this.capture = capture
        val encoder = H264VideoEncoder(cfg.width, cfg.height, keyInterval = cfg.fps * 2)
        this.encoder = encoder

        val frameNanos = 1_000_000_000L / cfg.fps.coerceAtLeast(1)
        val start = mediaEpochNanos.takeIf { it > 0L } ?: System.nanoTime()
        var next = start
        var bytes = 0L
        var encodeTotal = 0L
        var statWindow = start
        var statFrames = 0

        while (running.get() && !sessionFailed.get()) {
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
                afterFrameSent()
                bytes += encoded.payload.size
                statFrames++
            }
            val now = System.nanoTime()
            if (now - statWindow >= 1_000_000_000L) {
                val fps = statFrames * 1e9 / (now - statWindow)
                onStats(Stats(frameCount.get(), bytes, encodeTotal / frameCount.get().coerceAtLeast(1) / 1_000_000, fps))
                statWindow = now
                statFrames = 0
            }
            next += frameNanos
            val waitMs = (next - System.nanoTime()) / 1_000_000
            if (waitMs > 0) Thread.sleep(waitMs) else next = System.nanoTime()
        }
    }

    private fun afterFrameSent() {
        frameCount.incrementAndGet()
        if (firstFrameNotified.compareAndSet(false, true)) onFirstFrame()
    }

    private fun startReader(conn: PcConnection, gen: Long) {
        reader = Thread({
            try {
                while (running.get() && !sessionFailed.get()) {
                    val frame = conn.receive() ?: break
                    when (frame.type) {
                        Protocol.Type.PING -> conn.send(Protocol.Frame(Protocol.Type.PONG, payload = frame.payload))
                        Protocol.Type.CONTROL -> {
                            val signal = SessionControl.decode(frame.payload)
                            when (signal.op) {
                                SessionControl.OP_PAUSE -> paused.set(true)
                                SessionControl.OP_RESUME -> {
                                    paused.set(false)
                                    ffmpegEncoder?.requestKeyFrame()
                                    encoder?.requestKeyFrame()
                                }
                                SessionControl.OP_SYNC -> {
                                    ffmpegEncoder?.requestKeyFrame()
                                    encoder?.requestKeyFrame()
                                }
                                SessionControl.OP_AUDIO -> audioSender?.setEnabled(signal.allow)
                            }
                        }
                        Protocol.Type.BYE -> break
                        else -> Unit
                    }
                }
            } catch (_: Exception) {
                // 连接关闭/超时，交由发送循环收口并在 3 秒后重连
            } finally {
                if (running.get() && generation.get() == gen) sessionFailed.set(true)
            }
        }, "airmirror-pc-reader").apply { isDaemon = true; start() }
    }
}

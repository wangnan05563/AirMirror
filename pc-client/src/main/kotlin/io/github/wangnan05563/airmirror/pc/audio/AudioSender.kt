package io.github.wangnan05563.airmirror.pc.audio

import io.github.wangnan05563.airmirror.protocol.Protocol
import io.github.wangnan05563.airmirror.pc.PcConnection
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 PC 系统声音串成 AirMirror 的 AUDIO 流（REQ-003 / FR-09）。
 *
 * 采集 → 48k/双声道/s16 → FFmpeg AAC-LC → 裸访问单元 → AUDIO 帧。
 * 静音时只停发音频帧，视频继续；恢复时重发 CONFIG 并重置 PTS，避免接收端按旧时间轴追帧。
 */
class AudioSender(
    private val ffmpeg: File,
    private val conn: PcConnection,
    initialEnabled: Boolean = true,
    initialVolume: Int = 100,
    private val epochNanos: Long = 0L,
    private val onStatus: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val enabled = AtomicBoolean(initialEnabled)
    private val volume = java.util.concurrent.atomic.AtomicInteger(initialVolume.coerceIn(0, 100))
    private val pending = ByteArrayOutputStream()
    private var capture: WasapiLoopbackCapture? = null
    private var converter: PcmConverter? = null
    private var encoder: FfmpegAacEncoder? = null
    private var frameIndex = 0L
    private var baseOffsetUs = 0L
    private var configSent = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (!WasapiLoopbackCapture.isSupported()) {
            onError("WASAPI 仅支持 Windows")
            running.set(false)
            return
        }
        if (ffmpeg.isFile) {
            onStatus("系统声音：WASAPI 回环 + AAC ${PcAudioFormat.BIT_RATE_KBPS}kbps")
        } else {
            onError("FFmpeg 不存在：${ffmpeg.absolutePath}")
            running.set(false)
            return
        }
        val source = WasapiLoopbackCapture(
            onPcm = ::onPcm,
            onFormat = { converter = PcmConverter(it) },
            onError = { message ->
                if (running.get()) {
                    onError("WASAPI：$message")
                    running.set(false)
                    closeEncoder()
                }
            },
        )
        capture = source
        source.start()
        if (enabled.get()) sendConfig()
    }

    fun setEnabled(on: Boolean) {
        if (!running.get()) return
        val changed = enabled.getAndSet(on) != on
        if (!changed) return
        if (on) {
            closeEncoder()
            frameIndex = 0L
            pending.reset()
            sendConfig()
            onStatus("系统声音：已开启")
        } else {
            closeEncoder()
            pending.reset()
            onStatus("系统声音：已静音")
        }
    }

    fun setVolume(percent: Int) {
        volume.set(percent.coerceIn(0, 100))
    }

    private fun onPcm(bytes: ByteArray) {
        if (!running.get()) return
        val conv = converter ?: return
        val output = conv.convert(bytes, volume.get())
        if (output.pcm.isEmpty()) return
        if (!enabled.get()) {
            pending.reset()
            return
        }
        val enc = ensureEncoder() ?: return
        pending.write(output.pcm)
        while (pending.size() >= PcAudioFormat.FRAME_BYTES) {
            val all = pending.toByteArray()
            val frame = all.copyOfRange(0, PcAudioFormat.FRAME_BYTES)
            pending.reset()
            if (all.size > PcAudioFormat.FRAME_BYTES) {
                pending.write(all, PcAudioFormat.FRAME_BYTES, all.size - PcAudioFormat.FRAME_BYTES)
            }
            enc.feed(frame)
        }
    }

    private fun ensureEncoder(): FfmpegAacEncoder? {
        if (!running.get() || !enabled.get()) return null
        encoder?.let { return it }
        if (!configSent) sendConfig()
        baseOffsetUs = if (epochNanos > 0L) (System.nanoTime() - epochNanos) / 1000 else 0L
        frameIndex = 0L
        val created = FfmpegAacEncoder(
            ffmpeg = ffmpeg,
            onFrame = { unit -> sendUnit(unit) },
            onError = { message ->
                onError("AAC：$message")
                running.set(false)
            },
        )
        encoder = created
        return created
    }

    private fun sendUnit(unit: ByteArray) {
        if (!running.get() || !enabled.get() || unit.isEmpty()) return
        val pts = baseOffsetUs + PcAudioFormat.ptsUs(frameIndex)
        try {
            conn.send(
                Protocol.Frame(
                    Protocol.Type.AUDIO,
                    flags = 0,
                    ptsUs = pts,
                    payload = unit,
                ),
            )
            frameIndex++
        } catch (e: IOException) {
            onError("发送音频失败：${e.message}")
            running.set(false)
        }
    }

    private fun sendConfig() {
        if (!running.get() || !enabled.get()) return
        val config = PcAudioFormat.encode()
        try {
            conn.send(
                Protocol.Frame(
                    Protocol.Type.AUDIO,
                    flags = Protocol.Flags.CONFIG,
                    payload = config,
                ),
            )
            configSent = true
        } catch (e: IOException) {
            onError("发送音频配置失败：${e.message}")
        }
    }

    private fun closeEncoder() {
        encoder?.let { runCatching { it.close() } }
        encoder = null
        configSent = false
    }

    fun stop() {
        close()
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { capture?.close() }
        capture = null
        closeEncoder()
        pending.reset()
    }
}

package io.github.wangnan05563.airmirror.pc.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FFmpeg 只负责 AAC-LC 编码：PCM 从 stdin 进来，ADTS 从 stdout 出来，[AdtsParser] 剥头后交给发送层。
 *
 * 采集已经由 [WasapiLoopbackCapture] 完成，这里不再让 FFmpeg 抢音频设备；
 * 这样 dshow/立体声混音是否可用与采集无关，编码器也只依赖项目已有的 FFmpeg 运行时分发。
 */
class FfmpegAacEncoder(
    private val ffmpeg: File,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit = {},
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val parser = AdtsParser()
    private val process: Process
    private val stdin: BufferedOutputStream
    private var reader: Thread? = null
    private var stderr: Thread? = null

    init {
        val args = listOf(
            ffmpeg.absolutePath,
            "-hide_banner", "-nostdin", "-loglevel", "warning",
            "-f", "s16le", "-ar", "${PcAudioFormat.SAMPLE_RATE}", "-ac", "${PcAudioFormat.CHANNELS}",
            "-i", "pipe:0",
            "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "${PcAudioFormat.BIT_RATE_KBPS}k",
            "-f", "adts", "-flush_packets", "1",
            "pipe:1",
        )
        process = ProcessBuilder(args).start()
        stdin = BufferedOutputStream(process.outputStream, 64 * 1024)
        running.set(true)
        reader = Thread({ readLoop() }, "airmirror-aac-reader").apply { isDaemon = true; start() }
        stderr = Thread({ stderrLoop() }, "airmirror-aac-stderr").apply { isDaemon = true; start() }
    }

    fun feed(pcm: ByteArray) {
        if (!running.get() || pcm.isEmpty()) return
        try {
            stdin.write(pcm)
            stdin.flush()
        } catch (e: IOException) {
            fail("写入 AAC 编码器失败：${e.message}")
        }
    }

    private fun readLoop() {
        val input = BufferedInputStream(process.inputStream)
        val buffer = ByteArray(16 * 1024)
        try {
            while (running.get()) {
                val read = input.read(buffer)
                if (read <= 0) break
                parser.feed(buffer.copyOf(read)).forEach(onFrame)
            }
        } catch (_: Exception) {
            // 进程被 stop 时流会断，交由 close/上层收口
        } finally {
            if (running.get()) {
                val code = runCatching { process.exitValue() }.getOrDefault(-1)
                fail("AAC 编码器退出：exit=$code")
            }
        }
    }

    private fun stderrLoop() {
        val input = process.errorStream
        val buffer = ByteArray(4 * 1024)
        try {
            while (running.get() && input.read(buffer) > 0) Unit
        } catch (_: Exception) {
            // 只排空 stderr，避免管道满阻塞编码器
        }
    }

    private fun fail(message: String) {
        if (running.getAndSet(false)) onError(message)
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { stdin.close() }
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        runCatching { process.destroy() }
        runCatching { reader?.join(300) }
        runCatching { stderr?.join(300) }
        parser.reset()
    }
}

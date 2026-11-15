package io.github.wangnan05563.airmirror.pc

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Windows 原生采集/编码链：FFmpeg 子进程。
 *
 * 采集优先 `ddagrab`（DXGI Desktop Duplication，GPU 抓屏；WGC 当前环境不可编译时的回退路径），
 * 失败自动回退 `gdigrab`；编码优先 `h264_mf`（Media Foundation 硬件编码，NV12），
 * 失败依次回退 `h264_qsv` / `libopenh264` / `libx264`。
 *
 * 输出 `-f h264` 到 stdout，由 [AnnexBAccessUnitParser] 按 AUD 切帧；
 * JCodec 软编保留为缺 FFmpeg 时的兜底。
 */
class FfmpegCaptureEncoder(
    private val ffmpeg: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateKbps: Int = 8000,
    initialMode: CaptureMode = CaptureMode.AUTO,
    private val onFrame: (Encoded, Long) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    data class Encoded(
        val config: ByteArray?,
        val payload: ByteArray,
        val keyFrame: Boolean,
    )

    enum class CaptureMode { AUTO, DDAGRAB, GDIGRAB }

    private val running = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)
    private val processRef = AtomicReference<Process?>()
    private var worker: Thread? = null
    private var stderrThread: Thread? = null
    @Volatile private var lastConfig: ByteArray? = null
    @Volatile private var activeMode: CaptureMode = initialMode
    @Volatile private var encoderName: String = "h264_qsv"
    private val encoderCandidates = ArrayList<String>()
    private var encoderIndex = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        encoderCandidates.clear()
        encoderCandidates.addAll(selectEncoders(ffmpeg))
        encoderIndex = 0
        encoderName = encoderCandidates.firstOrNull() ?: "libopenh264"
        worker = Thread({ runLoop() }, "airmirror-ffmpeg").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        processRef.get()?.let { runCatching { it.destroy() } }
        runCatching { worker?.join(1200) }
        processRef.get()?.let { runCatching { it.destroyForcibly() } }
    }

    fun requestKeyFrame() {
        // FFmpeg 没有运行时强制 IDR 的通用接口；重启子进程让下一帧从 IDR 开始。
        restartRequested.set(true)
        processRef.get()?.let { runCatching { it.destroy() } }
    }

    fun isAlive(): Boolean = running.get()

    private fun runLoop() {
        var fallbackTried = false
        while (running.get()) {
            val mode = resolveMode()
            val process = try {
                startProcess(mode)
            } catch (e: Exception) {
                onError("FFmpeg 启动失败：${e.message}")
                break
            }
            processRef.set(process)
            val exitedEarly = process.waitFor(1500, TimeUnit.MILLISECONDS)
            if (exitedEarly && running.get() && !restartRequested.get()) {
                val code = process.exitValue()
                val err = readStderrTail(process)
                if (mode == CaptureMode.DDAGRAB && !fallbackTried) {
                    fallbackTried = true
                    activeMode = CaptureMode.GDIGRAB
                    onError("ddagrab 不可用（exit=$code），回退 gdigrab")
                    continue
                }
                val next = nextEncoder()
                if (next != null) {
                    onError("$encoderName 启动失败（exit=$code），切换 $next：$err")
                    continue
                }
                onError("FFmpeg 提前退出（exit=$code）：$err")
                break
            }
            readStdout(process)
            processRef.set(null)
            if (!running.get()) break
            val exit = runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!exit && restartRequested.get()) {
                runCatching { process.destroyForcibly() }
            }
            if (restartRequested.getAndSet(false)) {
                onError("按请求重启编码器以生成 IDR")
                continue
            }
            val code = runCatching { process.exitValue() }.getOrDefault(-1)
            if (running.get() && code != 0) {
                val err = readStderrTail(process)
                if (mode == CaptureMode.DDAGRAB && !fallbackTried) {
                    fallbackTried = true
                    activeMode = CaptureMode.GDIGRAB
                    onError("ddagrab 失败（exit=$code），回退 gdigrab：$err")
                    continue
                }
                onError("FFmpeg 退出（exit=$code）：$err")
            }
            break
        }
        running.set(false)
    }

    private fun resolveMode(): CaptureMode = when (activeMode) {
        CaptureMode.AUTO -> CaptureMode.DDAGRAB
        else -> activeMode
    }

    private fun startProcess(mode: CaptureMode): Process {
        val args = ArrayList<String>()
        args += listOf(
            ffmpeg.absolutePath,
            "-hide_banner", "-nostdin", "-loglevel", "warning",
            "-fflags", "nobuffer", "-flags", "low_delay",
        )
        if (mode == CaptureMode.DDAGRAB) {
            args += listOf(
                "-f", "lavfi",
                "-i", "ddagrab=0:framerate=$fps:output_fmt=bgra",
                "-vf", "hwdownload,format=bgra,scale=$width:$height:flags=fast_bilinear",
            )
        } else {
            args += listOf(
                "-f", "gdigrab",
                "-framerate", "$fps",
                "-draw_mouse", "1",
                "-i", "desktop",
                "-vf", "scale=$width:$height:flags=fast_bilinear",
            )
        }
        args += listOf(
            "-an",
            "-c:v", encoderName,
            "-b:v", "${bitrateKbps}k",
            "-maxrate", "${bitrateKbps}k",
            "-bufsize", "${(bitrateKbps / 2).coerceAtLeast(512)}k",
            "-g", "${fps * 2}",
            "-keyint_min", "${fps * 2}",
            "-bf", "0",
        )
        when (encoderName) {
            "libx264" -> args += listOf(
                "-pix_fmt", "yuv420p",
                "-preset", "ultrafast",
                "-tune", "zerolatency",
                "-profile:v", "high",
                "-x264-params", "repeat-headers=1:threads=4",
            )
            "h264_qsv" -> args += listOf(
                "-pix_fmt", "nv12",
                "-preset", "veryfast",
                "-look_ahead", "0",
                "-async_depth", "1",
            )
            "h264_mf" -> args += listOf(
                "-pix_fmt", "nv12",
                "-hw_encoding", "1",
                "-rate_control", "ld_vbr",
                "-scenario", "display_remoting",
            )
            "libopenh264" -> args += listOf(
                "-pix_fmt", "yuv420p",
                "-profile:v", "baseline",
                "-rc_mode", "1",
                "-allow_skip_frames", "1",
            )
        }
        args += listOf(
            "-bsf:v", "h264_metadata=aud=insert",
            "-f", "h264",
            "-flush_packets", "1",
            "pipe:1",
        )
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(false)
        pb.redirectInput(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        process.outputStream.close()
        stderrThread = Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    while (running.get()) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) System.err.println("[ffmpeg] $line")
                    }
                }
            }
        }, "airmirror-ffmpeg-stderr").apply { isDaemon = true; start() }
        return process
    }

    private fun readStdout(process: Process) {
        val parser = AnnexBAccessUnitParser()
        val input = process.inputStream
        val buffer = ByteArray(64 * 1024)
        try {
            while (running.get()) {
                val read = input.read(buffer)
                if (read <= 0) break
                val units = parser.feed(buffer.copyOf(read))
                for (unit in units) emit(unit)
            }
            for (unit in parser.flush()) emit(unit)
        } catch (_: Exception) {
            // 进程被 stop/restart 时流会中断，由外层收口。
        }
    }

    private fun emit(unit: AnnexB.AccessUnit) {
        val config = unit.config
        val changed = config != null && !config.contentEquals(lastConfig ?: ByteArray(0))
        if (changed) lastConfig = config
        onFrame(
            Encoded(
                config = if (config != null && (changed || unit.keyFrame)) config else null,
                payload = unit.payload,
                keyFrame = unit.keyFrame,
            ),
            System.nanoTime() / 1000,
        )
    }

    private fun readStderrTail(process: Process): String {
        return runCatching {
            val text = process.errorStream.readNBytes(1200).toString(Charsets.UTF_8)
            text.replace('\n', ' ').take(400)
        }.getOrDefault("")
    }

    private fun selectEncoders(ffmpeg: File): List<String> {
        val output = runCatching {
            val p = ProcessBuilder(ffmpeg.absolutePath, "-hide_banner", "-encoders").redirectErrorStream(true).start()
            val text = p.inputStream.readNBytes(20_000).toString(Charsets.UTF_8)
            p.waitFor(3, TimeUnit.SECONDS)
            text
        }.getOrDefault("")
        val available = ArrayList<String>()
        if (output.contains("h264_mf")) available += "h264_mf"
        if (output.contains("h264_qsv")) available += "h264_qsv"
        if (output.contains("libopenh264")) available += "libopenh264"
        if (output.contains("libx264")) available += "libx264"
        return available.ifEmpty { listOf("libopenh264") }
    }

    private fun nextEncoder(): String? {
        while (encoderIndex + 1 < encoderCandidates.size) {
            encoderIndex++
            val candidate = encoderCandidates[encoderIndex]
            if (candidate != encoderName) {
                encoderName = candidate
                return candidate
            }
        }
        return null
    }
}

object FfmpegLocator {
    fun resolve(): File? {
        val candidates = ArrayList<File>()
        System.getProperty("airmirror.ffmpeg")?.takeIf { it.isNotBlank() }?.let { candidates += File(it) }
        System.getenv("AIRMIRROR_FFMPEG")?.takeIf { it.isNotBlank() }?.let { candidates += File(it) }
        val appHome = File(System.getProperty("app.home") ?: System.getenv("APP_HOME") ?: System.getProperty("user.dir"))
        val userDir = File(System.getProperty("user.dir"))
        for (root in listOf(appHome, userDir)) {
            candidates += File(root, "ffmpeg/ffmpeg.exe")
            candidates += File(root, "ffmpeg.exe")
            candidates += File(root, "ffmpeg/bin/ffmpeg.exe")
        }
        candidates.firstOrNull { it.isFile }?.let { return it }
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(File.pathSeparatorChar)) {
            val file = File(dir, "ffmpeg.exe")
            if (file.isFile) return file
        }
        return null
    }
}

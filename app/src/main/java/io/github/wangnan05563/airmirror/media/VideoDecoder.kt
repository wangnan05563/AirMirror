package io.github.wangnan05563.airmirror.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import io.github.wangnan05563.airmirror.perf.FrameLatency
import io.github.wangnan05563.airmirror.perf.LatencyWindow
import java.nio.ByteBuffer

/** 接收端 H.264/H.265 硬解码，输出到 [Surface]。编码由配置帧的标记位决定，见 [configure]。 */
class VideoDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0

    /** 当前流水线在解的画幅（收到编码配置后才有值，未出画为 0）：画面角标按它显示分辨率。 */
    val codedWidth: Int get() = width
    val codedHeight: Int get() = height

    /** 摘 Surface 与收流线程并发：界面已销毁后仍可能拿到旧引用，此时喂帧要静默跳过而不是抛「codec is released」。 */
    @Volatile
    private var released = false

    @Volatile
    var renderedFrames = 0L
        private set

    /** 采集→上屏的分位数：以帧时间戳（nanoTime 基准）与投递显示的时刻之差为样本。 */
    val latency = FrameLatency()

    /**
     * 延迟降级策略用的窗口统计：每 [WINDOW_FRAMES] 帧出一张，[takeLatencyWindow] 取走即空。
     * 累计分位数不能用来做降级——起播那几秒的高延迟会永久留在直方图里，
     * 之后无论怎么降档 p90 都下不来，策略就会一路降到底。
     */
    private val window = FrameLatency()
    private val pendingWindow = java.util.concurrent.atomic.AtomicReference<LatencyWindow?>(null)
    private var windowFrames = 0L

    /** 本解码器第一次真正投递显示的时刻（nanoTime），未出画为 0；接收端据此算首帧耗时。 */
    @Volatile
    var firstRenderAtNano = 0L
        private set

    /** 最近一帧真正上屏的流时间戳（与音频帧同一 nanoTime 基准）：音画同步拿它和音频主时钟比。 */
    @Volatile
    var lastRenderedPtsUs = 0L
        private set

    /**
     * 用发送端的配置帧（内含宽高）建解码器。
     * [videoCodec] 由调用方从 CONFIG 帧的标记位得出：标记位只在对端声明过对应硬解时才会出现，
     * 所以这里不需要再判「能不能解」，只需按编码取 MIME 与配置块结构。
     */
    fun configure(configPayload: ByteArray, videoCodec: VideoCodec = VideoCodec.AVC) {
        if (released) return
        val config = H264.unpackConfig(configPayload)
        width = config.width
        height = config.height
        // 换配置就是换一条流水线，首帧与延迟分布都按新流水线重新计
        latency.reset()
        window.reset()
        windowFrames = 0L
        pendingWindow.set(null)
        firstRenderAtNano = 0L
        lastRenderedPtsUs = 0L
        val format = when (videoCodec) {
            VideoCodec.AVC -> {
                val units = H264.splitAnnexB(H264.normalize(config.annexB))
                require(units.isNotEmpty()) { "empty codec config" }
                MediaFormat.createVideoFormat(videoCodec.mimeType, width, height).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(units[0]))
                    if (units.size > 1) setByteBuffer("csd-1", ByteBuffer.wrap(units[1]))
                }
            }

            VideoCodec.HEVC -> {
                // H.265 多一段 VPS。少任何一段都不建解码器：那样是「配成功、画面全黑」，
                // 报错反而好查，交给调用方按 PRD 回退 H.264
                val units = requireNotNull(H265.csdUnits(config.annexB)) { "incomplete hevc codec config" }
                MediaFormat.createVideoFormat(videoCodec.mimeType, width, height).apply {
                    units.forEachIndexed { index, unit -> setByteBuffer("csd-$index", ByteBuffer.wrap(unit)) }
                }
            }
        }
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        // 旧实例已经不在手上了，先把字段清空：建解码器失败时若还挂着它，
        // 之后每一个视频帧都在已释放的实例上抛异常，画面再也回不来
        codec = null
        val decoder = MediaCodec.createDecoderByType(videoCodec.mimeType)
        try {
            decoder.configure(format, surface, null, 0)
            decoder.start()
        } catch (e: Exception) {
            runCatching { decoder.release() }
            throw e
        }
        codec = decoder
    }

    /**
     * 解码器跑着跑着报了致命错误（MediaCodec 出错后不再接受任何调用）：把这个实例丢掉，
     * 但保留本对象，下一个 CONFIG 会重建流水线。留着死实例等于让后续每帧都撞异常。
     */
    fun discardUnusable() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
    }

    /** 解码器只认宽高非零，配置前用占位值；真实尺寸由 SPS 决定。 */
    fun setExpectedSize(w: Int, h: Int) {
        width = w
        height = h
    }

    fun decode(data: ByteArray, ptsUs: Long) {
        if (released) return
        val decoder = codec ?: return
        val index = decoder.dequeueInputBuffer(10_000)
        if (index < 0) return
        val buffer = inputBuffer(decoder, index)
        buffer.clear()
        buffer.put(data)
        decoder.queueInputBuffer(index, 0, data.size, ptsUs, 0)
        drain(decoder)
    }

    fun drain() {
        if (released) return
        codec?.let { drain(it) }
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    decoder.releaseOutputBuffer(index, true)
                    renderedFrames++
                    val nowNano = System.nanoTime()
                    if (firstRenderAtNano == 0L) firstRenderAtNano = nowNano
                    if (info.presentationTimeUs > 0L) lastRenderedPtsUs = info.presentationTimeUs
                    if (info.presentationTimeUs > 0) {
                        val sample = (nowNano / 1_000 - info.presentationTimeUs) / 1_000
                        latency.record(sample)
                        window.record(sample)
                        if (++windowFrames >= WINDOW_FRAMES) {
                            windowFrames = 0L
                            val snapshot = window.snapshot()
                            window.reset()
                            // 只留最新一张：收流线程取慢了也不该把过期窗口当成当前状态
                            pendingWindow.set(snapshot)
                        }
                    }
                }
            }
        }
    }

    /** 取走最近一张窗口统计（没有则 null）；降级策略只关心最新一段，取走即清空。 */
    fun takeLatencyWindow(): LatencyWindow? = pendingWindow.getAndSet(null)

    private fun inputBuffer(decoder: MediaCodec, index: Int): ByteBuffer =
        if (Build.VERSION.SDK_INT >= 21) decoder.getInputBuffer(index)!! else decoder.inputBuffers[index]

    fun release() {
        released = true
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
    }

    companion object {
        /** 约 0.5~1 秒一个窗口（60fps/30fps），足够出分位数又不至于对抖动反应过慢 */
        const val WINDOW_FRAMES = 30L
    }
}

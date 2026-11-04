package io.github.wangnan05563.airmirror.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import io.github.wangnan05563.airmirror.perf.AvSync
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import io.github.wangnan05563.airmirror.privacy.Redactor

/**
 * 接收端音频输出：AAC 解码 → AudioTrack 播放，同时它就是 PRD FR-09 说的**主时钟**。
 *
 * 时钟口径：写进 AudioTrack 的样本按流时间戳换算，播出去多少就是「听众正听到哪一刻」；
 * 画面拿这个值和自己的 pts 比，才知道自己是早到该等还是晚到该丢。
 *
 * 缓冲水位维持在 [AvSync] 的 40–80ms：高于上限追帧（丢整帧），低于下限轻微拉长（垫静音），
 * 单次动作不超过 10ms——按拍直接丢帧会把声音抽成一串洞。
 */
class AudioOut(private val spec: AudioFormat.Spec) {

    /** 解码出来还没写进声卡的 PCM，排在一条短队列上 */
    data class Pcm(val bytes: ByteArray, val ptsUs: Long)

    /** 第一个写进声卡的样本对应的流时间戳；硬对齐或清空后重新锚定 */
    @Volatile
    private var anchorPtsUs = -1L

    private var anchorSamples = 0L

    @Volatile
    private var writtenSamples = 0L

    private val frameBytes = AudioFormat.pcmBytes(1, spec.channels)
    private val queue = LinkedBlockingQueue<Pcm>(QUEUE_FRAMES)

    @Volatile
    private var released = false

    /** 写进声卡的帧数（含垫的静音），只用于打点与低版本的水位兜底 */
    @Volatile
    var playedFrames = 0L
        private set

    private var droppedByQueue = 0L

    private var writeRejected = 0L

    private var anchoredLogged = false

    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, spec.sampleRate, spec.channels)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        // 裸访问单元必须靠 csd-0 才知道自己是 AAC-LC/几声道，缺了它解码器只会一路拿静音顶替
        spec.csd()?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
        configure(format, null, null, 0)
        start()
    }

    private val track: AudioTrack = newTrack()

    init {
        runCatching { track.play() }
            .onFailure { Log.w(TAG, "audio track start failed: ${Redactor.throwable(it)}") }
        thread(name = "airmirror-audio-out", isDaemon = true) { pump() }
    }

    /** 喂一个 AAC 访问单元；解码同步取，所以调用线程就是排队线程。 */
    fun write(aac: ByteArray, ptsUs: Long) {
        if (released) return
        val index = codec.dequeueInputBuffer(QUEUE_TIMEOUT_US)
        if (index < 0) {
            Log.w(TAG, "audio decoder busy, frame dropped")
            return
        }
        val buffer = inputBuffer(index)
        buffer.clear()
        buffer.put(aac)
        codec.queueInputBuffer(index, 0, aac.size, ptsUs, 0)
        drain()
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (!released) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val buffer = outputBuffer(index)
                    val bytes = ByteArray(info.size.coerceAtLeast(0))
                    if (info.size > 0) {
                        buffer.position(info.offset)
                        buffer.get(bytes, 0, info.size)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bytes.isNotEmpty()) enqueue(Pcm(bytes, info.presentationTimeUs))
                }
            }
        }
    }

    private fun enqueue(pcm: Pcm) {
        if (!queue.offer(pcm)) {
            droppedByQueue++
            if (droppedByQueue % 50 == 0L) Log.w(TAG, "audio queue full, dropped $droppedByQueue frames")
        }
    }

    /** 写入线程：AudioTrack 的阻塞写天然给播放定速，队列取空时静等，不让 CPU 空转。 */
    private fun pump() {
        while (!released) {
            val pcm = try {
                queue.poll(20, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            } ?: continue
            if (released) return
            val written = runCatching { track.write(pcm.bytes, 0, pcm.bytes.size) }.getOrDefault(0)
            if (written <= 0) {
                // 声卡一口不吞通常是被独占或设备掉了：不记就只会看到「音画偏差 0ms」这种没有结论的现象
                if (writeRejected++ % 50 == 0L) Log.w(TAG, "audio track rejected $writeRejected writes")
                continue
            }
            if (anchorPtsUs < 0L) {
                anchorPtsUs = pcm.ptsUs
                anchorSamples = writtenSamples
                // 每次硬对齐都会重新锚定一次，取证看接收端的 hard align 日志；这里只报第一条，免得刷屏
                if (!anchoredLogged) {
                    anchoredLogged = true
                    Log.i(TAG, "audio playback anchored at ${pcm.ptsUs}us")
                }
            }
            writtenSamples += written / frameBytes
            playedFrames++
        }
    }

    /**
     * 缓冲水位（毫秒）：解码完还没写的 + 写进声卡还没播出去的。
     * 只算声卡那一头会低估一大截（解码队列能压到半秒），40–80ms 的目标区间就成了假判据。
     */
    fun levelMs(): Int {
        val queued = queue.sumOf { it.bytes.size / frameBytes }.toLong()
        val pending = (writtenSamples - playedSamples()).coerceAtLeast(0L)
        return ((queued + pending) * 1_000 / spec.sampleRate).toInt()
    }

    /**
     * 主时钟：此刻听众正听到的那一帧对应的时间戳；还没开播回 0，调用方据此判「还不能拿来对表」。
     * `flush()` 会把声卡的播放头清零，所以硬对齐后不需要再补偿旧值。
     */
    fun clockUs(): Long {
        val anchor = anchorPtsUs
        if (anchor < 0L) return 0L
        return anchor + (playedSamples() - anchorSamples).coerceAtLeast(0L) * 1_000_000L / spec.sampleRate
    }

    private fun playedSamples(): Long =
        if (Build.VERSION.SDK_INT >= 21) track.playbackHeadPosition.toLong() else writtenSamples - queue.size.toLong()

    /** 追帧：丢掉排在声卡前面的若干个 PCM 帧，把声音往前赶。 */
    fun skip(frames: Int) {
        repeat(frames) { queue.poll() }
    }

    /** 轻微拉长：垫一段静音，把声音往后压一点，不改播放速率（PRD「避免可听出的变速」）。 */
    fun pad(millis: Int) {
        val samples = (millis.toLong() * spec.sampleRate / 1_000).toInt()
        if (samples <= 0) return
        queue.offer(Pcm(ByteArray(samples * frameBytes), nextPtsUs()))
    }

    private fun nextPtsUs(): Long {
        val anchor = anchorPtsUs
        if (anchor < 0L) return 0L
        val queuedSamples = queue.sumOf { it.bytes.size / frameBytes }.toLong()
        return anchor + (writtenSamples + queuedSamples - anchorSamples) * 1_000_000L / spec.sampleRate
    }

    /**
     * 硬对齐 / 重新对齐：一次跳变把声音拉到画面那一侧（PRD「允许一帧内的轻微跳变」）。
     * 清空解码队列与声卡缓冲，下一条 PCM 重新锚定时间戳。
     */
    fun hardAlign() {
        queue.clear()
        runCatching {
            track.pause()
            track.flush()
            track.play()
        }
        anchorPtsUs = -1L
        anchorSamples = 0L
        writtenSamples = 0L
    }

    private fun newTrack(): AudioTrack {
        // 声道掩码跟采集到的声道数走：单声道却按立体声建声卡，播出来是加速的变调噪音
        val mask = if (spec.channels == 1) AndroidAudioFormat.CHANNEL_OUT_MONO else AndroidAudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(spec.sampleRate, mask, AndroidAudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4 * 1024)
        return if (Build.VERSION.SDK_INT >= 21) {
            AudioTrack.Builder()
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(spec.sampleRate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                // USAGE_MEDIA 让镜像声音走媒体音量通道，与投屏内容的听感一致
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                spec.sampleRate,
                mask,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
                AudioTrack.MODE_STREAM,
            )
        }
    }

    private fun inputBuffer(index: Int): ByteBuffer =
        if (Build.VERSION.SDK_INT >= 21) codec.getInputBuffer(index)!! else codec.inputBuffers[index]

    private fun outputBuffer(index: Int): ByteBuffer =
        if (Build.VERSION.SDK_INT >= 21) codec.getOutputBuffer(index)!! else codec.outputBuffers[index]

    fun release() {
        if (released) return
        released = true
        queue.clear()
        runCatching { track.stop() }
        runCatching { track.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        private const val TAG = "AirMirrorAudio"

        /** 约 0.5 秒的解码队列：再长就等于自己造延迟，追帧也追不回来 */
        private const val QUEUE_FRAMES = 24
        private const val QUEUE_TIMEOUT_US = 2_000L
    }
}

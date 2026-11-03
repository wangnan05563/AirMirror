package io.github.wangnan05563.airmirror.sender

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import io.github.wangnan05563.airmirror.media.AudioFormat

/**
 * 发送端系统声音采集（PRD FS-01「持续发送画面与音频」+ 8.4 附录「系统屏幕录制授权后采集屏幕与系统音频」）。
 *
 * 走 `AudioPlaybackCapture`：授权来自本次投屏的 MediaProjection，按 usage 匹配媒体、游戏与系统提示音，
 * 这三类之外系统一律不给采（PRD 只承诺「系统音频」，不承诺通话声音）。
 * 本机自己播放的声音按规则采不到，所以「手机投到本机、本机又放同一份声音」不会形成回环。
 *
 * 需要录音权限：系统把「采集本机声音」算在 `RECORD_AUDIO` 里。
 * 没给权限、或系统干脆不给建采集器时按同一口径回落——画面继续投，声音留在手机上。
 */
object SystemAudio {

    /** 一次读取的字节数：约 16ms 立体声 16-bit，够编码器凑帧，又不至于给声音再加一层可见延迟 */
    const val READ_BYTES = 3_072

    sealed interface Opened {
        /** [via] 记录走的是哪条通道，取证时要能看出来 */
        class Ready(val record: AudioRecord, val spec: AudioFormat.Spec, val via: String) : Opened
        object NoPermission : Opened
        class Unsupported(val reason: String) : Opened
        class Failed(val reason: String) : Opened
    }

    fun hasPermission(context: Context): Boolean =
        context.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, context.packageName) ==
            PackageManager.PERMISSION_GRANTED

    fun open(projection: MediaProjection, hasPermission: Boolean): Opened {
        if (!hasPermission) return Opened.NoPermission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Opened.Unsupported("当前系统未开放声音采集")
        }
        return try {
            ready(buildRecord(projection), "playback-capture")
        } catch (e: Exception) {
            Opened.Failed(e.message ?: "采集通道建不起来")
        }
    }

    private fun buildRecord(projection: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioFormat.SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_IN_STEREO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioRecord.Builder()
            .setAudioFormat(pcmFormat())
            .setBufferSizeInBytes(if (minBuffer > 0) minBuffer * 2 else READ_BYTES * 8)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    private fun pcmFormat(): AndroidAudioFormat = AndroidAudioFormat.Builder()
        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AndroidAudioFormat.CHANNEL_IN_STEREO)
        .setSampleRate(AudioFormat.SAMPLE_RATE)
        .build()

    private fun ready(record: AudioRecord, via: String): Opened {
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return Opened.Failed("采集器没初始化起来（$via）")
        }
        val spec = AudioFormat.Spec(
            record.sampleRate,
            // 声道数以采集器实际给的为准：写死 2 声道遇到单声道设备会把时间戳算错一倍
            if (Build.VERSION.SDK_INT >= 23) record.channelCount else AudioFormat.CHANNELS,
            AudioFormat.BIT_RATE_KBPS,
            AudioFormat.CODEC_AAC_LC,
        )
        if (!spec.isUsable) {
            // 采集器给了个对端建不起解码器的参数（少见采样率、超过两声道），
            // 硬发过去只会让大屏一声不响，不如现在就按「采不到」回落
            runCatching { record.release() }
            return Opened.Failed("采集参数没法建解码器（${spec.sampleRate}Hz/${spec.channels}ch）")
        }
        runCatching { record.startRecording() }
            .onFailure {
                runCatching { record.release() }
                return Opened.Failed("采集没跑起来（$via）：${it.message}")
            }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { record.release() }
            return Opened.Failed("采集没跑起来（$via）")
        }
        return Opened.Ready(record, spec, via)
    }
}

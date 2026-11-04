package io.github.wangnan05563.airmirror.device

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import io.github.wangnan05563.airmirror.media.VideoCodec
import io.github.wangnan05563.airmirror.privacy.Redactor

/**
 * 按 MIME 问硬编/硬解能力（PRD 8.7 的编码选择顺序只看「硬解」，不看有没有解码器）。
 *
 * 与 [DeviceProbe] 的分工：那边算的是「这台机器属于哪一级、能放多大画面」，
 * 这里回答的是「这个编码在这一侧是不是硬件通路」。两者都要成立才能承诺 H.265。
 */
object CodecProbe {
    private const val TAG = "AirMirrorCodec"

    /** 探测一次即可：能力表不会中途变化，反复遍历很贵 */
    @Volatile
    private var cachedEncoders: Set<VideoCodec>? = null

    fun hardwareEncoders(): Set<VideoCodec> {
        cachedEncoders?.let { return it }
        val found = LinkedHashSet<VideoCodec>()
        forEachCodec { info, types ->
            if (!info.isEncoder || !isHardware(info)) return@forEachCodec
            for (codec in VideoCodec.entries) if (codec.mimeType in types) found.add(codec)
        }
        cachedEncoders = found.toSet()
        Log.i(TAG, "hardware encoders: ${found.joinToString { it.wire }.ifEmpty { "none" }}")
        return cachedEncoders!!
    }

    /**
     * 这个编码在这一尺寸上是否有硬解通路。API 21 以下没有能力查询接口，
     * 而 H.265 本身是 Android 5.0 之后才进系统的，所以老系统一律回 false 即可。
     */
    fun hardwareDecoder(codec: VideoCodec, width: Int, height: Int): Boolean {
        if (Build.VERSION.SDK_INT < 21) return false
        var supported = false
        forEachCodec { info, types ->
            if (info.isEncoder || !isHardware(info) || codec.mimeType !in types) return@forEachCodec
            val video = runCatching { info.getCapabilitiesForType(codec.mimeType).videoCapabilities }.getOrNull() ?: return@forEachCodec
            if (video.isSizeSupported(width, height)) supported = true
        }
        return supported
    }

    private fun forEachCodec(block: (MediaCodecInfo, List<String>) -> Unit) {
        if (Build.VERSION.SDK_INT < 21) return
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                block(info, info.supportedTypes.toList())
            }
        } catch (e: Exception) {
            Log.w(TAG, "codec list probe failed: ${Redactor.throwable(e)}")
        }
    }

    /**
     * 「硬件」判定：API 29 起系统直接告知是否纯软件；更老的版本只能按命名习惯认。
     * AOSP 的软解一律叫 `OMX.google.*` 或 `c2.android.*`，误判的代价是对端多收一条
     * 不支持的编码声明，随后建解码器失败会自动回落，不会黑屏。
     */
    private fun isHardware(info: MediaCodecInfo): Boolean = when {
        Build.VERSION.SDK_INT >= 29 -> !info.isSoftwareOnly
        else -> {
            val name = info.name.lowercase()
            !name.startsWith("omx.google") && !name.startsWith("c2.android") && !name.contains(".sw.")
        }
    }
}

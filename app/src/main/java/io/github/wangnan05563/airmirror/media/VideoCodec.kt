package io.github.wangnan05563.airmirror.media

import io.github.wangnan05563.airmirror.device.NetworkBand

/** 镜像画面的视频编码（PRD 8.7 编码选择顺序）。`wire` 是线上口径，两边都按它协商；`display` 给人看。 */
enum class VideoCodec(val wire: String, val mimeType: String, val display: String) {
    AVC("avc", "video/avc", "H.264"),
    HEVC("hevc", "video/hevc", "H.265");

    companion object {
        fun fromWire(value: String): VideoCodec? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * 编码选择（PRD 8.7「接收端硬解支持 H.265 且带宽充足时优先 H.265；否则 H.264」）。
 *
 * 三条都得成立，缺一条就回 H.264：
 * - 对端**硬解**支持 H.265。只认软解不算：软解 HEVC 在老盒子上比硬解 H.264 更慢，
 *   拿它换带宽反而把延迟打爆，PRD 特意写了「硬解」两个字。
 * - 本机有 H.265 硬编。对端能解、自己解不动编也没用。
 * - 带宽充足。2.4GHz 已被档位协商压到 720p30（PRD 8.7 适用条件列），
 *   那一档 PRD 给的编码就是 H.264 High；频段探测不到时按「不设限」处理，
 *   与 [io.github.wangnan05563.airmirror.device.QualityTiers.negotiate] 同一口径——
 *   探测失败不该擅自动画质。
 *
 * 对端是老版本（没上报 `codec=`）时一律 H.264：宁可不省带宽，也不能让老接收端收到自己认不出的码流。
 */
object CodecChoice {

    fun select(peerHardwareDecode: Set<VideoCodec>, localHardwareEncode: Set<VideoCodec>, band: NetworkBand): VideoCodec {
        if (band == NetworkBand.GHZ_2) return VideoCodec.AVC
        return if (VideoCodec.HEVC in peerHardwareDecode && VideoCodec.HEVC in localHardwareEncode) VideoCodec.HEVC else VideoCodec.AVC
    }

    /** 协商结论的成因，写进日志与状态栏，避免「省了带宽却说不清为什么」 */
    fun reason(codec: VideoCodec, peerHardwareDecode: Set<VideoCodec>, localHardwareEncode: Set<VideoCodec>, band: NetworkBand): String = when {
        band == NetworkBand.GHZ_2 -> "2.4GHz 用 H.264"
        codec == VideoCodec.HEVC -> "双方 H.265 硬解硬编"
        VideoCodec.HEVC !in peerHardwareDecode -> "对方无 H.265 硬解"
        VideoCodec.HEVC !in localHardwareEncode -> "本机无 H.265 硬编"
        else -> "默认 H.264"
    }

    /** 线上描述符：`hevc+avc`（按优先级从左到右）。空集合发空串，接收端解出来当作「未上报」。 */
    fun render(codecs: Set<VideoCodec>): String =
        VideoCodec.entries.filter { it in codecs }.joinToString("+") { it.wire }

    /** 认不出的名字直接丢掉，不让一个错字段把整条上限作废（与 [io.github.wangnan05563.airmirror.protocol.Limit] 同一容错口径） */
    fun parse(descriptor: String): Set<VideoCodec> =
        descriptor.split('+', ',', ' ').mapNotNull { VideoCodec.fromWire(it.trim()) }.toSet()
}

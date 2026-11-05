package io.github.wangnan05563.airmirror.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.StatFs
import android.util.Log
import android.view.Display
import io.github.wangnan05563.airmirror.media.VideoCodec
import io.github.wangnan05563.airmirror.privacy.Redactor

/**
 * 采集 [DeviceFacts]：系统版本、内存、H.264 硬解能力、输出分辨率、可用存储与网络。
 * 判定逻辑全在 [DeviceGrading]，这里只负责把 Android 的事实读准。
 */
object DeviceProbe {
    private const val TAG = "AirMirrorProbe"
    private const val UHD_WIDTH = 3840
    private const val UHD_HEIGHT = 2160

    fun facts(context: Context): DeviceFacts {
        val decode = decoderCoverage()
        return DeviceFacts(
            apiLevel = Build.VERSION.SDK_INT,
            totalRamMb = totalRamMb(context),
            hardwareDecodeWidth = decode.first,
            hardwareDecodeHeight = decode.second,
            hardwareDecodeAvailable = decode.third,
            outputSupports4K = outputSupports4K(context),
            freeStorageMb = freeStorageMb(context),
            hasWifi = hasWifi(context),
            canCreateLocalService = true,
        )
    }

    /** 探测一次即可，结果在进程内缓存：能力表不会中途变化，反复遍历很贵 */
    @Volatile
    private var cached: GradePolicy? = null

    fun policy(context: Context): GradePolicy {
        cached?.let { return it }
        return DeviceGrading.policyFor(facts(context)).also { cached = it }
    }

    /** 设置页/待连接页用一句话如实告知本机等级与最高画质（PRD FS-12 步骤 1、2） */
    fun summary(policy: GradePolicy): String = DeviceGrading.summary(policy)

    /**
     * 采集 4K 四道门的输入（PRD FS-05）。建连协商时尚无实测带宽，[GateInputs.sustainedKbps] 留 0，
     * 会话中由 BandwidthGauge 的量接管（那时判的是「带宽骤降」，见 ReceiverSession）。
     */
    fun gates(context: Context): GateInputs = GateInputs(
        screenReaches4K = outputSupports4K(context),
        hevc4KHardDecode = CodecProbe.hardwareDecoder(VideoCodec.HEVC, UHD_WIDTH, UHD_HEIGHT),
        interfaceReaches4K = interfaceReaches4K(context),
        band = band(context),
    )

    /**
     * 本机实际可达的最高档 = 分级 ∩ 硬解 ∩ 四道门。设置页拿它决定列哪几档，
     * 接收端拿它上报上限，两边必须是同一个数，否则会出现「页面上写着 4K、对面收到 1080p」。
     */
    fun localCeiling(context: Context): QualityTier {
        val policy = policy(context)
        val capability = QualityTiers.receiverCeiling(policy, facts(context))
        return QualityTiers.lower(capability, FourGates.evaluate(gates(context)).maxTier)
    }

    /**
     * 输出接口能否承载 4K。Android 不暴露 HDMI 版本与 TMDS 速率，能拿到的最硬证据是
     * 显示子系统按 EDID 给出的模式表：表里有 4K@≥30 的模式，说明这条输出真的扫描得出 4K。
     * 内置屏同样按模式表判。API 23 以前没有模式表，退回按当前输出像素判。
     */
    private fun interfaceReaches4K(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        manager.displays.any { it.modes4K() }
    } catch (e: Exception) {
        Log.w(TAG, "interface probe failed: ${Redactor.throwable(e)}")
        false
    }

    private fun Display.modes4K(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return reaches(UHD_WIDTH, UHD_HEIGHT)
        return runCatching {
            supportedModes.any {
                it.physicalWidth >= UHD_WIDTH && it.physicalHeight >= UHD_HEIGHT && it.refreshRate >= 30.0
            }
        }.getOrElse { reaches(UHD_WIDTH, UHD_HEIGHT) }
    }

    /**
     * H.264 硬解能到哪个档位：直接问解码器「这个尺寸支持吗」，比读能力区间表更准，
     * 也避开不同厂商 Range 的边界差异。API 21 以下没有可用的探测接口，
     * 按「有硬解但能力未知」上报 0，由分级规则落到 C 级封顶 720p——
     * 那些系统本来也不该承诺更高分辨率。
     */
    private fun decoderCoverage(): Triple<Int, Int, Boolean> {
        if (Build.VERSION.SDK_INT < 21) return Triple(0, 0, true)
        var bestWidth = 0
        var bestHeight = 0
        var found = false
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                val type = info.supportedTypes.firstOrNull { it == MediaFormat.MIMETYPE_VIDEO_AVC } ?: continue
                val video = info.getCapabilitiesForType(type).videoCapabilities ?: continue
                for (tier in QualityTier.Ordered) {
                    if (tier.width <= bestWidth) break
                    if (video.isSizeSupported(tier.width, tier.height)) {
                        bestWidth = tier.width
                        bestHeight = tier.height
                        found = true
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "decoder probe failed: ${Redactor.throwable(e)}")
        }
        return Triple(bestWidth, bestHeight, found)
    }

    private fun totalRamMb(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024L * 1024L)
    } catch (e: Exception) {
        Log.w(TAG, "memory probe failed: ${Redactor.throwable(e)}")
        0L
    }

    private fun outputSupports4K(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        manager.displays.any { it.reaches(3840, 2160) }
    } catch (e: Exception) {
        Log.w(TAG, "output probe failed: ${Redactor.throwable(e)}")
        false
    }

    /** 电视盒子常用外接显示，取真实尺寸（含系统装饰前的全屏像素） */
    private fun Display.reaches(width: Int, height: Int): Boolean {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        getRealMetrics(metrics)
        return metrics.widthPixels >= width && metrics.heightPixels >= height
    }

    private fun freeStorageMb(context: Context): Long = try {
        val stat = StatFs(context.cacheDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong / (1024L * 1024L)
    } catch (e: Exception) {
        Log.w(TAG, "storage probe failed: ${Redactor.throwable(e)}")
        Long.MAX_VALUE
    }

    private fun hasWifi(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)

    /**
     * 当前 Wi-Fi 频段（PRD 8.7 的档位适用条件）。系统不暴露频段时回 UNKNOWN，
     * 按「不设网络上限」处理——宁可画质高一点，也不要在探测失败时擅自降档。
     */
    @Suppress("DEPRECATION")
    fun band(context: Context): NetworkBand {
        // WifiInfo.frequency 是 API 21：4.4 上直接读会抛异常，按「不设网络上限」处理
        if (Build.VERSION.SDK_INT < 21) return NetworkBand.UNKNOWN
        return try {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val info = manager.connectionInfo
            when {
                info == null || info.frequency <= 0 -> NetworkBand.UNKNOWN
                info.frequency >= 4900 -> NetworkBand.GHZ_5
                else -> NetworkBand.GHZ_2
            }
        } catch (e: Exception) {
            Log.w(TAG, "band probe failed: ${Redactor.throwable(e)}")
            NetworkBand.UNKNOWN
        }
    }
}

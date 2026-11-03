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

/**
 * 采集 [DeviceFacts]：系统版本、内存、H.264 硬解能力、输出分辨率、可用存储与网络。
 * 判定逻辑全在 [DeviceGrading]，这里只负责把 Android 的事实读准。
 */
object DeviceProbe {
    private const val TAG = "AirMirrorProbe"

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
            Log.w(TAG, "decoder probe failed: ${e.message}")
        }
        return Triple(bestWidth, bestHeight, found)
    }

    private fun totalRamMb(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024L * 1024L)
    } catch (e: Exception) {
        Log.w(TAG, "memory probe failed: ${e.message}")
        0L
    }

    private fun outputSupports4K(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < 17) {
            false
        } else {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            manager.displays.any { it.reaches(3840, 2160) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "output probe failed: ${e.message}")
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
        Log.w(TAG, "storage probe failed: ${e.message}")
        Long.MAX_VALUE
    }

    private fun hasWifi(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)

    /**
     * 当前 Wi-Fi 频段（PRD 8.7 的档位适用条件）。系统不暴露频段时回 UNKNOWN，
     * 按「不设网络上限」处理——宁可画质高一点，也不要在探测失败时擅自降档。
     */
    @Suppress("DEPRECATION")
    fun band(context: Context): NetworkBand = try {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val info = manager.connectionInfo
        when {
            info == null || info.frequency <= 0 -> NetworkBand.UNKNOWN
            info.frequency >= 4900 -> NetworkBand.GHZ_5
            else -> NetworkBand.GHZ_2
        }
    } catch (e: Exception) {
        Log.w(TAG, "band probe failed: ${e.message}")
        NetworkBand.UNKNOWN
    }
}

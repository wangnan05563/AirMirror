package io.github.wangnan05563.airmirror.device

/**
 * 设备兼容分级（PRD 7.2）。判定条件「满足任一即降级」，所以从高到低逐档设门槛，
 * 掉到某一档的条件一旦触发就锁定，不再往上够。
 *
 * 本文件不依赖 android.*，输入由 DeviceProbe 采集，便于在 JVM 单测里覆盖全部降级路径。
 */
data class DeviceFacts(
    val apiLevel: Int,
    val totalRamMb: Long,
    /** 硬解能否覆盖该分辨率（AVC 解码器能力表） */
    val hardwareDecodeWidth: Int,
    val hardwareDecodeHeight: Int,
    val hardwareDecodeAvailable: Boolean,
    /** 输出侧能否达到 4K（屏幕/ HDMI 能力） */
    val outputSupports4K: Boolean,
    val freeStorageMb: Long,
    val hasWifi: Boolean,
    val canCreateLocalService: Boolean,
)

enum class Grade { A, B, C, UNSUPPORTED }

/** 分级带来的运行策略（PRD 7.2 运行策略列 + 8.8 老设备性能策略表）。 */
data class GradePolicy(
    val grade: Grade,
    val maxTier: QualityTier,
    /** C 级协议按需加载、闲置 5 分钟释放；其余常驻 */
    val protocolsResident: Boolean,
    val animationsEnabled: Boolean,
    val thumbnailsEnabled: Boolean,
    /** 日志保留天数，C 级只留崩溃日志 */
    val logRetentionDays: Int,
    val residentMemoryTargetMb: Int,
    /** 触发降级的原因，供设置页如实告知 */
    val reasons: List<String>,
    /** 不支持时的结论文案；A/B/C 时为空 */
    val unsupportedReason: String,
)

object DeviceGrading {
    const val MIN_API = 19

    /** 不支持：无 Wi-Fi、系统裁剪无法创建本地服务、可用存储 < 100MB（PRD 7.2） */
    private const val MIN_FREE_STORAGE_MB = 100L
    private const val UHD_WIDTH = 3840
    private const val UHD_HEIGHT = 2160
    private const val FHD_WIDTH = 1920
    private const val FHD_HEIGHT = 1080
    private const val HD_WIDTH = 1280
    private const val HD_HEIGHT = 720

    fun gradeOf(facts: DeviceFacts): Grade = when {
        facts.apiLevel < MIN_API -> Grade.UNSUPPORTED
        !facts.hasWifi -> Grade.UNSUPPORTED
        !facts.canCreateLocalService -> Grade.UNSUPPORTED
        facts.freeStorageMb < MIN_FREE_STORAGE_MB -> Grade.UNSUPPORTED
        !facts.hardwareDecodeAvailable -> Grade.C
        else -> when {
            facts.apiLevel >= 29 && facts.totalRamMb >= 3_000 &&
                covers(facts, UHD_WIDTH, UHD_HEIGHT) && facts.outputSupports4K -> Grade.A

            facts.apiLevel >= 24 && facts.totalRamMb >= 2_000 && covers(facts, FHD_WIDTH, FHD_HEIGHT) -> Grade.B

            else -> Grade.C
        }
    }

    /**
     * C 级封顶 720p：硬解只够 720p 时即使系统版本高也按 7.2 的「或硬解不可用」口径降级，
     * 但内存与系统版本带来的降级更严重，取两者中更保守的一档。
     */
    fun policyFor(facts: DeviceFacts): GradePolicy {
        val reasons = ArrayList<String>()
        var ceiling = gradeOf(facts)
        if (ceiling == Grade.UNSUPPORTED) {
            return GradePolicy(
                grade = Grade.UNSUPPORTED,
                maxTier = QualityTier.OFF,
                protocolsResident = false,
                animationsEnabled = false,
                thumbnailsEnabled = false,
                logRetentionDays = 0,
                residentMemoryTargetMb = 0,
                reasons = emptyList(),
                unsupportedReason = unsupportedMessage(facts),
            )
        }
        if (!covers(facts, HD_WIDTH, HD_HEIGHT)) {
            reasons += "硬解达不到 720p"
            ceiling = Grade.C
        }
        return when (ceiling) {
            Grade.A -> GradePolicy(
                Grade.A, QualityTier.UHD60, true, true, true, 7, 180, reasons, "",
            )

            Grade.B -> GradePolicy(
                Grade.B, QualityTier.FHD60, true, false, false, 3, 120, reasons, "",
            )

            else -> GradePolicy(
                Grade.C, QualityTier.HD30, false, false, false, 0, 80, reasons, "",
            )
        }
    }

    /** 硬解能否覆盖到给定的宽高（解码能力上限决定画质档位，而不是靠猜） */
    fun covers(facts: DeviceFacts, width: Int, height: Int): Boolean =
        facts.hardwareDecodeAvailable && facts.hardwareDecodeWidth >= width && facts.hardwareDecodeHeight >= height

    /** 设置页/待连接页用两行如实告知本机等级与最高画质（PRD FS-12 步骤 1、2） */
    fun summary(policy: GradePolicy): String = when (policy.grade) {
        Grade.UNSUPPORTED -> policy.unsupportedReason
        else -> "本机等级：${policy.grade.name} 级\n最高画质：${policy.maxTier.label}"
    }

    private fun unsupportedMessage(facts: DeviceFacts): String = when {
        facts.apiLevel < MIN_API -> "本机系统低于 Android 4.4，无法运行投屏接收端。可改用电视自带投屏。"
        !facts.hasWifi -> "本机没有可用的 Wi-Fi 模块，局域网投屏无法工作。可改用有线网络或系统投屏。"
        !facts.canCreateLocalService -> "本机系统被裁剪，无法创建本地服务。可使用电视自带投屏。"
        else -> "可用存储不足 ${MIN_FREE_STORAGE_MB}MB，请先清理空间再投屏。"
    }
}

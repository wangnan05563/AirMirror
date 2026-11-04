package io.github.wangnan05563.airmirror.device

/**
 * 4K 的四道门（PRD FS-05 判定条件）。四道全通才允许进入 4K 档，
 * 任何一道不通，该档位不仅协商拿不到，设置页里也必须不出现——
 * 「宣传 4K、实际 720p」是本产品明确要避开的失真（PRD 13.1）。
 *
 * 本文件不依赖 android.*：探测结果由 [DeviceProbe.gates] 采集后交进来，
 * 每道门不通时压到哪一档全部在这里判定，可以在 JVM 单测里逐条覆盖。
 */
enum class FourGate(val label: String, val requirement: String, val unmet: String) {
    SCREEN("屏幕输出", "面板与系统输出达到 4K", "最高档位止于 1080p"),
    DECODER("解码能力", "支持 H.265 的 4K 硬解", "降为 1440p 或 1080p"),
    NETWORK("网络带宽", "5GHz 且可用带宽满足 4K 档码率", "降至可维持档位"),
    INTERFACE("接口带宽", "输出接口带宽足以承载 4K", "不启用 4K 档位"),
}

/**
 * 四道门的输入。[sustainedKbps] 是会话内实测的稳定带宽，0 表示还没量到
 * （建连协商时一定量不到，运行中由 [io.github.wangnan05563.airmirror.perf.BandwidthGauge] 补上）。
 */
data class GateInputs(
    val screenReaches4K: Boolean,
    val hevc4KHardDecode: Boolean,
    val interfaceReaches4K: Boolean,
    val band: NetworkBand,
    val sustainedKbps: Int = 0,
)

/** 判定结果：哪些门不通 + 因此本机最高能到哪个档。 */
data class GateReport(val unmet: List<FourGate>, val maxTier: QualityTier) {
    val reaches4K: Boolean get() = unmet.isEmpty()

    /** 界面上一行说清「为什么没有 4K」，而不是默默少一栏 */
    fun unmetReason(): String =
        if (reaches4K) "" else "未达 4K：" + unmet.joinToString("、") { it.label }
}

object FourGates {

    /** 4K 档的码率下限（kbps），PRD 8.7 表：4K = 20–40 Mbps */
    private val UHD_MIN_KBPS = QualityTier.UHD60.minBitRateKbps

    fun evaluate(inputs: GateInputs): GateReport {
        val unmet = ArrayList<FourGate>(4)
        if (!inputs.screenReaches4K) unmet += FourGate.SCREEN
        if (!inputs.hevc4KHardDecode) unmet += FourGate.DECODER
        // 「量不到」不等于「够」：4K 的适用条件写死了 5GHz，频段都探不到就不承诺 4K，
        // 但已量到的速率只在真的低于档位下限时才算不通
        val networkOk = inputs.band == NetworkBand.GHZ_5 &&
            (inputs.sustainedKbps <= 0 || inputs.sustainedKbps >= UHD_MIN_KBPS)
        if (!networkOk) unmet += FourGate.NETWORK
        if (!inputs.interfaceReaches4K) unmet += FourGate.INTERFACE
        if (unmet.isEmpty()) return GateReport(emptyList(), QualityTier.UHD60)

        var cap = QualityTier.UHD60
        // 不通时压到哪一档照 PRD 的「不满足时」列：屏幕输出只到 1080p，
        // 解码与接口各让到 1440p，网络按可维持档位；多条不通取最低的那一档
        if (FourGate.SCREEN in unmet) cap = QualityTiers.lower(cap, QualityTier.FHD60)
        if (FourGate.DECODER in unmet) cap = QualityTiers.lower(cap, QualityTier.QHD)
        if (FourGate.INTERFACE in unmet) cap = QualityTiers.lower(cap, QualityTier.QHD)
        if (FourGate.NETWORK in unmet) cap = QualityTiers.lower(cap, networkCeiling(inputs))
        return GateReport(unmet, cap)
    }

    /**
     * 网络这一道门单独能撑到哪一档（PRD 8.7 适用条件 + 1440p/4K 都要求 5GHz）。
     * 2.4GHz 明确封顶 720p30；频段探不到时按「1080p 默认档」处理——
     * 1080p60 的适用条件只有「B 级及以上」，不额外要求网络证据。
     */
    private fun networkCeiling(inputs: GateInputs): QualityTier = when {
        inputs.band == NetworkBand.GHZ_2 -> QualityTier.HD30
        inputs.sustainedKbps > 0 -> QualityTiers.tierWithinBandwidth(inputs.sustainedKbps)
        else -> QualityTier.FHD60
    }

    /**
     * 设置页/待连接页的一行结论（PRD FS-05「显示本机最高支持…，与实际能力一致」）。
     * [ceiling] 是最终上限（分级 ∩ 硬解 ∩ 四道门），不只报门的结果：
     * 门通了但硬解只到 1080p 时，页面上也必须写 1080p。
     */
    fun headline(ceiling: QualityTier, report: GateReport): String {
        val base = "本机最高支持 ${ceiling.label}"
        return if (report.reaches4K) base else "$base · ${report.unmetReason()}"
    }

    /** 只看四道门时的写法（设置页里门与能力同档，取门的结果即可） */
    fun headline(report: GateReport): String = headline(report.maxTier, report)
}

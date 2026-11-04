package io.github.wangnan05563.airmirror.device

/**
 * 画质档位（PRD 8.7）。分辨率/编码/码率区间与适用条件都写死在这里，
 * 发送端按「接收端上限 ∩ 本机分级 ∩ 网络条件」取交集，超出档位一律降档而不是硬塞。
 */
enum class QualityTier(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val minBitRateKbps: Int,
    val maxBitRateKbps: Int,
) {
    UHD60("4K", 3840, 2160, 60, 20_000, 40_000),
    QHD("1440p", 2560, 1440, 60, 12_000, 20_000),
    FHD60("1080p60", 1920, 1080, 60, 6_000, 12_000),
    HD30("720p30", 1280, 720, 30, 2_500, 5_000),
    SD480("480p", 854, 480, 30, 800, 2_000),
    OFF("不支持", 0, 0, 0, 0, 0);

    /** 档位序号越大画质越低，供降档/回档步进 */
    internal val rank: Int get() = ordinal

    val isUsable: Boolean get() = this != OFF

    companion object {
        /** 从高档到低档排列，取交集时按此顺序命中第一个可用的 */
        val Ordered: List<QualityTier> = listOf(UHD60, QHD, FHD60, HD30, SD480)
    }
}

/** 网络条件（PRD 8.7 适用条件列：4K/1440p 要求 5GHz，2.4GHz 只能到 720p30） */
enum class NetworkBand { UNKNOWN, GHZ_2, GHZ_5 }

/**
 * 一档画质落到具体编码参数：目标长边不超过档位，等比缩放并取偶数（编码器要求宽高为 2 的倍数）。
 */
data class EncodeProfile(
    val tier: QualityTier,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitRateKbps: Int,
) {
    val bitRate: Int get() = bitRateKbps * 1000
}

/**
 * 协商结论的展示文案（PRD 13.1「能力协商，不展示不可达档位」）：
 * 档位必须说清楚，被谁压下来也说清楚，用户才不会以为「宣传 4K、实际 720p」。
 */
data class QualityNote(val tier: QualityTier, val reason: String) {
    fun text(): String = when {
        !tier.isUsable -> "当前无法投屏"
        reason.isEmpty() -> "已按 ${tier.label} 投屏"
        else -> "已按 ${tier.label} 投屏 · $reason"
    }
}

object QualityTiers {

    /**
     * 可选档位 = 分级封顶 ∩ 接收端上报的上限 ∩ 网络条件。
     * 三者在 PRD 里分属 7.2 / 8.4 / 8.7，协商时必须同时成立，否则会出现
     * 「A 级手机往 C 级盒子推 4K」这类承诺落不了地的组合。
     */
    fun negotiate(gradeCeiling: QualityTier, peerCeiling: QualityTier, band: NetworkBand): QualityTier {
        val networkLimit = if (band == NetworkBand.GHZ_2) QualityTier.HD30.rank else QualityTier.UHD60.rank
        val rank = maxOf(gradeCeiling.rank, maxOf(peerCeiling.rank, networkLimit))
        return QualityTier.Ordered.firstOrNull { it.rank == rank } ?: QualityTier.OFF
    }

    /** 硬解不支持该档位时向下取到第一个能解的档；一个都不支持返回 OFF。 */
    fun fitDecoder(tier: QualityTier, decodeWidth: Int, decodeHeight: Int, hardwareAvailable: Boolean): QualityTier {
        if (!hardwareAvailable) return QualityTier.SD480
        return QualityTier.Ordered.firstOrNull { it.rank >= tier.rank && it.width <= decodeWidth && it.height <= decodeHeight }
            ?: QualityTier.OFF
    }

    /** 低一档（PRD FS-10 恢复画质「上次档位低一档」/ 7.3 能力不匹配「降档」） */
    fun downgrade(tier: QualityTier): QualityTier {
        val next = tier.rank + 1
        return QualityTier.Ordered.firstOrNull { it.rank == next } ?: QualityTier.SD480
    }

    /**
     * 实际帧率 = 档位帧率 ∩ 对端上报的帧率上限。
     * 接收端按延迟降档时先砍帧率（PRD REQ-077），此时分辨率档位不变，只有这个数会变。
     */
    fun effectiveFps(tier: QualityTier, peerFpsCeiling: Int): Int =
        if (peerFpsCeiling <= 0 || !tier.isUsable) tier.fps else minOf(tier.fps, peerFpsCeiling)

    /** 回升一档，用于恢复后 10 秒内逐步回到原档位；已经高于上限时直接夹回上限 */
    fun upgrade(tier: QualityTier, ceiling: QualityTier): QualityTier {
        val target = tier.rank - 1
        return if (target <= ceiling.rank) {
            ceiling
        } else {
            QualityTier.Ordered.firstOrNull { it.rank == target } ?: ceiling
        }
    }

    /**
     * 把源画面（如手机屏幕）等比缩进档位框内。竖屏镜像保持竖屏方向，
     * 只按长边限制缩放，避免把 1080x2400 原样推给编码器。
     */
    fun profile(tier: QualityTier, sourceWidth: Int, sourceHeight: Int): EncodeProfile {
        require(sourceWidth > 0 && sourceHeight > 0) { "source size must be positive" }
        if (!tier.isUsable) return EncodeProfile(tier, 0, 0, 0, 0)
        val sourceLong = maxOf(sourceWidth, sourceHeight)
        val tierLong = maxOf(tier.width, tier.height)
        val scale = if (sourceLong <= tierLong) 1.0 else tierLong.toDouble() / sourceLong
        val width = evenOf(sourceWidth * scale)
        val height = evenOf(sourceHeight * scale)
        val bitrate = midBitRate(tier, width, height)
        return EncodeProfile(tier, width, height, tier.fps, bitrate)
    }

    /** 码率随实际像素数在档位区间内插值，小分辨率不必按顶格码率跑（省电也省带宽） */
    private fun midBitRate(tier: QualityTier, width: Int, height: Int): Int {
        val pixels = (width.toLong() * height).toDouble()
        val tierPixels = (tier.width.toLong() * tier.height).toDouble()
        val ratio = if (tierPixels <= 0) 1.0 else (pixels / tierPixels).coerceIn(0.5, 1.0)
        val span = tier.maxBitRateKbps - tier.minBitRateKbps
        return (tier.minBitRateKbps + (span * ratio)).toInt()
    }

    private fun evenOf(value: Double): Int {
        val rounded = value.toInt().coerceAtLeast(2)
        return if (rounded % 2 == 0) rounded else rounded - 1
    }

    /**
     * 取两个上限里更低的一档（rank 越大画质越低）。
     * 四道门、分级、硬解、手动锁定都是「上限」，合并时只能取最低，
     * 取高了的后果是承诺一个自己放不出来的档位。
     */
    fun lower(a: QualityTier, b: QualityTier): QualityTier = if (b.rank > a.rank) b else a

    /** 手动锁定的档位永远不能超过本机能力；null 表示「自动」，不设限。 */
    fun lockWithin(ceiling: QualityTier, locked: QualityTier?): QualityTier =
        if (locked == null || !locked.isUsable) ceiling else lower(ceiling, locked)

    /**
     * 实测带宽能撑到哪一档（PRD FS-05「可用带宽低于当前档位下限」）。
     * [kbps] 为 0 表示还没量到，此时不设限——量不到不等于带宽为零。
     * 单位用 kbps 而不是 Mbps：档位下限本身就是 kbps，换算成 Mbps 取整会把 19_500 读成 19
     * （刚好卡在 4K 门槛下），更会把静止画面的 500 读成 0（被当成「还没量到」）。
     */
    fun tierWithinBandwidth(kbps: Int): QualityTier {
        if (kbps <= 0) return QualityTier.UHD60
        return QualityTier.Ordered.firstOrNull { kbps >= it.minBitRateKbps } ?: QualityTier.SD480
    }

    /**
     * 设置页可列的档位：上限以上的一档连行都不生成
     * （PRD FS-05 验收「不可达隐藏 | 老设备界面中不存在 4K 选项」），
     * 上限以下仍然要列——用户锁档时可以主动选更低的一档换稳定。
     * rank 越大画质越低，所以「可达」是 rank ≥ 上限；上限为「不支持」时自然一档都不列。
     */
    fun reachable(ceiling: QualityTier): List<QualityTier> =
        QualityTier.Ordered.filter { it.rank >= ceiling.rank }

    /**
     * 接收端能放的最大档 = 分级封顶 ∩ 本机硬解能力。
     * 分级表按 PRD 写的门槛封顶，硬解探测可能更低（比如 B 级系统只解到 720p），取两者中更低的一档，
     * 这样上报给发送端的上限不会承诺一个自己放不出来的分辨率。
     */
    fun receiverCeiling(policy: GradePolicy, facts: DeviceFacts): QualityTier {
        if (policy.grade == Grade.UNSUPPORTED) return QualityTier.OFF
        val decoderFit = fitDecoder(policy.maxTier, facts.hardwareDecodeWidth, facts.hardwareDecodeHeight, facts.hardwareDecodeAvailable)
        return if (decoderFit.rank > policy.maxTier.rank) decoderFit else policy.maxTier
    }

    /**
     * 对端上报的上限（长边）落在哪个档：取第一个长边不超过它的档位，
     * 全部超限时落到保底档 480p，而不是原样硬塞。
     */
    fun tierWithin(width: Int, height: Int): QualityTier {
        val longEdge = maxOf(width, height)
        if (longEdge <= 0) return QualityTier.OFF
        return QualityTier.Ordered.firstOrNull { maxOf(it.width, it.height) <= longEdge } ?: QualityTier.SD480
    }

    /**
     * 说明协商结果是被哪一方压下来的。三者取最低，命中谁就报谁；
     * 都没被压到 4K 以下时只报档位，不编造理由。
     */
    fun note(negotiated: QualityTier, ownCeiling: QualityTier, peerCeiling: QualityTier?, band: NetworkBand): QualityNote {
        if (!negotiated.isUsable) return QualityNote(negotiated, "")
        val bandLimit = if (band == NetworkBand.GHZ_2) QualityTier.HD30 else QualityTier.UHD60
        val peerRank = peerCeiling?.rank ?: QualityTier.UHD60.rank
        val rank = maxOf(ownCeiling.rank, maxOf(peerRank, bandLimit.rank))
        if (rank <= QualityTier.UHD60.rank) return QualityNote(negotiated, "")
        val reason = when {
            band == NetworkBand.GHZ_2 && bandLimit.rank == rank -> "2.4GHz Wi-Fi 上限"
            peerCeiling != null && peerRank == rank && ownCeiling.rank < rank -> "对方上限"
            peerCeiling != null && peerRank == rank -> "双方上限"
            ownCeiling.rank == rank -> "本机上限"
            else -> ""
        }
        return QualityNote(negotiated, reason)
    }
}

package io.github.wangnan05563.airmirror.perf

import io.github.wangnan05563.airmirror.device.QualityTier
import io.github.wangnan05563.airmirror.device.QualityTiers

/**
 * 一个统计窗口的延迟结论（由 [FrameLatency.snapshot] 产出）。
 *
 * [overWindow] 与 [clockSkew] 必须一起带上：分位数是按桶取的，
 * 落在窗口外的样本全压在同一格子里，只看 p90 会把「延迟爆表」读成刚好卡在边界。
 */
data class LatencyWindow(
    val frames: Long,
    val p50Ms: Long,
    val p90Ms: Long,
    val averageMs: Long,
    val maxMs: Long,
    val overWindow: Long = 0L,
    val clockSkew: Long = 0L,
) {
    /** 打点本身不可信（负样本占多数）时不能据此降档 */
    val isTrustworthy: Boolean get() = frames > 0 && clockSkew <= frames / 2

    fun text(): String = "n=$frames p50=${p50Ms}ms p90=${p90Ms}ms max=${maxMs}ms"
}

/**
 * 延迟降档序列（PRD REQ-077「不达标时先降帧率再降分辨率，不出现黑屏」）。
 *
 * 帧率先减半直到 [minFpsFloor]，帧率到底后才动分辨率，一路到 480p 为止；
 * 序列按「从好到差」排列，第 0 位就是基线。回升沿同一序列倒着走，
 * 这样降下去几档就只可能回到几档，不会越过协商好的画质上限。
 *
 * 注：REQ-136 写的是「超出优先牺牲分辨率」，与 REQ-077 的杠杆顺序相反；
 * 本实现按更具体的 FS-06 验收行 REQ-077 走，分歧已回写待裁决。
 */
class LatencyLadder(base: QualityTier, private val fpsFloor: Int = MIN_FPS_FLOOR) {

    /** 一档编码参数：档位决定分辨率上限与码率，[fps] 可以低于档位帧率 */
    data class Step(val tier: QualityTier, val fps: Int) {
        fun text(): String = "${tier.label}@${fps}"
    }

    val steps: List<Step> = buildSteps(base)

    /** 索引超出序列时按 [step] 的夹取规则收敛 */
    fun step(index: Int): Step = steps[index.coerceIn(0, steps.size - 1)]

    /**
     * 序列里第一个「不高于 [tier]」的一步，用于带宽骤降时一步跳过去（PRD FS-05「直接跳到可维持档位」）。
     * 回 -1 表示当前画质已经不比 [tier] 好，没什么可跳的。
     */
    fun indexOfTierAtOrBelow(tier: QualityTier): Int = steps.indexOfFirst { it.tier.rank >= tier.rank }

    /**
     * 序列里与 ([tier], [fps]) 最贴近的一档，用于阶梯重建后接着往下走：
     * 当前画质比整条新序列还好，说明这次重建本身就是降档，落在新序列的基线；
     * 比整条序列都差，说明已经在新序列的底部，落在最后。
     */
    fun indexNearest(tier: QualityTier, fps: Int): Int {
        val exact = steps.indexOfFirst { it.tier == tier && it.fps == fps }
        if (exact >= 0) return exact
        val sameTier = steps.indexOfLast { it.tier == tier }
        if (sameTier >= 0) return sameTier
        return if (tier.rank < steps.first().tier.rank) 0 else steps.size - 1
    }

    private fun buildSteps(base: QualityTier): List<Step> {
        if (!base.isUsable) return listOf(Step(base, 0))
        val list = ArrayList<Step>(6)
        var tier = base
        var fps = base.fps
        list.add(Step(tier, fps))
        while (true) {
            if (fps > fpsFloor) {
                fps = (fps / 2).coerceAtLeast(fpsFloor)
            } else {
                val lower = QualityTiers.downgrade(tier)
                if (lower == tier || !lower.isUsable) return list
                tier = lower
                fps = minOf(tier.fps, fps)
            }
            val next = Step(tier, fps)
            // 档位与帧率同时到边界时可能算出重复的一档，重复即到底
            if (next == list.last()) return list
            list.add(next)
        }
    }

    companion object {
        /** 镜像投屏低于 15fps 已经没有跟手意义，作为帧率下限 */
        const val MIN_FPS_FLOOR = 15
    }
}

/** 策略对当前窗口表态：保持不变、降一档、回升一档，或「已锁定、只提示」。 */
enum class LatencyMove { KEEP, DEGRADE, RECOVER, NOTIFY }

data class LatencyDecision(val move: LatencyMove, val stepIndex: Int, val reason: String)

/**
 * 端到端延迟达标的守护策略：输入窗口统计，输出该不该动画质。
 *
 * 三条不可省：
 * ① 迟滞——单个窗口超标就动画质，会让画面在档位间来回抖；
 * ② 动作后静默——换档要重建编码器并等 IDR，其间帧数少、延迟也没稳定下来；
 * ③ 到底就停——最低档之后继续「降」只会把流关掉，PRD 明确要不出现黑屏。
 */
class LatencyPolicy(
    private val targetP90Ms: Long = DEFAULT_TARGET_P90_MS,
    private val minFrames: Long = DEFAULT_MIN_FRAMES,
    private val badWindowsNeeded: Int = DEFAULT_BAD_WINDOWS,
    private val goodWindowsNeeded: Int = DEFAULT_GOOD_WINDOWS,
    private val settleWindows: Int = DEFAULT_SETTLE_WINDOWS,
) {
    private var badStreak = 0
    private var goodStreak = 0
    private var settling = 0

    /** 锁定后「放不稳」提示的静默窗数，避免 3 秒浮层被同一句话反复顶掉 */
    private var notifyQuiet = 0

    /** 当前处在降档序列的第几档（0 = 基线） */
    var stepIndex = 0
        private set

    /**
     * 用户锁定画质（PRD FS-05「手动锁定 | 锁定后不再自动升降，仅在严重卡顿时提示」）。
     * 锁定时策略照旧观察，但表态改成 NOTIFY：画质一格不动，只把「放不稳」说给用户。
     */
    @Volatile
    var locked: Boolean = false

    /** 会话重新开始：画质回到基线，观察计数全清 */
    fun reset() = anchorAt(0)

    /**
     * 阶梯重建后把当前档对齐到真实画质（接收端自己要求过降分辨率，新序列从那一档起步）。
     * 只夹住上界的话，「降到 720p」会让新阶梯立刻以为自己已经到底，480p 永远试不到；
     * 反过来当成基线，又会把刚降下去的画质误判成已恢复。
     */
    fun anchorAt(index: Int) {
        badStreak = 0
        goodStreak = 0
        settling = 0
        notifyQuiet = 0
        stepIndex = index.coerceAtLeast(0)
    }

    /**
     * [ladderLength] 是本次判定的降档序列长度（[LatencyLadder.steps]）。
     * 必须由调用方每次带上：阶梯在本机会话中会变短（硬解降档、对端改推低分辨率），
     * 漏带一次就会「以为只有一档」而永远不降级——这类静默失效比报错难查。
     */
    fun onWindow(window: LatencyWindow, ladderLength: Int): LatencyDecision {
        val steps = ladderLength.coerceAtLeast(1)
        if (stepIndex >= steps) stepIndex = steps - 1
        if (settling > 0) {
            settling--
            return keep("换档后观察 ${settling + 1}/${settleWindows}")
        }
        if (!window.isTrustworthy) return keep("打点样本不足，不动画质")
        if (window.frames < minFrames) return keep("窗口只有 ${window.frames} 帧")
        if (isOver(window)) {
            goodStreak = 0
            badStreak++
            if (badStreak < badWindowsNeeded) return keep("超标 ${badStreak}/${badWindowsNeeded} 窗")
            badStreak = 0
            if (locked) {
                // 锁定 = 用户表了态，画质一格都不能动；但「放不稳」得说，只说不降（PRD FS-05）
                if (notifyQuiet > 0) {
                    notifyQuiet--
                    return keep("锁定中，提示静默剩 $notifyQuiet 窗")
                }
                notifyQuiet = NOTIFY_QUIET_WINDOWS
                return LatencyDecision(LatencyMove.NOTIFY, stepIndex, "档位已锁定，p90 ${window.p90Ms}ms 超 ${targetP90Ms}ms")
            }
            if (stepIndex >= steps - 1) return keep("已到最低档（第 $stepIndex 档），保画面不黑屏")
            stepIndex++
            settling = settleWindows
            return LatencyDecision(LatencyMove.DEGRADE, stepIndex, "p90 ${window.p90Ms}ms 超 ${targetP90Ms}ms")
        }
        badStreak = 0
        notifyQuiet = 0
        goodStreak++
        if (goodStreak < goodWindowsNeeded) return keep(if (stepIndex > 0) "达标 ${goodStreak}/${goodWindowsNeeded} 窗，等回升" else "达标")
        if (stepIndex == 0) {
            goodStreak = 0
            return keep("达标")
        }
        if (locked) return keep("档位已锁定，不自动回升")
        stepIndex--
        goodStreak = 0
        settling = settleWindows
        return LatencyDecision(LatencyMove.RECOVER, stepIndex, "连续 ${goodWindowsNeeded + 1} 窗达标")
    }

    private fun isOver(window: LatencyWindow): Boolean =
        window.p90Ms > targetP90Ms || window.overWindow * 2 > window.frames

    private fun keep(reason: String) = LatencyDecision(LatencyMove.KEEP, stepIndex, reason)

    companion object {
        /** PRD REQ-003 / REQ-333 / REQ-380：1080p60 局域网同 Wi-Fi 下 P90 ≤150ms */
        const val DEFAULT_TARGET_P90_MS = 150L

        const val DEFAULT_MIN_FRAMES = 20L
        const val DEFAULT_BAD_WINDOWS = 2
        const val DEFAULT_GOOD_WINDOWS = 12
        const val DEFAULT_SETTLE_WINDOWS = 4

        /** 一次 30 帧窗口约 0.5～1 秒，静默 10 窗≈10 秒才再说一次「放不稳」 */
        const val NOTIFY_QUIET_WINDOWS = 10
    }
}

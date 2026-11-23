package io.github.wangnan05563.airmirror.ui

import io.github.wangnan05563.airmirror.net.DeviceRegistry
import io.github.wangnan05563.airmirror.net.DiscoveryLadder

/**
 * T06105 两步投屏的纯判定层：入口优先级、自动选中与决策计数都不依赖 android.*。
 *
 * 入口优先级：唯一在线且上次成功投屏的设备 > 同网在线设备列表 > 扫码 > 投屏码。
 * 自动选中必须同时满足：
 * 1. 发现窗口已稳定（[AUTO_SETTLE_MS]），避免第二台电视晚到时静默选错；
 * 2. 恰好一台在线且协议可用；
 * 3. 这台设备命中本机「上次成功投屏」记录，且记录未超过 30 天；
 * 4. 用户还没主动改动入口、这次没有自动尝试过、没有正在连接或投屏。
 *
 * 决策预算只数“用户主动选择”：扫码入口 1 次、手动切投屏码 1 次、展开设备 1 次、
 * 选镜像 1 次；输入投屏码与提交属于执行，不重复计数。自动选中为 0 次。
 */
object TwoStepCastPolicy {

    /** 发现稳定窗：完整走完三轮探测（1.5s/轮）再判定，避免第一台到了就抢先连、第二台晚到被静默忽略。 */
    const val AUTO_SETTLE_MS = DiscoveryLadder.DEFAULT_ROUND * DiscoveryLadder.DEFAULT_ROUNDS
    const val MAX_DECISIONS = 2

    enum class EntryMode { SCAN, CODE }

    data class Candidate(
        val fingerprint: String,
        val name: String,
        val online: Boolean,
        val supported: Boolean,
    )

    data class Trusted(
        val fingerprint: String,
        val usedAt: Long,
    )

    data class Input(
        val rows: List<Candidate>,
        val trusted: Trusted?,
        val now: Long,
        val discoverySettled: Boolean,
        val userChoseEntry: Boolean,
        val autoAttempted: Boolean,
        val connecting: Boolean,
        val casting: Boolean,
        val trustedTtl: Long = 30L * 24 * 60 * 60 * 1000,
    )

    fun defaultMode(cameraAvailable: Boolean): EntryMode =
        if (cameraAvailable) EntryMode.SCAN else EntryMode.CODE

    /** 返回唯一允许自动连接的设备；任何一条不满足都返回 null，宁可让用户点一下也不投错屏。 */
    fun autoTarget(input: Input): Candidate? {
        if (!input.discoverySettled || input.userChoseEntry || input.autoAttempted) return null
        if (input.connecting || input.casting) return null
        val trusted = input.trusted ?: return null
        if (trusted.fingerprint.isBlank() || trusted.usedAt <= 0L) return null
        val expired = if (input.now < trusted.usedAt) false else input.now - trusted.usedAt >= input.trustedTtl
        if (expired) return null
        val online = input.rows.filter { it.online }
        if (online.size != 1) return null
        val only = online.single()
        if (!only.supported) return null
        return only.takeIf { it.fingerprint == trusted.fingerprint }
    }

    /**
     * 扫码入口是否露出：投屏码模式保持投屏码；
     * 扫码模式下只要有一台在线可用设备，就让设备列表优先，扫码行让位。
     */
    fun scanEntryVisible(rows: List<DeviceRegistry.Row>, mode: EntryMode): Boolean {
        if (mode == EntryMode.CODE) return false
        return rows.none { it.online && it.hasSupportedProtocol() }
    }

    private fun DeviceRegistry.Row.hasSupportedProtocol(): Boolean =
        io.github.wangnan05563.airmirror.protocol.ProtocolBoundary.bestSupported(protocols) != null

    /** 决策预算：超过上限返回 false，界面据此给“已用 2 步”的明确出口而不是继续叠动作。 */
    class Budget(private val max: Int = MAX_DECISIONS) {
        var used: Int = 0
            private set

        val remaining: Int get() = (max - used).coerceAtLeast(0)

        fun take(): Boolean {
            if (used >= max) return false
            used++
            return true
        }

        fun reset() {
            used = 0
        }
    }
}

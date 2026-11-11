package io.github.wangnan05563.airmirror.ui

import io.github.wangnan05563.airmirror.error.ErrorNotice
import io.github.wangnan05563.airmirror.net.DeviceRegistry
import io.github.wangnan05563.airmirror.net.DiscoveryLadder

/**
 * UX-01 空列表自救的判决映射（设计：AirMirror-空列表诊断映射设计-20261109.md）。
 *
 * 纯逻辑、不依赖 android.*：输入「发现判决 + 注册表空因」，输出唯一 [ErrorNotice.Cause] 或 null。
 * 文案、下一步、动作集合全部由 [ErrorNotice.noticeFor] 提供，界面不再拼字符串。
 * 「权限缺失」不是发现判决（运行期权限只有通知/录音，均不影响发现），故不在映射里。
 */
object EmptyDiagnosis {

    /** 诊断态固定三动作，顺序固定（设计 §3.3）：重试发现 / 用投屏码连接 / 手动输 IP。 */
    val ACTIONS: List<ErrorNotice.Action> = listOf(
        ErrorNotice.Action.RETRY_DISCOVERY,
        ErrorNotice.Action.ENTER_CODE,
        ErrorNotice.Action.ENTER_IP,
    )

    /**
     * 搜索未结束、已命中、列表有在线设备时返回 null：界面不诊断、不打扰。
     *
     * 优先级（设计 §3.2）：搜索未结束不下结论 → 已知设备全离线 → 无任何命中按网段分 AP/双频。
     * 组播禁用但广播/直连命中（[DiscoveryLadder.Verdict.Found] downgraded=true）全程静默。
     */
    fun of(verdict: DiscoveryLadder.Verdict, registry: DeviceRegistry.EmptyReason): ErrorNotice.Cause? {
        if (verdict is DiscoveryLadder.Verdict.Found) return null
        val nothing = verdict as? DiscoveryLadder.Verdict.Nothing ?: return null
        if (nothing.reason == DiscoveryLadder.Empty.SEARCHING) return null
        if (registry == DeviceRegistry.EmptyReason.HAS_ONLINE) return null
        if (registry == DeviceRegistry.EmptyReason.ALL_OFFLINE) return ErrorNotice.Cause.DEVICE_OFFLINE
        return when (nothing.reason) {
            DiscoveryLadder.Empty.AP_ISOLATED -> ErrorNotice.Cause.DISCOVERY_AP_ISOLATED
            DiscoveryLadder.Empty.DUAL_BAND -> ErrorNotice.Cause.DISCOVERY_DUAL_BAND
            DiscoveryLadder.Empty.SEARCHING -> null
        }
    }
}
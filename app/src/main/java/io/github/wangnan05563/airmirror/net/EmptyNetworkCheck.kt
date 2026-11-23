package io.github.wangnan05563.airmirror.net

import io.github.wangnan05563.airmirror.error.ErrorNotice

/**
 * T06106 / PRD FR-2.0-01：空态四类状态的纯判定逻辑。
 *
 * 只做“现在该给用户什么原因”的判定，不碰 Android 绘制，也不读 SSID：
 * 1. 未联网（没有可用 Wi-Fi/有线/热点链路）；
 * 2. 同网但 15 秒内没有发现接收端；
 * 3. 已发现接收端但最近一次配对失败；
 * 4. 网络策略/平台限制（AP 隔离、双频分离、VPN/企业策略）。
 *
 * 诊断数据只保留网络类型、本机 IPv4、最近扫描时间、已发现数量；不读取 SSID，
 * 因此不需要位置权限。运行时通知/录音权限也不阻断发现，不能把它们误判成“权限导致搜不到”。
 */
object EmptyNetworkCheck {

    /** PRD FR-2.0-01：15 秒没有任何结果才进入“未发现”态。 */
    const val NO_RESULT_TIMEOUT_MS = 15_000L

    /** PRD FR-2.0-01：无结果期间每 5 秒允许自动重试一次。 */
    const val AUTO_RETRY_MS = 5_000L

    enum class Link {
        NONE,
        WIFI,
        ETHERNET,
        MOBILE,
        OTHER,
    }

    enum class State {
        /** 15 秒窗口内还没结论，界面应继续显示搜索。 */
        SEARCHING,
        /** 没有可用本地网络链路。 */
        NO_NETWORK,
        /** 有网络，但 15 秒内没有发现接收端（含已知设备全离线）。 */
        NO_RECEIVER,
        /** 已发现接收端，最近一次配对失败。 */
        PAIRING_FAILED,
        /** AP 隔离、双频分离或平台/企业网络策略限制。 */
        RESTRICTED,
        /** 列表已有在线接收端，不需要空态诊断。 */
        READY,
    }

    /**
     * 调用方（页面/服务）提供的诊断快照。
     *
     * [lastScanAt] 用与 [check] 相同的单调时钟；[foundReceivers] 是本次扫描发现的接收端数量；
     * [platformRestricted] 只能由明确知道平台策略的调用方置 true，不能拿通知/录音权限来填。
     */
    data class Snapshot(
        val link: Link = Link.NONE,
        val localIp: String? = null,
        val foundReceivers: Int = 0,
        val lastScanAt: Long? = null,
        val platformRestricted: Boolean = false,
    ) {
        init {
            require(foundReceivers >= 0) { "foundReceivers 不能为负" }
        }
    }

    data class Diagnostic(
        val link: Link,
        val localIp: String?,
        val lastScanAt: Long?,
        val foundReceivers: Int,
        val elapsedMs: Long,
    )

    data class Result(
        val state: State,
        val cause: ErrorNotice.Cause?,
        val actions: List<ErrorNotice.Action>,
        val diagnostic: Diagnostic,
        val code: String,
    )

    /**
     * 按 PRD 的优先级判定；[startedAt] 与 [now] 必须使用同一时钟。
     * 已存在在线接收端或发现判决为 Found 时返回 [State.READY]，页面无需空态。
     */
    fun check(
        now: Long,
        startedAt: Long,
        snapshot: Snapshot,
        verdict: DiscoveryLadder.Verdict? = null,
        registry: DeviceRegistry.EmptyReason = DeviceRegistry.EmptyReason.NONE_FOUND,
        pairingFailed: Boolean = false,
    ): Result {
        val elapsed = (now - startedAt).coerceAtLeast(0L)
        val diagnostic = Diagnostic(
            link = snapshot.link,
            localIp = snapshot.localIp,
            lastScanAt = snapshot.lastScanAt,
            foundReceivers = snapshot.foundReceivers,
            elapsedMs = elapsed,
        )

        if (snapshot.link == Link.NONE) {
            return result(State.NO_NETWORK, ErrorNotice.Cause.NO_NETWORK, diagnostic, "AM-NET-04")
        }

        when ((verdict as? DiscoveryLadder.Verdict.Nothing)?.reason) {
            DiscoveryLadder.Empty.AP_ISOLATED ->
                return result(State.RESTRICTED, ErrorNotice.Cause.DISCOVERY_AP_ISOLATED, diagnostic, "AM-NET-01")
            DiscoveryLadder.Empty.DUAL_BAND ->
                return result(State.RESTRICTED, ErrorNotice.Cause.DISCOVERY_DUAL_BAND, diagnostic, "AM-NET-02")
            else -> Unit
        }

        if (snapshot.platformRestricted) {
            return result(State.RESTRICTED, ErrorNotice.Cause.PERMISSION_NETWORK, diagnostic, "AM-PERM-01")
        }

        if (snapshot.foundReceivers > 0 && pairingFailed) {
            return result(State.PAIRING_FAILED, ErrorNotice.Cause.PAIRING_FAILED, diagnostic, "AM-PAIR-01")
        }

        if (registry == DeviceRegistry.EmptyReason.HAS_ONLINE || verdict is DiscoveryLadder.Verdict.Found) {
            return result(State.READY, null, diagnostic, "")
        }

        if (elapsed < NO_RESULT_TIMEOUT_MS) {
            return result(State.SEARCHING, null, diagnostic, "AM-ND-00")
        }

        if (registry == DeviceRegistry.EmptyReason.ALL_OFFLINE) {
            return result(State.NO_RECEIVER, ErrorNotice.Cause.DEVICE_OFFLINE, diagnostic, "AM-OFF-01")
        }
        return result(State.NO_RECEIVER, ErrorNotice.Cause.NO_RECEIVER, diagnostic, "AM-ND-02")
    }

    /**
     * 自动重试节奏：首次扫描或距上次扫描满 5 秒才允许再扫；页面可以随时手动停止。
     */
    fun retryDue(now: Long, lastScanAt: Long?): Boolean =
        lastScanAt == null || now < lastScanAt || now - lastScanAt >= AUTO_RETRY_MS

    private fun result(
        state: State,
        cause: ErrorNotice.Cause?,
        diagnostic: Diagnostic,
        code: String,
    ): Result = Result(
        state = state,
        cause = cause,
        actions = cause?.let { ErrorNotice.noticeFor(it).actions } ?: emptyList(),
        diagnostic = diagnostic,
        code = code,
    )
}

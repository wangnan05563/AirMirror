package io.github.wangnan05563.airmirror.net

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * T06106：把 Android 的链路状态收敛成 [EmptyNetworkCheck.Snapshot]，再交给纯判定。
 *
 * 只使用 `ConnectivityManager.activeNetworkInfo` 与 `NetworkInterface` 枚举本机 IPv4：
 * 不读 Wi-Fi SSID、不申请位置权限，也不把通知/录音权限当成“发现权限”。
 * 页面只消费返回的 [EmptyNetworkCheck.Result] 与 [EmptyNetworkCheck.Diagnostic]，不在这里画界面。
 */
class NetworkDiagnostics(
    private val context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    /**
     * 读取当前诊断快照。[lastScanAt]、[foundReceivers] 由页面/扫描器提供，
     * [platformRestricted] 仅在明确知道 VPN/企业策略限制时置 true。
     */
    fun snapshot(
        lastScanAt: Long?,
        foundReceivers: Int,
        platformRestricted: Boolean = false,
    ): EmptyNetworkCheck.Snapshot = EmptyNetworkCheck.Snapshot(
        link = linkOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager),
        localIp = localIpv4(),
        foundReceivers = foundReceivers.coerceAtLeast(0),
        lastScanAt = lastScanAt,
        platformRestricted = platformRestricted,
    )

    /** 读取快照并完成一次 15 秒窗口/配对失败/策略限制判定。 */
    fun check(
        startedAt: Long,
        snapshot: EmptyNetworkCheck.Snapshot,
        verdict: DiscoveryLadder.Verdict? = null,
        registry: DeviceRegistry.EmptyReason = DeviceRegistry.EmptyReason.NONE_FOUND,
        pairingFailed: Boolean = false,
    ): EmptyNetworkCheck.Result = EmptyNetworkCheck.check(
        now = clock(),
        startedAt = startedAt,
        snapshot = snapshot,
        verdict = verdict,
        registry = registry,
        pairingFailed = pairingFailed,
    )

    @Suppress("DEPRECATION")
    private fun linkOf(manager: ConnectivityManager?): EmptyNetworkCheck.Link {
        val info = manager?.activeNetworkInfo
        if (info == null || !info.isConnected) return EmptyNetworkCheck.Link.NONE
        return when (info.type) {
            ConnectivityManager.TYPE_WIFI -> EmptyNetworkCheck.Link.WIFI
            ConnectivityManager.TYPE_ETHERNET -> EmptyNetworkCheck.Link.ETHERNET
            ConnectivityManager.TYPE_MOBILE -> EmptyNetworkCheck.Link.MOBILE
            else -> EmptyNetworkCheck.Link.OTHER
        }
    }

    companion object {
        /**
         * 枚举非回环、非 link-local 的 IPv4。没有可用地址时返回 null，界面如实显示“未知”，
         * 不为了展示去读 SSID/BSSID。
         */
        fun localIpv4(): String? {
            val interfaces = try {
                NetworkInterface.getNetworkInterfaces()
            } catch (_: SocketException) {
                return null
            } ?: return null
            for (network in interfaces) {
                val usable = try {
                    network.isUp && !network.isLoopback
                } catch (_: SocketException) {
                    false
                }
                if (!usable) continue
                for (address in network.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        return address.hostAddress
                    }
                }
            }
            return null
        }
    }
}

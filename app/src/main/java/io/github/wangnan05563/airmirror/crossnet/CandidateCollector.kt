package io.github.wangnan05563.airmirror.crossnet

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 候选地址采集（PRD FS-04 第 4 步「交换网络候选并尝试打洞」）。
 *
 * 关键约束：候选端口必须是**将要用于打洞的那个 UDP socket 的本地端口**。
 * 用一个临时 socket 查 STUN、再用另一个 socket 打洞，会得到两份不同的 NAT 映射，
 * 对端打过来永远到不了隧道——这是跨网实现里最容易「看起来正常、实际连不上」的坑。
 *
 * HOST 候选是本机网卡 IPv4，同一局域网/热点时对端能直接打到；SRFLX 是 STUN 给出的公网映射，跨网只认它。
 * 刻意不采集 RELAY 候选：Q-4 已裁不自建中继，出现中继地址只会让状态机多一条永远走不到的路。
 */
object CandidateCollector {

    data class Config(
        val stunHost: String = "",
        val stunPort: Int = 3478,
        val stunTimeoutMs: Int = 2_000,
    )

    /**
     * 本机可对外宣告的 IPv4。过滤回环、169.254 链路本地、未启用接口与虚拟网卡：
     * 把这些地址发出去只会让打洞把 15 秒预算耗在必然失败的地址上。
     */
    fun localHosts(): List<String> {
        val addresses = LinkedHashSet<String>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        for (nic in interfaces) {
            val usable = runCatching { nic.isUp && !nic.isLoopback && !nic.isVirtual }.getOrDefault(false)
            if (!usable) continue
            for (address in nic.inetAddresses) {
                val ipv4 = address as? Inet4Address ?: continue
                if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress || ipv4.isAnyLocalAddress) continue
                addresses += ipv4.hostAddress ?: continue
            }
        }
        // 排序只为让单测与日志稳定，不代表优先级
        return addresses.sorted()
    }

    /**
     * 采集候选：所有 HOST（端口取 [socket] 的本地端口）+ 一条 SRFLX。
     * STUN 查询失败时只给 HOST——同网/热点仍可用，如实降级而不是整体失败。
     */
    fun collect(
        socket: DatagramSocket,
        config: Config = Config(),
        query: (DatagramSocket, String, Int, Int) -> StunCodec.MappedAddress? = { s, host, port, timeout ->
            StunClient.query(s, host, port, timeout)
        },
    ): List<CrossNetProtocol.Candidate> {
        val port = socket.localPort
        require(port in 1..65535) { "candidate socket is not bound: $port" }
        val candidates = ArrayList<CrossNetProtocol.Candidate>()
        localHosts().forEach { host ->
            candidates += CrossNetProtocol.Candidate(host, port, CrossNetProtocol.CandidateKind.HOST, HOST_PRIORITY)
        }
        if (config.stunHost.isNotBlank()) {
            val mapped = runCatching { query(socket, config.stunHost, config.stunPort, config.stunTimeoutMs) }.getOrNull()
            if (mapped != null && Subnet.isIpv4(mapped.address) && mapped.port in 1..65535) {
                // 无 NAT 的公网直连时映射可能就等于本机地址，已由 HOST 覆盖
                val duplicate = candidates.any { it.address == mapped.address && it.port == mapped.port }
                if (!duplicate) {
                    candidates += CrossNetProtocol.Candidate(
                        mapped.address,
                        mapped.port,
                        CrossNetProtocol.CandidateKind.SRFLX,
                        SRFLX_PRIORITY,
                    )
                }
            }
        }
        return candidates.take(CrossNetProtocol.MAX_CANDIDATES)
    }

    private const val HOST_PRIORITY = 0
    private const val SRFLX_PRIORITY = 1
}

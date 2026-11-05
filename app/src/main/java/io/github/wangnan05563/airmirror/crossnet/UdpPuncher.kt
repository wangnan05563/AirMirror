package io.github.wangnan05563.airmirror.crossnet

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * UDP 打洞执行器（PRD FS-04 第 4 步）。
 *
 * 两端都向对方的候选地址发包：只要有一侧的包到达，就说明 NAT 上已经开了洞，
 * 收到探针的一方回 ACK 并立即认为通道可用（对方能打到我们，我们也能按来源地址打回去）。
 * 单个 socket 上完成全部收发，避免「探测端口」和「媒体端口」被拆成两条不同的 NAT 映射。
 *
 * 必须在后台线程调用（阻塞式 socket）；调用方负责在结束后关闭 socket。
 * 不使用 41523 媒体端口：发现/打洞与媒体分端口，避免被 8.9 的防滥用闸门记成无效握手。
 */
class UdpPuncher(
    private val socket: DatagramSocket,
    private val sessionId: String,
    private val role: CrossNetProtocol.Role,
    private val credential: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Result(
        val connected: Boolean,
        val peer: String?,
        val probes: Int,
        val elapsedMs: Long,
    )

    fun punch(
        peers: List<CrossNetProtocol.Candidate>,
        timeoutMs: Long = 15_000,
        intervalMs: Long = 500,
    ): Result {
        require(peers.isNotEmpty()) { "no punch candidates" }
        require(timeoutMs > 0) { "punch timeout must be positive" }
        require(intervalMs > 0) { "punch interval must be positive" }
        val start = now()
        val deadline = start + timeoutMs
        val probe = PunchWire.encode(sessionId, role, false, credential)
        var lastRound: Long? = null
        var sent = 0

        while (now() < deadline) {
            val current = now()
            if (lastRound == null || current - lastRound >= intervalMs) {
                peers.forEach { candidate ->
                    val packet = runCatching {
                        DatagramPacket(
                            probe,
                            probe.size,
                            InetAddress.getByName(candidate.address),
                            candidate.port,
                        )
                    }.getOrNull() ?: return@forEach
                    runCatching { socket.send(packet) }.onSuccess { sent++ }
                }
                lastRound = current
            }

            val remaining = maxOf(1L, deadline - now())
            val wait = minOf(intervalMs, remaining)
            socket.soTimeout = wait.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            try {
                val buffer = ByteArray(PunchWire.PACKET_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val reply = PunchWire.decode(packet.data.copyOf(packet.length), credential) ?: continue
                if (reply.sessionId != sessionId || reply.role == role) continue
                if (!reply.ack) {
                    val ack = PunchWire.encode(sessionId, role, true, credential)
                    runCatching {
                        socket.send(DatagramPacket(ack, ack.size, packet.address, packet.port))
                    }
                }
                return Result(true, "${packet.address.hostAddress}:${packet.port}", sent, now() - start)
            } catch (e: SocketTimeoutException) {
                // 一个 interval 内没等到包，继续下一轮探测
            } catch (e: java.net.PortUnreachableException) {
                // 对方候选暂时不可达：ICMP 不可达不能被当成「打洞失败」，继续试下一轮
            }
        }
        return Result(false, null, sent, now() - start)
    }
}

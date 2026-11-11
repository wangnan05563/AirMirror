package io.github.wangnan05563.airmirror.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Arrays
import java.util.UUID
import kotlin.concurrent.thread

/** 探测传输层：抽出来是为了让节拍与判决能在 JVM 单测里用假传输驱动，不用真的开端口。 */
interface ProbeTransport {
    fun send(bytes: ByteArray, host: String, port: Int)
    fun receive(timeoutMs: Int): ProbeDatagram?
    fun close()
}

data class ProbeDatagram(val bytes: ByteArray, val host: String, val port: Int)

/** 生产用传输：只绑 41525 收应答/公告，发出的探测一律走 41524，不碰 41523 媒体端口。 */
class UdpProbeTransport : ProbeTransport {
    private var socket: DatagramSocket? = null

    private fun bound(): DatagramSocket = socket ?: DatagramSocket(ProbeProtocol.ANNOUNCE_PORT).apply {
        broadcast = true
        reuseAddress = true
    }.also { socket = it }

    override fun send(bytes: ByteArray, host: String, port: Int) {
        bound().send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
    }

    override fun receive(timeoutMs: Int): ProbeDatagram? {
        val s = bound()
        s.soTimeout = timeoutMs
        val buffer = ByteArray(ProbeProtocol.MAX_PACKET)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            s.receive(packet)
            ProbeDatagram(Arrays.copyOf(packet.data, packet.length), packet.address?.hostAddress.orEmpty(), packet.port)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}

/**
 * 发送端的降级发现扫描器（PRD FR-02 / FS-07 异常「组播被禁用：自动降级为受控广播/直连探测」）。
 *
 * 三路并行：NSD 组播由 [noteMulticastHit] 从 ServiceBrowser 的 onFound 记一笔；
 * 广播与直连两路在这里按 [DiscoveryLadder] 的节拍同时补发。
 * 命中即停发；组播没命中而另外任一路命中 = 静默降级（界面不提示，列表照常显示设备）；
 * 三轮全空才按缓存网段判 AP 隔离或双频分离，给 PRD 文案。
 *
 * [poll] 是单步纯逻辑，[start]/[stop] 只是把它放到后台线程按拍跑，Android 主线程不做网络 IO。
 */
class ProbeScanner(
    private val transport: ProbeTransport,
    private val sink: Sink,
    private val ladder: DiscoveryLadder = DiscoveryLadder(),
    private val recent: RecentTargets = RecentTargets(),
    private val interfaces: () -> List<BroadcastTargets.Interface> = { emptyList() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val token: () -> String = { UUID.randomUUID().toString().take(8) },
    private val senderName: String = "AirMirror",
) {
    interface Sink {
        fun onDevice(fingerprint: String, name: String, host: String, port: Int, protocols: List<String>)
        fun onLost(fingerprint: String)
        fun onNotice(text: String)
        fun onChanged()
    }

    private val seenAt = LinkedHashMap<String, Long>()
    private var session = ""
    private var lastNotice = ""
    @Volatile private var lastVerdict: DiscoveryLadder.Verdict =
        DiscoveryLadder.Verdict.Nothing(DiscoveryLadder.Empty.SEARCHING, "")
    @Volatile private var running = false
    @Volatile private var failed = false
    private var worker: Thread? = null

    /** ServiceBrowser 命中组播：这是唯一能证明「组播没被禁」的一路。 */
    fun noteMulticastHit() {
        ladder.noteHit(DiscoveryLadder.Channel.MULTICAST)
        publishNotice("")
    }

    /** 连接成功（或用户主动发起了连接）：记进 10 分钟缓存，作为下次直连探测的地址来源。 */
    fun recordConnected(fingerprint: String, name: String, host: String, port: Int, now: Long = clock()) {
        recent.record(fingerprint, name, host, port, now)
        recent.prune(now)
    }

    fun snapshot(): String = recent.encode()

    /** 最近一次发现判决（只读暴露，不改判定逻辑）：界面据此做结构化空态诊断（UX-01）。 */
    fun verdict(): DiscoveryLadder.Verdict = lastVerdict

    /** 只起判决表、不开后台线程：JVM 单测用 [begin] + [poll] 就能把整条节拍走完。 */
    fun begin(now: Long = clock()) {
        ladder.start(now)
        failed = false
        lastVerdict = DiscoveryLadder.Verdict.Nothing(DiscoveryLadder.Empty.SEARCHING, "")
    }

    fun start(now: Long = clock()) {
        begin(now)
        running = true
        worker = thread(name = "airmirror-probe-scan") {
            while (running) {
                poll(clock())
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    fun stop() {
        running = false
        transport.close()
        runCatching { worker?.join(1_000) }
        worker = null
    }

    /** 一次节拍：先收应答 → 再决定补发 → 判定离线 → 判定空列表原因。先收后发，命中当轮就不再多打一轮探测。 */
    fun poll(now: Long) {
        if (failed) return
        repeat(DRAIN_PER_POLL) {
            val datagram = try {
                transport.receive(RECEIVE_TIMEOUT_MS)
            } catch (_: Exception) {
                failed = true
                return
            } ?: return@repeat
            handle(datagram, now)
        }
        if (ladder.probeDue(now)) emitProbes(now)
        expire(now)
        evaluate(now)
    }

    private fun emitProbes(now: Long) {
        session = token()
        for (host in BroadcastTargets.of(interfaces())) {
            sendProbe(host, CHANNEL_BROADCAST)
        }
        for (target in recent.viable(now)) {
            sendProbe(target.host, CHANNEL_DIRECT)
        }
        ladder.markProbed(now)
    }

    private fun sendProbe(host: String, channel: Char) {
        val bytes = ProbeProtocol.probe(channel + session, senderName)
        runCatching { transport.send(bytes, host, ProbeProtocol.PROBE_PORT) }
    }

    private fun handle(datagram: ProbeDatagram, now: Long) {
        val reply = ProbeProtocol.parse(datagram.bytes) as? ProbeProtocol.Packet.Reply ?: return
        if (reply.fingerprint.isBlank() || datagram.host.isBlank()) return
        val channel = when (reply.kind) {
            ProbeProtocol.KIND_ACK -> {
                // nonce 必须对本轮提问，且带出当时走的是哪一路；串台的旧应答一律丢
                if (session.isEmpty() || reply.nonce.length < 2 || !reply.nonce.endsWith(session)) return
                when (reply.nonce[0]) {
                    CHANNEL_BROADCAST -> DiscoveryLadder.Channel.BROADCAST
                    CHANNEL_DIRECT -> DiscoveryLadder.Channel.DIRECT
                    else -> return
                }
            }
            ProbeProtocol.KIND_HELLO -> DiscoveryLadder.Channel.BROADCAST
            else -> return
        }
        ladder.noteHit(channel)
        // 伪造应答能在 8 秒存活窗口内塞进任意多指纹（HELLO 连 nonce 都不需要）：
        // 表无上限时内存跟着攻击者的发包速率走，正常局域网的同屏设备远不到这个数。
        // 已跟踪的指纹仍可刷新，不受上限影响。
        if (reply.fingerprint !in seenAt && seenAt.size >= MAX_TRACKED) return
        seenAt[reply.fingerprint] = now
        // reply.port 只是登记给连接流程，扫描器不会主动连它（媒体端口被 8.9 的限流闸门看着）
        sink.onDevice(reply.fingerprint, reply.name, datagram.host, reply.port, reply.protocols)
        publishNotice("")
    }

    private fun expire(now: Long) {
        val stale = seenAt.filter { now - it.value >= LOST_AFTER_MS }.keys.toList()
        for (fingerprint in stale) {
            seenAt.remove(fingerprint)
            sink.onLost(fingerprint)
            sink.onChanged()
        }
    }

    private fun evaluate(now: Long) {
        val localPrefix = interfaces().firstOrNull()?.address?.substringBeforeLast('.')
        val verdict = ladder.verdict(now, recent.crossSubnet(now, localPrefix))
        lastVerdict = verdict
        publishNotice(if (verdict is DiscoveryLadder.Verdict.Nothing) verdict.notice else "")
    }

    private fun publishNotice(text: String) {
        if (text == lastNotice) return
        lastNotice = text
        sink.onNotice(text)
        sink.onChanged()
    }

    private companion object {
        const val POLL_MS = 200L
        const val RECEIVE_TIMEOUT_MS = 40
        const val DRAIN_PER_POLL = 8
        const val LOST_AFTER_MS = 8_000L
        const val CHANNEL_BROADCAST = 'b'
        const val CHANNEL_DIRECT = 'd'

        /** 同时跟踪的设备上限：与 DeviceRegistry 的 64 条对齐再留一倍余量 */
        const val MAX_TRACKED = 128
    }
}

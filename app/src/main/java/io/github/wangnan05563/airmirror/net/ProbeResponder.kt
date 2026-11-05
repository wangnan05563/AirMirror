package io.github.wangnan05563.airmirror.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.github.wangnan05563.airmirror.privacy.Redactor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Arrays
import kotlin.concurrent.thread

/**
 * 接收端的探测应答器（PRD FR-02 异常「组播被禁用：自动降级为受控广播/直连探测」的接收侧）。
 *
 * 两件事：谁探测就答谁，以及没人问也定期朝广播地址报一次自己——后者让「组播被掐死但广播还通」
 * 的网络里，发送端不用先发任何东西就能看见大屏。
 *
 * 探测走 41524/41525，绝不碰 41523 媒体端口：8.9 的防滥用闸门把「握手没走完就撤」也记成
 * 一笔被拒，几轮 TCP 探测会把机主自己关进 60 秒静默期。发现与数据分端口，互不记账。
 *
 * 端口被占就用不了，这时只退回 NSD/DLNA 那两路并留下一行日志：发现失败不该把投屏服务本身拖下水。
 */
class ProbeResponder(
    context: Context,
    private val fingerprint: String,
    private val wifi: WifiManager? = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager,
) {
    @Volatile private var name = ""
    @Volatile private var mediaPort = 0
    @Volatile private var protocols: List<String> = listOf(PROTOCOL_AMRC)
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** [mediaPort] 是接收端真正在听的 TCP 端口：固定端口被占用时它会变，公告必须跟着改。 */
    fun start(advertisedName: String, mediaPort: Int, protocols: List<String> = listOf(PROTOCOL_AMRC)) {
        if (running) return
        this.name = advertisedName
        this.mediaPort = mediaPort
        this.protocols = protocols
        val bound = runCatching {
            DatagramSocket(ProbeProtocol.PROBE_PORT).apply {
                soTimeout = SOCKET_TIMEOUT_MS
                broadcast = true
            }
        }.onFailure { Log.w(TAG, "probe responder idle: port unavailable (${Redactor.throwable(it)})") }
            .getOrNull() ?: return
        socket = bound
        multicastLock = runCatching {
            wifi?.createMulticastLock(LOCK_TAG)?.apply {
                setReferenceCounted(true)
                acquire()
            }
        }.getOrNull()
        running = true
        worker = thread(name = "airmirror-probe") { loop(bound) }
        Log.i(TAG, "probe responder up on port ${ProbeProtocol.PROBE_PORT}")
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        runCatching { worker?.join(1_000) }
        worker = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        Log.i(TAG, "probe responder down")
    }

    private fun loop(bound: DatagramSocket) {
        val buffer = ByteArray(ProbeProtocol.MAX_PACKET)
        val packet = DatagramPacket(buffer, buffer.size)
        var nextAnnounceAt = 0L
        while (running) {
            // receive() 会把 length 改成实际收包长度；不重置的话第二次只能收进上次那么长
            packet.length = buffer.size
            val now = System.currentTimeMillis()
            if (now >= nextAnnounceAt) {
                nextAnnounceAt = now + ANNOUNCE_INTERVAL_MS
                announce(bound)
            }
            val arrived = runCatching { bound.receive(packet) }
                .onFailure { if (running && it !is java.net.SocketTimeoutException) Log.w(TAG, "probe receive failed: ${Redactor.throwable(it)}") }
                .isSuccess
            if (!arrived) continue
            val reply = ProbeProtocol.parse(Arrays.copyOf(packet.data, packet.length))
                as? ProbeProtocol.Packet.Probe ?: continue
            // 应答只回到问话人那个地址：它是单播回去的，不占用广播通道，也不会被别人家的手机误收
            val bytes = ProbeProtocol.ack(reply.nonce, fingerprint, name, mediaPort, protocols)
            runCatching {
                bound.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
            }.onFailure { Log.w(TAG, "probe answer failed: ${Redactor.throwable(it)}") }
            // 只记「答了一次」，不记对端名字与地址：这一路不需要靠用户内容做命中判据（PRD 14.4）
            Log.i(TAG, "answered one probe")
        }
    }

    private fun announce(bound: DatagramSocket) {
        val port = mediaPort
        if (port <= 0) return
        val bytes = ProbeProtocol.hello(fingerprint, name, port, protocols)
        for (target in BroadcastTargets.of(localInterfaces())) {
            runCatching {
                val address = InetAddress.getByName(target)
                bound.send(DatagramPacket(bytes, bytes.size, address, ProbeProtocol.ANNOUNCE_PORT))
            }.onFailure { Log.w(TAG, "announce failed: ${Redactor.throwable(it)}") }
        }
    }

    companion object {
        private const val TAG = "AirMirrorProbe"
        private const val PROTOCOL_AMRC = "AMRC"
        private const val LOCK_TAG = "airmirror-probe"
        private const val ANNOUNCE_INTERVAL_MS = 2_000L
        private const val SOCKET_TIMEOUT_MS = 500

        /** 本机带掩码的 IPv4 接口；链路本地地址也带回来，由 [BroadcastTargets] 自己判该不该发。 */
        fun localInterfaces(): List<BroadcastTargets.Interface> {
            val out = mutableListOf<BroadcastTargets.Interface>()
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            for (face in interfaces) {
                if (!face.isUp) continue
                for (address in face.interfaceAddresses) {
                    val bytes = address.address?.address ?: continue
                    if (bytes.size != 4) continue
                    out.add(BroadcastTargets.Interface(bytes.ipv4Text(), address.networkPrefixLength.toInt()))
                }
            }
            return out
        }

        private fun ByteArray.ipv4Text(): String = joinToString(".") { (it.toInt() and 0xFF).toString() }
    }
}

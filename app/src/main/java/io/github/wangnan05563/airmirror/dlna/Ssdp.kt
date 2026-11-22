package io.github.wangnan05563.airmirror.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.github.wangnan05563.airmirror.privacy.Redactor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.DatagramPacket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/** SSDP 报文的构造与解析（纯函数，可单测）。 */
object Ssdp {
    const val GROUP = "239.255.255.250"
    const val PORT = 1900
    const val SERVER = "AirMirror/1.0 UPnP/1.0 DLNADOC/1.50"
    const val CACHE_MAX_SECONDS = 180
    const val ST_ALL = "ssdp:all"
    const val ROOTDEVICE = "upnp:rootdevice"

    /** 周期 alive 的间隔。必须小于 max-age，否则控制点缓存到期时会短暂把设备当成离线。 */
    internal const val ALIVE_INTERVAL_MS = 20_000L

    /** 1900 被别的 DLNA 接收端占用时，按这个间隔重试绑定，不能一次失败就永久放弃。 */
    internal const val BIND_RETRY_MS = 2_000L

    /** 三份报文都要带同一份 BOOTID，抽成常量避免版本号在报文里各写各的。 */
    private const val BOOTID_HEADER = "BOOTID.UPNP.ORG: 1\r\n"

    /** 是 M-SEARCH 则返回其 ST，否则 null（NOTIFY/HTTP 探测一律忽略）。 */
    fun searchTarget(datagram: String): String? {
        val lines = datagram.split("\r\n", "\n")
        val line = lines.firstOrNull()?.trim() ?: return null
        if (!line.startsWith("M-SEARCH", ignoreCase = true)) return null
        for (header in lines.drop(1)) {
            val colon = header.indexOf(':')
            if (colon <= 0) continue
            val name = header.substring(0, colon).trim()
            if (!name.equals("ST", ignoreCase = true)) continue
            val value = header.substring(colon + 1).trim()
            return value.ifEmpty { null }
        }
        return null
    }

    /** 控制点按 ssdp:all 或具体类型（含 upnp:rootdevice / uuid）搜索；命中任一已声明目标即应答。 */
    fun matches(st: String, types: List<String>): Boolean = st == ST_ALL || st in types
    fun searchResponse(st: String, location: String, usn: String): String = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("CACHE-CONTROL: max-age=").append(CACHE_MAX_SECONDS).append("\r\n")
        append("DATE: ").append(httpDate()).append("\r\n")
        append("EXT: \r\n")
        append("LOCATION: ").append(location).append("\r\n")
        append("SERVER: ").append(SERVER).append("\r\n")
        append("ST: ").append(st).append("\r\n")
        append("USN: ").append(usn).append("\r\n")
        append(BOOTID_HEADER)
        append("CONFIGID.UPNP.ORG: 1\r\n")
        append("\r\n")
    }

    /**
     * 一次 M-SEARCH 的全部应答。
     *
     * `ssdp:all` 不能只回一条 `ST: ssdp:all`：UPnP 控制点要按具体目标建立设备缓存，
     * 部分视频 App 看到笼统的 ssdp:all 会直接丢弃，表现就是「设备时有时无」。
     * 具体 ST 仍只回一条，保持原有兼容性。
     */
    fun searchResponses(
        st: String,
        location: String,
        usnBase: String,
        targets: List<String>,
    ): List<Pair<String, String>> {
        val answerTargets = if (st == ST_ALL) targets else listOf(st)
        return answerTargets.map { target -> target to searchResponse(target, location, usn(target, usnBase)) }
    }

    /** rootdevice 与 uuid 的 USN 有专门口径，其余目标用 `USN = <uuid>::<ST>`。 */
    fun usn(type: String, usnBase: String): String = when (type) {
        ROOTDEVICE -> "$usnBase::$ROOTDEVICE"
        usnBase -> usnBase
        else -> "$usnBase::$type"
    }

    /** 控制点用 DATE 校正缓存寿命，必须报真实时间（RFC1123 GMT）。 */
    fun httpDate(nowMillis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date(nowMillis))

    fun alive(nt: String, location: String, usn: String): String = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: ").append(GROUP).append(':').append(PORT).append("\r\n")
        append("CACHE-CONTROL: max-age=").append(CACHE_MAX_SECONDS).append("\r\n")
        append("DATE: ").append(httpDate()).append("\r\n")
        append("LOCATION: ").append(location).append("\r\n")
        append("NT: ").append(nt).append("\r\n")
        append("NTS: ssdp:alive\r\n")
        append("SERVER: ").append(SERVER).append("\r\n")
        append("USN: ").append(usn).append("\r\n")
        append(BOOTID_HEADER)
        append("CONFIGID.UPNP.ORG: 1\r\n")
        append("\r\n")
    }

    fun byebye(nt: String, usn: String): String = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: ").append(GROUP).append(':').append(PORT).append("\r\n")
        append("NT: ").append(nt).append("\r\n")
        append("NTS: ssdp:byebye\r\n")
        append("USN: ").append(usn).append("\r\n")
        append(BOOTID_HEADER)
        append("\r\n")
    }
}

/**
 * SSDP 应答器：监听组播 1900（同时能吃到的单播 M-SEARCH），回应 M-SEARCH 并周期发 ssdp:alive。
 *
 * 组播接收在 Android 上要先拿 MulticastLock，否则 Wi-Fi 驱动会过滤掉组播包。
 */
class SsdpResponder(
    context: Context,
    initialLocation: String,
    private val usnBase: String,
    private val types: List<String>,
    private val onUnavailable: ((String) -> Unit)? = null,
    private val onReady: (() -> Unit)? = null,
) {
    private val appContext = context.applicationContext

    /** 网络切换后 LOCATION 要跟着换；只改字段并关 socket，让 loop 用新地址重绑。 */
    @Volatile
    private var location: String = initialLocation

    /** 通告目标：三个服务类型 + upnp:rootdevice + 自己的 uuid。 */
    private val targets = types + Ssdp.ROOTDEVICE + usnBase

    /** 1900 被占用时只在一次故障串里提示一次，避免每 2 秒刷屏。 */
    private var unavailableReported = false

    /** 主动换地址触发的重绑，不能当成端口冲突去提示用户。 */
    @Volatile
    private var restarting = false


    @Volatile
    private var running = false
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        acquireMulticastLock()
        worker = thread(name = "airmirror-ssdp") { loop() }
    }

    private fun loop() {
        // 电视盒上常同时装着「云视听小电视」这类自带 DLNA 接收端，它们也会占 1900。
        // 绑定失败不能只打一条日志就退出：退出后 AirMirror 的 HTTP 服务还在，但控制点永远搜不到，
        // 表现就是「设备有时刷得出来、有时刷不出来」。这里每 2 秒重试，直到拿到端口或服务被停。
        while (running) {
            try {
                val listener = openListener()
                socket = listener
                restarting = false
                unavailableReported = false
                onReady?.invoke()
                sendAliveBurst(listener, InetAddress.getByName(Ssdp.GROUP))
                receiveLoop(listener)
            } catch (e: Exception) {
                if (!running) break
                if (restarting) {
                    restarting = false
                    continue
                }
                if (!unavailableReported) {
                    unavailableReported = true
                    onUnavailable?.invoke(
                        "DLNA 发现端口 1900 被系统投屏占用，手机可能搜不到设备；请关闭电视盒的「多屏互动/极光投屏」后重试。",
                    )
                }
                Log.w(TAG, "ssdp listener unavailable, retrying: ${Redactor.throwable(e)}")
                closeSocket()
                try {
                    Thread.sleep(Ssdp.BIND_RETRY_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            } finally {
                closeSocket()
            }
        }
    }

    /**
     * 先设 SO_REUSEADDR 再 bind，而不是 `MulticastSocket(PORT)` 后再设：
     * 后者 bind 已经发生，reuse 设置太晚；与电视盒自带 DLNA 接收端共存时直接 EADDRINUSE。
     */
    private fun openListener(): MulticastSocket {
        val group = InetAddress.getByName(Ssdp.GROUP)
        val listener = MulticastSocket()
        listener.reuseAddress = true
        listener.timeToLive = 4
        listener.bind(InetSocketAddress(Ssdp.PORT))
        // 每秒醒一次，stop() 或网卡切换时不会永远卡在 receive；正常 M-SEARCH 仍在同一 socket 上收。
        listener.soTimeout = 1_000
        val networkInterface = interfaceOrNull()
        if (networkInterface == null) {
            @Suppress("DEPRECATION")
            listener.joinGroup(group)
        } else {
            // 发送 alive 与接收 M-SEARCH 必须走同一张网卡；只 join 不设 outgoing interface，
            // 多网卡（有线 + Wi-Fi / VPN）时 LOCATION 会指到另一张网卡的地址。
            listener.networkInterface = networkInterface
            listener.joinGroup(InetSocketAddress(group, Ssdp.PORT), networkInterface)
        }
        return listener
    }

    private fun receiveLoop(listener: MulticastSocket) {
        val buffer = ByteArray(2048)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                listener.receive(packet)
            } catch (e: java.net.SocketTimeoutException) {
                // 给 stop/网卡切换留一个检查 running 的窗口；没有超时就永远卡在 receive 里。
                continue
            } catch (e: Exception) {
                if (running) throw e else break
            }
            val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
            val st = Ssdp.searchTarget(text) ?: continue
            if (!Ssdp.matches(st, targets)) continue
            val replies = Ssdp.searchResponses(st, location, usnBase, targets)
            for ((target, payload) in replies) {
                val bytes = payload.toByteArray(Charsets.US_ASCII)
                listener.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
            }
            // 对端 IP 属于地址类信息，排查用 ST 已够
            Log.i(TAG, "answered M-SEARCH ST=$st replies=${replies.size}")
        }
    }

    /**
     * 绑定成功后连发三次 alive（间隔 200ms），覆盖刚开机/刚切网时控制点的发现窗口。
     * 单次 alive 很容易在 UDP 丢包时错过，控制点就会等到下一次搜索或下一轮周期。
     */
    private fun sendAliveBurst(listener: MulticastSocket, group: InetAddress) {
        repeat(3) { round ->
            if (!running) return
            sendAlive(listener, group)
            if (round < 2) runCatching { Thread.sleep(200) }
        }
    }

    /** 周期 alive，让中途开机的控制点也能刷到设备；间隔必须小于 CACHE_MAX_SECONDS。 */
    fun startAliveBeacon(intervalMs: Long = Ssdp.ALIVE_INTERVAL_MS) {
        thread(name = "airmirror-ssdp-alive", isDaemon = true) {
            while (running) {
                Thread.sleep(intervalMs)
                val listener = socket ?: continue
                runCatching { sendAlive(listener, InetAddress.getByName(Ssdp.GROUP)) }
            }
        }
    }

    private fun sendAlive(multicast: MulticastSocket, group: InetAddress) {
        for (type in targets) {
            val bytes = Ssdp.alive(type, location, usnFor(type)).toByteArray(Charsets.US_ASCII)
            multicast.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(group, Ssdp.PORT)))
        }
        Log.i(TAG, "sent ssdp:alive for ${targets.size} targets")
    }

    /**
     * 网络切换后更新 LOCATION，并让 loop 重建监听（否则旧 socket 仍绑在旧网卡，
     * 手机能读到旧地址却连不上）。HTTP 端口不变，只是公告地址换掉。
     */
    fun updateLocation(newLocation: String) {
        if (newLocation == location) return
        restarting = true
        location = newLocation
        closeSocket()
    }

    fun stop() {
        running = false
        val multicast = socket
        if (multicast != null) {
            val group = runCatching { InetAddress.getByName(Ssdp.GROUP) }.getOrNull()
            if (group != null) {
                for (type in targets) {
                    val bye = Ssdp.byebye(type, usnFor(type)).toByteArray(Charsets.US_ASCII)
                    runCatching { multicast.send(DatagramPacket(bye, bye.size, InetSocketAddress(group, Ssdp.PORT))) }
                }
            }
        }
        closeSocket()
        releaseMulticastLock()
        worker?.join(1_000)
        worker = null
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    /** rootdevice 与 uuid 的 USN 有专门口径，其余目标用 `USN = <uuid>::<ST>`。 */
    private fun usnFor(type: String): String = Ssdp.usn(type, usnBase)

    private fun interfaceOrNull(): NetworkInterface? {
        val address = localIpv4() ?: return null
        return try {
            NetworkInterface.getByInetAddress(InetAddress.getByName(address))
        } catch (e: Exception) {
            null
        }
    }

    private fun acquireMulticastLock() {
        // WifiManager 必须从 Application 取：拿 Activity 会在 Android N 以前泄漏整棵视图树
        val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("airmirror-ssdp").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private companion object {
        const val TAG = "AirMirrorSsdp"
    }
}

/** 取本机站点内网 IPv4（组播接口与 LOCATION 都要用）。 */
fun localIpv4(): String? {
    var fallback: String? = null
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is java.net.Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    val host = address.hostAddress ?: continue
                    // 有线/Wi-Fi 是投屏实际走的网卡；VPN/Tailscale 的 10.x 地址也满足 siteLocal，
                    // 先返回它会把 LOCATION 指到手机根本路由不到的地址。
                    if (isLanInterface(networkInterface.name)) return host
                    if (fallback == null) fallback = host
                }
            }
        }
    } catch (e: Exception) {
        return fallback
    }
    return fallback
}

private fun isLanInterface(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith("wlan") || lower.startsWith("eth") || lower.startsWith("en") ||
        lower.startsWith("ap") || lower.startsWith("wifi")
}

/**
 * 本机所有 IPv4。判断「这条局域网公告是不是本机发的」不能只看 [localIpv4] 那一张网卡：
 * mDNS 可能从热点、Tailscale 或第二块网卡宣告，只比站点内网那一个会漏掉这些接口，自己又出现在列表里。
 */
fun localIpv4s(): Set<String> {
    val result = HashSet<String>()
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                    address.hostAddress?.let { result.add(it) }
                }
            }
        }
    } catch (e: Exception) {
        // 枚举网卡失败就当没有本机地址：宁可多显示一条，也不要因为取不到地址把真邻居挡掉
        return emptySet()
    }
    return result
}

package io.github.wangnan05563.airmirror.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
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
    const val CACHE_MAX_SECONDS = 1800
    const val ST_ALL = "ssdp:all"

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
        append("BOOTID.UPNP.ORG: 1\r\n")
        append("CONFIGID.UPNP.ORG: 1\r\n")
        append("\r\n")
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
        append("BOOTID.UPNP.ORG: 1\r\n")
        append("CONFIGID.UPNP.ORG: 1\r\n")
        append("\r\n")
    }

    fun byebye(nt: String, usn: String): String = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: ").append(GROUP).append(':').append(PORT).append("\r\n")
        append("NT: ").append(nt).append("\r\n")
        append("NTS: ssdp:byebye\r\n")
        append("USN: ").append(usn).append("\r\n")
        append("BOOTID.UPNP.ORG: 1\r\n")
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
    private val location: String,
    private val usnBase: String,
    private val types: List<String>,
) {
    private val appContext = context.applicationContext

    /** 通告目标：三个服务类型 + upnp:rootdevice + 自己的 uuid。 */
    private val targets = types + UPNP_ROOTDEVICE + usnBase

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
        try {
            val group = InetAddress.getByName(Ssdp.GROUP)
            val listener = MulticastSocket(Ssdp.PORT).apply {
                reuseAddress = true
                timeToLive = 4
                val networkInterface = interfaceOrNull()
                if (networkInterface == null) {
                    @Suppress("DEPRECATION")
                    joinGroup(group)
                } else {
                    joinGroup(InetSocketAddress(group, Ssdp.PORT), networkInterface)
                }
            }
            socket = listener
            sendAlive(listener, group)
            val buffer = ByteArray(2048)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    listener.receive(packet)
                } catch (e: Exception) {
                    if (running) continue else break
                }
                val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                val st = Ssdp.searchTarget(text) ?: continue
                if (!Ssdp.matches(st, targets)) continue
                val reply = Ssdp.searchResponse(st, location, usnFor(st)).toByteArray(Charsets.US_ASCII)
                listener.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                Log.i(TAG, "answered M-SEARCH ST=$st from ${packet.address.hostAddress}:${packet.port}")
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "ssdp listener stopped: ${e.message}")
        } finally {
            closeSocket()
        }
    }

    /** 周期 alive，让中途开机的控制点也能刷到设备。 */
    fun startAliveBeacon(intervalMs: Long = 600_000) {
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
        Log.i(TAG, "sent ssdp:alive for ${targets.size} targets, location=$location")
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
    private fun usnFor(type: String): String = when (type) {
        UPNP_ROOTDEVICE -> "$usnBase::$UPNP_ROOTDEVICE"
        usnBase -> usnBase
        else -> "$usnBase::$type"
    }

    private fun interfaceOrNull(): NetworkInterface? {
        val address = localIpv4() ?: return null
        return try {
            NetworkInterface.getByInetAddress(InetAddress.getByName(address))
        } catch (e: Exception) {
            null
        }
    }

    private fun acquireMulticastLock() {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
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
        const val UPNP_ROOTDEVICE = "upnp:rootdevice"
    }
}

/** 取本机站点内网 IPv4（组播接口与 LOCATION 都要用）。 */
fun localIpv4(): String? {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is java.net.Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        null
    } catch (e: Exception) {
        null
    }
}

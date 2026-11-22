package io.github.wangnan05563.airmirror.pc

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * PC 端 mDNS 发现：查询 `_airmirror._tcp.local`，解析 PTR/SRV/TXT/A 记录。
 *
 * 与 Android NSD 的 TXT 口径一致：`fp` 是接收端长期指纹；老版本没有 TXT 时退回服务实例名。
 * 只负责协议与设备事件，不直接操作 UI；T06110/T06111 负责配对与安装向导接入。
 */
class MdnsDiscovery(
    private val serviceType: String = DEFAULT_SERVICE_TYPE,
    private val onFound: (Device) -> Unit,
    private val onLost: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : Closeable {

    data class Device(
        val name: String,
        val host: String,
        val port: Int,
        val fingerprint: String,
        val lastSeenAt: Long,
    )

    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private var worker: Thread? = null
    private val devices = LinkedHashMap<String, Device>()
    private val lock = Any()

    fun start() {
        if (running) return
        val bound = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                timeToLive = 255
                soTimeout = SOCKET_TIMEOUT_MS
            }
        }.onFailure { onError("mDNS 端口 5353 不可用：${it.message ?: it.javaClass.simpleName}") }.getOrNull() ?: return
        socket = bound
        joinAllInterfaces(bound)
        running = true
        worker = thread(name = "airmirror-mdns") { loop(bound) }
        sendQuery(bound)
    }

    /** 手动刷新：不改变已发现列表，只补发一次 PTR 查询。 */
    fun queryNow() {
        val s = socket ?: return
        sendQuery(s)
    }

    override fun close() = stop()

    fun stop() {
        if (!running) return
        running = false
        runCatching { socket?.close() }
        socket = null
        runCatching { worker?.join(1_000) }
        worker = null
        synchronized(lock) { devices.clear() }
    }

    private fun joinAllInterfaces(socket: MulticastSocket) {
        val group = InetAddress.getByName(GROUP)
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        var joined = 0
        for (face in interfaces) {
            if (!face.isUp || face.isLoopback || !face.supportsMulticast()) continue
            runCatching {
                socket.joinGroup(InetSocketAddress(group, PORT), face)
                joined++
            }.onFailure { onError("加入 mDNS 组播失败 ${face.name}：${it.message ?: it.javaClass.simpleName}") }
        }
        // 某些 Windows 网卡只能走默认组播接口；至少尝试一次，失败不阻断收包。
        if (joined == 0) runCatching { socket.joinGroup(InetSocketAddress(group, PORT), null) }
    }

    private fun sendQuery(socket: MulticastSocket) {
        runCatching {
            val bytes = MdnsPacket.query(serviceType)
            val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName(GROUP), PORT)
            socket.send(packet)
        }.onFailure { onError("发送 mDNS 查询失败：${it.message ?: it.javaClass.simpleName}") }
    }

    private fun loop(socket: MulticastSocket) {
        val buffer = ByteArray(MAX_PACKET)
        var nextQueryAt = now() + QUERY_INTERVAL_MS
        while (running) {
            if (now() >= nextQueryAt) {
                nextQueryAt = now() + QUERY_INTERVAL_MS
                sendQuery(socket)
            }
            val packet = DatagramPacket(buffer, buffer.size)
            val arrived = try {
                socket.receive(packet)
                true
            } catch (_: SocketTimeoutException) {
                false
            } catch (e: Exception) {
                if (running) onError("mDNS 收包失败：${e.message ?: e.javaClass.simpleName}")
                false
            }
            if (arrived) handle(packet)
            prune()
        }
    }

    private fun handle(packet: DatagramPacket) {
        val found = runCatching { MdnsPacket.parse(packet.data.copyOf(packet.length)) }
            .onFailure { onError("mDNS 报文解析失败：${it.message ?: it.javaClass.simpleName}") }
            .getOrNull() ?: return
        val parsed = MdnsDevices.from(found.records, serviceType)
        val events = mutableListOf<Device>()
        synchronized(lock) {
            for (device in parsed) {
                val old = devices[device.fingerprint]
                if (old == null || old.host != device.host || old.port != device.port || old.name != device.name) {
                    events += device
                }
                devices[device.fingerprint] = device.copy(lastSeenAt = now())
            }
        }
        events.forEach(onFound)
    }

    private fun prune() {
        val cutoff = now() - DEVICE_TTL_MS
        val lost = mutableListOf<String>()
        synchronized(lock) {
            val iterator = devices.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.lastSeenAt < cutoff) {
                    lost += entry.key
                    iterator.remove()
                }
            }
        }
        lost.forEach(onLost)
    }

    companion object {
        const val DEFAULT_SERVICE_TYPE = "_airmirror._tcp.local"
        private const val GROUP = "224.0.0.251"
        private const val PORT = 5353
        private const val MAX_PACKET = 4096
        private const val SOCKET_TIMEOUT_MS = 1_000
        private const val QUERY_INTERVAL_MS = 5_000L
        private const val DEVICE_TTL_MS = 120_000L
    }
}

/** DNS-SD 报文编解码；独立成对象便于不依赖网卡的单测。 */
internal object MdnsPacket {

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33
    private const val CLASS_IN = 1
    private const val TYPE_PTR_VALUE = TYPE_PTR

    sealed class RData {
        data class Ptr(val target: String) : RData()
        data class Srv(val target: String, val port: Int) : RData()
        data class Txt(val values: Map<String, String>) : RData()
        data class Address(val host: String) : RData()
        data object Other : RData()
    }

    data class Record(val name: String, val type: Int, val ttl: Long, val data: RData)

    data class Message(val records: List<Record>)

    fun query(serviceType: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, 0) // ID
        writeU16(out, 0) // flags: standard query
        writeU16(out, 1) // QDCOUNT
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, 0)
        writeName(out, serviceType)
        writeU16(out, TYPE_PTR_VALUE)
        writeU16(out, CLASS_IN)
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray): Message {
        if (bytes.size < 12) return Message(emptyList())
        var pos = 12
        val qd = readU16(bytes, 4)
        val an = readU16(bytes, 6)
        val ns = readU16(bytes, 8)
        val ar = readU16(bytes, 10)
        repeat(qd) {
            if (pos > bytes.size) return Message(emptyList())
            val (_, next) = readName(bytes, pos)
            pos = next + 4
        }
        val records = mutableListOf<Record>()
        repeat(an + ns + ar) {
            if (pos > bytes.size) return@repeat
            val (name, next) = runCatching { readName(bytes, pos) }.getOrElse { return@repeat }
            pos = next
            if (pos + 10 > bytes.size) return@repeat
            val type = readU16(bytes, pos)
            pos += 2
            pos += 2 // class
            val ttl = readU32(bytes, pos)
            pos += 4
            val length = readU16(bytes, pos)
            pos += 2
            if (pos + length > bytes.size) return@repeat
            val data = decodeData(bytes, pos, length, type)
            pos += length
            records += Record(name, type, ttl, data)
        }
        return Message(records)
    }

    private fun decodeData(bytes: ByteArray, start: Int, length: Int, type: Int): RData = when (type) {
        TYPE_PTR -> {
            val (target, _) = readName(bytes, start)
            RData.Ptr(target)
        }
        TYPE_SRV -> {
            if (length < 7) RData.Other
            else {
                val port = readU16(bytes, start + 4)
                val (target, _) = readName(bytes, start + 6)
                RData.Srv(target, port)
            }
        }
        TYPE_TXT -> {
            val values = LinkedHashMap<String, String>()
            var p = start
            val end = start + length
            while (p < end) {
                val len = bytes[p].toInt() and 0xFF
                p++
                if (p + len > end) break
                val text = String(bytes, p, len, Charsets.UTF_8)
                val eq = text.indexOf('=')
                if (eq > 0) values[text.substring(0, eq)] = text.substring(eq + 1)
                p += len
            }
            RData.Txt(values)
        }
        TYPE_A -> {
            if (length != 4) RData.Other
            else {
                val host = (start until start + 4).joinToString(".") { (bytes[it].toInt() and 0xFF).toString() }
                RData.Address(host)
            }
        }
        TYPE_AAAA -> {
            if (length != 16) RData.Other
            else RData.Address((0 until 16 step 2).joinToString(":") {
                "%02x%02x".format(bytes[start + it].toInt() and 0xFF, bytes[start + it + 1].toInt() and 0xFF)
            })
        }
        else -> RData.Other
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        val labels = name.trim('.').split('.')
        for (label in labels) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 63) { "mDNS label too long" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun readName(bytes: ByteArray, start: Int): Pair<String, Int> {
        var pos = start
        var next = start
        var jumped = false
        var depth = 0
        val labels = mutableListOf<String>()
        while (pos < bytes.size) {
            if (depth++ > 32) error("mDNS name pointer loop")
            val len = bytes[pos].toInt() and 0xFF
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= bytes.size) error("truncated mDNS pointer")
                val pointer = ((len and 0x3F) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
                if (!jumped) next = pos + 2
                jumped = true
                pos = pointer
                continue
            }
            if (len == 0) {
                pos++
                if (!jumped) next = pos
                break
            }
            if (pos + 1 + len > bytes.size) error("truncated mDNS label")
            labels += String(bytes, pos + 1, len, Charsets.UTF_8)
            pos += 1 + len
        }
        if (!jumped) next = pos
        return labels.joinToString(".") to next
    }
}

/** 把一次 mDNS 响应里的 PTR/SRV/TXT/A 合成设备条目。 */
internal object MdnsDevices {

    fun from(records: List<MdnsPacket.Record>, serviceType: String): List<MdnsDiscovery.Device> {
        val ptr = records.filter {
            it.type == MdnsPacket.TYPE_PTR &&
                (it.data as? MdnsPacket.RData.Ptr)?.target?.endsWith(".$serviceType", true) == true
        }
        val result = mutableListOf<MdnsDiscovery.Device>()
        for (record in ptr) {
            val instance = (record.data as MdnsPacket.RData.Ptr).target
            val srv = records.firstOrNull { it.name.equals(instance, true) && it.data is MdnsPacket.RData.Srv }
                ?.data as? MdnsPacket.RData.Srv ?: continue
            val txt = records.firstOrNull { it.name.equals(instance, true) && it.data is MdnsPacket.RData.Txt }
                ?.data as? MdnsPacket.RData.Txt
            val address = records.firstOrNull {
                it.name.equals(srv.target, true) && it.data is MdnsPacket.RData.Address
            }?.data as? MdnsPacket.RData.Address
            val host = address?.host ?: srv.target
            val display = instance.removeSuffix(".$serviceType").ifBlank { instance }
            val fingerprint = txt?.values?.get("fp")?.takeIf { it.isNotBlank() } ?: instance
            result += MdnsDiscovery.Device(display, host, srv.port, fingerprint, 0L)
        }
        return result
    }
}

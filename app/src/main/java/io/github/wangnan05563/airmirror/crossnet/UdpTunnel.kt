package io.github.wangnan05563.airmirror.crossnet

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.TreeMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 跨网直连隧道：把打洞成功的 UDP 路径包装成「有序、可靠、可当流用」的通道。
 *
 * 为什么必须自己写一层：打洞只能穿透 NAT 的 UDP 映射，而现有私有通道（握手、端到端加密、帧协议）
 * 全部建立在 `InputStream`/`OutputStream` 之上。没有这一层，「打洞成功」就只是一条收不到完整帧的裸 UDP。
 * 只做当前需要的最小可靠语义——分片、序号、累积 ACK、超时重传、按序交付、FIN——不做拥塞控制：
 * 跨网是本产品的兜底路径（PRD FS-04 定位），码率由既有的画质协商与延迟降级控制。
 *
 * 背压策略：接收队列满时不推进期望序号、也不回 ACK，让发送方窗口自然卡住并按 RTO 重传，
 * 而不是丢帧导致画面永久缺块。乱序缓存有上限，超出丢最远分片，靠重传补齐。
 */
class UdpTunnel(
    private val socket: DatagramSocket,
    private val peer: InetSocketAddress,
) : Closeable {
    private val lock = ReentrantLock()
    private val canSend = lock.newCondition()
    private val chunks = LinkedBlockingQueue<ByteArray>(QUEUE_CHUNKS)

    private val pending = TreeMap<Long, Segment>()
    private val outOfOrder = TreeMap<Long, ByteArray>()
    private var nextSeq = 0L
    private var lastAck = -1L
    private var expectedSeq = 0L
    /** 对端 FIN 的序号：它排在所有数据之后，必须等前面的数据都交付了才能收尾。 */
    private var finSeq = Long.MAX_VALUE
    private var finSignaled = false
    @Volatile private var readTimeoutMs = 0
    @Volatile private var eof = false
    @Volatile private var closed = false
    private var current: ByteArray? = null
    private var currentOffset = 0
    private var retransmits = 0

    private val receiver = Thread({ receiveLoop() }, "am-tunnel-rx").apply { isDaemon = true }
    private val retimer = Thread({ retransmitLoop() }, "am-tunnel-rtx").apply { isDaemon = true }

    fun start(): UdpTunnel {
        receiver.start()
        retimer.start()
        return this
    }

    /** 与 [java.net.Socket.setSoTimeout] 同语义：0 表示一直等。 */
    fun setReadTimeout(timeoutMs: Int) {
        readTimeoutMs = timeoutMs.coerceAtLeast(0)
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            require(off >= 0 && len >= 0 && off + len <= b.size) { "invalid read range" }
            if (len == 0) return 0
            while (true) {
                val chunk = current
                if (chunk != null) {
                    val copy = minOf(len, chunk.size - currentOffset)
                    System.arraycopy(chunk, currentOffset, b, off, copy)
                    currentOffset += copy
                    if (currentOffset >= chunk.size) {
                        current = null
                        currentOffset = 0
                    }
                    return copy
                }
                val next = pollChunk() ?: return -1
                current = next
                currentOffset = 0
            }
        }

        override fun available(): Int {
            val chunk = current ?: return 0
            return chunk.size - currentOffset
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            require(off >= 0 && len >= 0 && off + len <= b.size) { "invalid write range" }
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val size = minOf(remaining, MSS)
                val payload = ByteArray(size)
                System.arraycopy(b, offset, payload, 0, size)
                enqueue(payload)
                offset += size
                remaining -= size
            }
        }
    }

    override fun close() {
        if (closed) return
        // 先把已入队但还没被确认的数据等一等：UDP 乱序时立刻发 FIN，对端可能只看到 FIN 而永远收不到最后一帧。
        val deadline = System.currentTimeMillis() + CLOSE_GRACE_MS
        while (System.currentTimeMillis() < deadline) {
            lock.lock()
            try {
                if (pending.isEmpty()) break
            } finally {
                lock.unlock()
            }
            try {
                Thread.sleep(CLOSE_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        // FIN 只发一次且不等确认：对端靠重传也能收到，本地不必为收尾再拖一个超时窗口
        sendFrame(FLAG_FIN, nextSequence(), -1, ByteArray(0))
        closed = true
        lock.lock()
        try {
            pending.clear()
            canSend.signalAll()
        } finally {
            lock.unlock()
        }
        chunks.clear()
        chunks.offer(EOF_MARK)
        runCatching { socket.close() }
    }

    private fun pollChunk(): ByteArray? {
        // 不能因为 eof/closed 就直接返回：远端 FIN 可能先于最后一个数据包到达，队列里还有数据要交付
        val chunk = if (readTimeoutMs <= 0) {
            chunks.take()
        } else {
            chunks.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                ?: throw SocketTimeoutException("tunnel read timeout")
        }
        if (chunk === EOF_MARK) {
            eof = true
            return null
        }
        return chunk
    }

    private fun enqueue(payload: ByteArray) {
        lock.lock()
        try {
            while (!closed && pending.size >= WINDOW) {
                // 发送窗口满时阻塞写：不限制的话，一台不确认的对端会把内存吃光
                canSend.await(RTO_MS, TimeUnit.MILLISECONDS)
            }
            if (closed) throw IOException("tunnel closed")
            val seq = nextSeq++
            pending[seq] = Segment(seq, payload, System.currentTimeMillis())
            sendFrame(FLAG_DATA, seq, lastAck, payload)
        } finally {
            lock.unlock()
        }
    }

    private fun nextSequence(): Long {
        lock.lock()
        try {
            return nextSeq++
        } finally {
            lock.unlock()
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(MAX_FRAME)
        while (!closed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                return // socket 被关闭或不可读：交给上层按断链处理
            }
            // 只认打洞时确定的那个对端，避免同一端口上的无关 UDP 包污染流。
            // InetAddress 必须用 equals 比地址：`!=` 比的是实例身份，同一个 IP 换一个实例
            // （socket 层新建、缓存失效）就会被当成无关包丢掉，表现为「隧道通了但一个字节也收不到」。
            if (!packet.address.equals(peer.address) || packet.port != peer.port) continue
            val frame = decode(packet.data, packet.length) ?: continue
            handle(frame)
        }
    }

    private fun handle(frame: Frame) {
        if (frame.ack >= 0) {
            lock.lock()
            try {
                if (frame.ack > lastAck) {
                    lastAck = frame.ack
                    pending.headMap(frame.ack, true).clear()
                    canSend.signalAll()
                }
            } finally {
                lock.unlock()
            }
        }
        if ((frame.flags and FLAG_DATA) != 0) {
            if (deliver(frame.seq, frame.payload)) {
                // 每个数据帧都回累积 ACK：丢 ACK 只会多一次重传，不会让窗口卡死
                sendFrame(FLAG_ACK, -1, highestDelivered(), ByteArray(0))
            }
        }
        if ((frame.flags and FLAG_FIN) != 0) {
            finSeq = frame.seq
            maybeFinish()
        }
    }

    /** FIN 到达不等于可以收尾：它排在数据之后，必须等 expectedSeq 追上 FIN 的序号。 */
    private fun maybeFinish() {
        lock.lock()
        try {
            if (!finSignaled && expectedSeq >= finSeq) {
                finSignaled = true
                eof = true
                chunks.offer(EOF_MARK)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun highestDelivered(): Long {
        lock.lock()
        try {
            return expectedSeq - 1
        } finally {
            lock.unlock()
        }
    }

    /** 返回 false 表示没有接受（背压或已重复），调用方据此决定不回 ACK、让对端重传。 */
    private fun deliver(seq: Long, payload: ByteArray): Boolean {
        lock.lock()
        try {
            when {
                seq < expectedSeq -> return true // 重传的旧帧，ACK 已经覆盖
                seq == expectedSeq -> {
                    if (!chunks.offer(payload)) return false
                    expectedSeq++
                    while (true) {
                        val next = outOfOrder.remove(expectedSeq) ?: break
                        if (!chunks.offer(next)) {
                            // 队列满：把这一片放回乱序区，序号不推进，等下轮重传
                            outOfOrder[expectedSeq] = next
                            break
                        }
                        expectedSeq++
                    }
                    maybeFinish()
                    return true
                }
                else -> {
                    outOfOrder[seq] = payload
                    while (outOfOrder.size > MAX_OUT_OF_ORDER) {
                        outOfOrder.pollLastEntry()
                    }
                    return true
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun retransmitLoop() {
        while (!closed) {
            try {
                Thread.sleep(RTO_MS / 2)
            } catch (e: InterruptedException) {
                return
            }
            lock.lock()
            try {
                if (closed) return
                if (pending.isEmpty()) continue
                val now = System.currentTimeMillis()
                if (retransmits >= MAX_RETRANSMITS) {
                    // 对端长时间不确认：按断链处理，别让 UI 永远停在「正在连接」
                    closed = true
                    canSend.signalAll()
                    chunks.offer(EOF_MARK)
                    runCatching { socket.close() }
                    return
                }
                for (segment in pending.values) {
                    if (now - segment.sentAt < RTO_MS) continue
                    segment.sentAt = now
                    retransmits++
                    sendFrame(FLAG_DATA, segment.seq, lastAck, segment.payload)
                }
            } finally {
                lock.unlock()
            }
        }
    }

    private fun sendFrame(flags: Int, seq: Long, ack: Long, payload: ByteArray) {
        val frame = ByteArray(HEADER + payload.size)
        frame[0] = ((MAGIC shr 8) and 0xFF).toByte()
        frame[1] = (MAGIC and 0xFF).toByte()
        frame[2] = VERSION.toByte()
        frame[3] = flags.toByte()
        writeInt(frame, 4, seq)
        writeInt(frame, 8, ack)
        writeShort(frame, 12, payload.size)
        System.arraycopy(payload, 0, frame, HEADER, payload.size)
        runCatching { socket.send(DatagramPacket(frame, frame.size, peer.address, peer.port)) }
    }

    private fun decode(data: ByteArray, length: Int): Frame? {
        if (length < HEADER) return null
        if (readU16(data, 0) != MAGIC) return null
        if ((data[2].toInt() and 0xFF) != VERSION) return null
        val flags = data[3].toInt() and 0xFF
        val size = readU16(data, 12)
        if (HEADER + size > length) return null
        return Frame(
            flags,
            readInt(data, 4),
            readInt(data, 8),
            data.copyOfRange(HEADER, HEADER + size),
        )
    }

    private data class Segment(val seq: Long, val payload: ByteArray, var sentAt: Long)
    private data class Frame(val flags: Int, val seq: Long, val ack: Long, val payload: ByteArray)

    internal companion object {
        const val MAGIC = 0x414D
        const val VERSION = 1
        const val HEADER = 14
        const val MAX_FRAME = 1400
        /** 1000 字节载荷 + 14 字节头仍远小于常见 1500 MTU，避免 IP 分片带来的整包丢失 */
        const val MSS = 1000

        /**
         * 在途窗口。镜像的 IDR 关键帧常有几十 KB，窗口若只按 8 片（8KB）算，
         * 一帧就要拆成十几轮往返；跨网 RTT 50ms 时单帧就要接近 1 秒，画面根本跟不上。
         * 68 片 ≈ 68KB 在途（>64KiB），覆盖一个中等关键帧，内存代价仍可忽略。
         */
        const val WINDOW = 68

        const val RTO_MS = 250L
        const val MAX_RETRANSMITS = 40
        /** 乱序缓存必须不小于窗口，否则整窗乱序时会被自己的上限挤掉、触发重传风暴 */
        const val MAX_OUT_OF_ORDER = 128
        const val QUEUE_CHUNKS = 2048
        const val CLOSE_GRACE_MS = 600L
        const val CLOSE_POLL_MS = 20L

        const val FLAG_DATA = 1
        const val FLAG_ACK = 2
        const val FLAG_FIN = 4

        val EOF_MARK = ByteArray(0)

        fun writeInt(target: ByteArray, offset: Int, value: Long) {
            target[offset] = ((value shr 24) and 0xFF).toByte()
            target[offset + 1] = ((value shr 16) and 0xFF).toByte()
            target[offset + 2] = ((value shr 8) and 0xFF).toByte()
            target[offset + 3] = (value and 0xFF).toByte()
        }

        fun writeShort(target: ByteArray, offset: Int, value: Int) {
            target[offset] = ((value shr 8) and 0xFF).toByte()
            target[offset + 1] = (value and 0xFF).toByte()
        }

        /**
         * 必须按有符号 32 位组装：ACK 用 -1 表示「还没收到任何数据」，
         * 若按无符号拼成 4294967295，会被当成确认了全部待发分片，导致发送方清空队列后再也不重传。
         */
        fun readInt(data: ByteArray, offset: Int): Long =
            (((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)).toLong()

        fun readU16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}

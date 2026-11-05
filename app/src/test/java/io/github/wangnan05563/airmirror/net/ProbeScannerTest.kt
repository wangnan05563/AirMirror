package io.github.wangnan05563.airmirror.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FS-07 发送侧：广播/直连并行、ACK 校验、HELLO 静默降级、三轮全空才给原因、端口隔离。 */
class ProbeScannerTest {

    private val t0 = 100_000L
    private val local = listOf(BroadcastTargets.Interface("192.168.1.23", 24))

    private class FakeTransport : ProbeTransport {
        val sent = mutableListOf<Triple<String, Int, ByteArray>>()
        val inbox = ArrayDeque<ProbeDatagram>()
        override fun send(bytes: ByteArray, host: String, port: Int) {
            sent += Triple(host, port, bytes)
        }
        override fun receive(timeoutMs: Int): ProbeDatagram? = inbox.removeFirstOrNull()
        override fun close() {}
    }

    private class FakeSink : ProbeScanner.Sink {
        val found = mutableListOf<String>()
        val lost = mutableListOf<String>()
        val notices = mutableListOf<String>()
        var changes = 0
        override fun onDevice(fingerprint: String, name: String, host: String, port: Int, protocols: List<String>) {
            found += "$fingerprint|$name|$host|$port|${protocols.joinToString(",")}"
        }
        override fun onLost(fingerprint: String) { lost += fingerprint }
        override fun onNotice(text: String) { notices += text }
        override fun onChanged() { changes++ }
    }

    private inner class Rig(val transport: FakeTransport = FakeTransport(), val sink: FakeSink = FakeSink()) {
        lateinit var scanner: ProbeScanner
        fun build(recent: RecentTargets = RecentTargets(), interfaces: () -> List<BroadcastTargets.Interface> = { local }): Rig {
            scanner = ProbeScanner(
                transport = transport,
                sink = sink,
                recent = recent,
                interfaces = interfaces,
                clock = { t0 },
                token = { "tok123" },
                senderName = "测试手机",
            )
            return this
        }
    }

    @Test
    fun `一轮同时发全局广播 网段广播与缓存直连 且都不碰媒体端口`() {
        val rig = Rig()
        val recent = RecentTargets()
        recent.record("am-old", "上次客厅", "192.168.1.50", 41523, t0 - 1_000)
        rig.build(recent)
        rig.scanner.begin(t0)
        rig.scanner.poll(t0)
        assertEquals(3, rig.transport.sent.size)
        assertTrue(rig.transport.sent.all { it.second == ProbeProtocol.PROBE_PORT })
        assertTrue("探测不得触碰 41523", rig.transport.sent.none { it.second == 41523 })
        val hosts = rig.transport.sent.map { it.first }
        assertEquals(listOf("255.255.255.255", "192.168.1.255", "192.168.1.50"), hosts)
        val nonces = rig.transport.sent.map {
            (ProbeProtocol.parse(it.third) as ProbeProtocol.Packet.Probe).nonce
        }
        assertEquals(listOf('b', 'b', 'd'), nonces.map { it[0] })
        assertTrue(nonces.all { it.endsWith("tok123") })
        val names = rig.transport.sent.map { (ProbeProtocol.parse(it.third) as ProbeProtocol.Packet.Probe).senderName }
        assertTrue(names.all { it == "测试手机" })
    }

    @Test
    fun `广播 ACK 命中即登记设备 且不再发下一轮探测`() {
        val rig = Rig().build()
        rig.scanner.begin(t0)
        rig.scanner.poll(t0)
        rig.transport.inbox.add(
            ProbeDatagram(ProbeProtocol.ack("btok123", "am-tv", "客厅电视", 41523, listOf("AMRC")), "192.168.1.7", 41525),
        )
        rig.scanner.poll(t0 + 800)
        assertEquals(listOf("am-tv|客厅电视|192.168.1.7|41523|AMRC"), rig.sink.found)
        assertEquals(2, rig.transport.sent.size) // 命中后 probeDue 关掉，没有新探测
        assertTrue(rig.sink.notices.none { it.contains("AP 隔离") })
    }

    @Test
    fun `串台的旧 ACK 一律丢`() {
        val rig = Rig().build()
        rig.scanner.begin(t0)
        rig.scanner.poll(t0)
        rig.transport.inbox.add(
            ProbeDatagram(ProbeProtocol.ack("bSTALE", "am-tv", "客厅电视", 41523, listOf("AMRC")), "192.168.1.7", 41525),
        )
        rig.scanner.poll(t0 + 800)
        assertTrue(rig.sink.found.isEmpty())
    }

    @Test
    fun `无人提问的 HELLO 也能点亮列表`() {
        val rig = Rig().build()
        rig.scanner.begin(t0)
        rig.transport.inbox.add(
            ProbeDatagram(ProbeProtocol.hello("am-tv", "卧室盒子", 41523, listOf("AMRC")), "192.168.1.9", 41525),
        )
        rig.scanner.poll(t0)
        assertTrue(rig.sink.found.any { it.startsWith("am-tv|卧室盒子|192.168.1.9") })
    }

    @Test
    fun `组播命中不算降级 也不再补发探测`() {
        val rig = Rig().build()
        rig.scanner.begin(t0)
        rig.scanner.noteMulticastHit()
        rig.scanner.poll(t0)
        assertTrue(rig.transport.sent.isEmpty())
        assertTrue(rig.sink.notices.none { it.contains("AP 隔离") })
    }

    @Test
    fun `三轮全空才给 AP 隔离文案 且早轮不误报`() {
        val rig = Rig().build()
        rig.scanner.begin(t0)
        rig.scanner.poll(t0)
        rig.scanner.poll(t0 + 1_000)
        assertTrue("还在搜，不能提前说搜不到", rig.sink.notices.none { it.contains("AP 隔离") })
        rig.scanner.poll(t0 + DiscoveryLadder.DEFAULT_ROUND * DiscoveryLadder.DEFAULT_ROUNDS)
        assertEquals(DiscoveryLadder.TEXT_AP_ISOLATED, rig.sink.notices.last())
    }

    @Test
    fun `缓存网段与本机不同时给双频分离文案`() {
        val recent = RecentTargets()
        recent.record("am-old", "旧网电视", "192.168.2.50", 41523, t0 - 1_000)
        val rig = Rig().build(recent)
        rig.scanner.begin(t0)
        rig.scanner.poll(t0)
        rig.scanner.poll(t0 + DiscoveryLadder.DEFAULT_ROUND * DiscoveryLadder.DEFAULT_ROUNDS)
        assertEquals(DiscoveryLadder.TEXT_DUAL_BAND, rig.sink.notices.last())
    }

    @Test
    fun `久未再见的设备标离线`() {
        val rig = Rig().build()
        rig.scanner.begin(t0)
        rig.transport.inbox.add(
            ProbeDatagram(ProbeProtocol.hello("am-tv", "客厅", 41523, listOf("AMRC")), "192.168.1.9", 41525),
        )
        rig.scanner.poll(t0)
        rig.scanner.poll(t0 + 8_001)
        assertEquals(listOf("am-tv"), rig.sink.lost)
    }

    @Test
    fun `端口被占时整路退场 不反复抛异常`() {
        val broken = object : ProbeTransport {
            override fun send(bytes: ByteArray, host: String, port: Int) {}
            override fun receive(timeoutMs: Int): ProbeDatagram? = throw IllegalStateException("bind failed")
            override fun close() {}
        }
        val sink = FakeSink()
        val scanner = ProbeScanner(
            transport = broken, sink = sink,
            interfaces = { local }, clock = { t0 }, token = { "tok" },
        )
        scanner.begin(t0)
        scanner.poll(t0)
        scanner.poll(t0 + 1_000)
        scanner.poll(t0 + 2_000)
        assertFalse("失败后不该继续下结论", sink.notices.contains(DiscoveryLadder.TEXT_AP_ISOLATED))
    }
}

package io.github.wangnan05563.airmirror.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-02 设备列表：多路广播合并成一条并保留最强协议、离线条目置灰 10 分钟、
 * 排序为「最近成功连接 → 同网段 → 其他」，卡片不暴露 IP。
 */
class DeviceRegistryTest {

    private var clock = 0L

    private fun registry(): DeviceRegistry = DeviceRegistry(
        now = { clock },
        sameSubnet = { address -> address.startsWith("192.168.7.") },
    )

    @Test
    fun threeBroadcastsOfTheSameDeviceBecomeOneRow() {
        val subject = registry()
        subject.report("udn-1", "客厅电视", "AMRC", "192.168.7.20")
        subject.report("udn-1", "客厅电视", "DLNA", "192.168.7.20")
        subject.report("udn-1", "Living Room TV", "Cast", "192.168.7.21")
        val rows = subject.rows()
        assertEquals(1, rows.size)
        assertEquals(listOf("AMRC", "DLNA", "Cast"), rows[0].protocols)
        assertEquals("AMRC", rows[0].strongest)
        assertEquals("Living Room TV", rows[0].name)
    }

    @Test
    fun unknownProtocolSortsBehindTheKnownOnes() {
        val subject = registry()
        subject.report("udn-1", "盒子", "Miracast", "192.168.7.30")
        subject.report("udn-1", "盒子", "DLNA", "192.168.7.30")
        assertEquals(listOf("DLNA", "Miracast"), subject.rows()[0].protocols)
    }

    @Test
    fun rowsNeverExposeTheAddress() {
        val subject = registry()
        subject.report("udn-1", "客厅电视", "AMRC", "192.168.7.20")
        assertFalse(subject.rows()[0].toString().contains("192.168"))
        assertEquals("192.168.7.20", subject.addressOf("udn-1"))
    }

    @Test
    fun offlineDevicesStayGreyForTenMinutesThenDisappear() {
        val subject = registry()
        subject.report("udn-1", "客厅电视", "AMRC", "192.168.7.20")
        clock = 1_000
        subject.markLost("udn-1")
        clock = 1_000 + 10 * 60_000L - 1
        assertEquals(listOf("udn-1"), subject.rows().map { it.fingerprint })
        assertFalse(subject.rows()[0].online)
        clock = 1_000 + 10 * 60_000L
        assertTrue(subject.rows().isEmpty())
    }

    @Test
    fun aFreshBroadcastRevivesAnOfflineEntry() {
        val subject = registry()
        subject.report("udn-1", "客厅电视", "AMRC", "192.168.7.20")
        subject.markLost("udn-1")
        subject.report("udn-1", "客厅电视", "AMRC", "192.168.7.20")
        assertTrue(subject.rows()[0].online)
    }

    @Test
    fun orderingIsLastConnectedThenSameSubnetThenOnline() {
        val subject = registry()
        subject.report("far-online", "远端在线", "AMRC", "10.0.0.5")
        subject.report("same-offline", "同网离线", "AMRC", "192.168.7.9")
        subject.report("same-online", "同网在线", "DLNA", "192.168.7.8")
        subject.report("far-hit", "曾经连过", "Cast", "10.0.0.9")
        clock = 500
        subject.markConnected("far-hit")
        subject.markLost("same-offline")
        assertEquals(
            listOf("far-hit", "same-online", "far-online", "same-offline"),
            subject.rows().map { it.fingerprint },
        )
    }

    @Test
    fun emptyListDistinguishesNothingFoundFromAllOffline() {
        val subject = registry()
        assertEquals(DeviceRegistry.EmptyReason.NONE_FOUND, subject.emptyReason())
        subject.report("udn-1", "客厅电视", "AMRC", "192.168.7.20")
        assertEquals(DeviceRegistry.EmptyReason.HAS_ONLINE, subject.emptyReason())
        subject.markLost("udn-1")
        assertEquals(DeviceRegistry.EmptyReason.ALL_OFFLINE, subject.emptyReason())
        clock = 10 * 60_000L
        assertEquals(DeviceRegistry.EmptyReason.NONE_FOUND, subject.emptyReason())
    }

    @Test
    fun onlyANonBlankBroadcastNameOverwritesTheKnownOne() {
        val subject = registry()
        subject.report("udn-1", "客厅电视", "AMRC", "192.168.7.20")
        subject.report("udn-1", "", "DLNA", "192.168.7.20")
        assertEquals("客厅电视", subject.rows()[0].name)
    }

    @Test
    fun ownAnnouncementNeverBecomesARow() {
        val subject = registry()
        subject.selfFingerprint = "self-udn"
        // 同机双端：接收端的公告会回环到自己，列表里不该出现本机
        subject.report("self-udn", "这台电视", "AMRC", "192.168.7.10")
        assertTrue(subject.rows().isEmpty())
        assertEquals(DeviceRegistry.EmptyReason.NONE_FOUND, subject.emptyReason())
        // 邻居照常出现，过滤不能把整个网段一起挡掉
        subject.report("udn-1", "客厅电视", "AMRC", "192.168.7.20")
        assertEquals(1, subject.rows().size)
    }

    @Test
    fun aDirectConnectKeepsTheLastConnectedTimeVisibleAfterwards() {
        // 直接输 IP 时这台设备可能还没被任何一轮发现建出条目：
        // 必须先 report 再 markConnected，否则会话结束后卡片写「离线 · 还没连过」，与刚发生的事实相反
        val subject = registry()
        subject.report("am-direct", "手机", "AMRC", "192.168.7.9:41523")
        subject.markConnected("am-direct")
        clock = 60_000
        subject.markLost("am-direct")
        val row = subject.rows().single()
        assertEquals(0L, row.lastConnectedAt ?: -1L)
        assertFalse(row.online)
    }

    @Test
    fun fabricatedBroadcastsCannotGrowTheListWithoutBound() {
        // 局域网里任何人都能伪造广播：条目数必须有上限，否则一台刷屏设备就能把列表和内存撑爆
        val subject = registry()
        repeat(500) { index ->
            clock = index.toLong()
            subject.report("udn-$index", "设备$index", "DLNA", "192.168.7.${index % 250}")
        }
        assertEquals(64, subject.rows().size)
        // 淘汰的是最久没出现的那批，最后广播的设备仍在列表里
        assertTrue(subject.rows().any { it.fingerprint == "udn-499" })
        assertFalse(subject.rows().any { it.fingerprint == "udn-0" })
    }

    @Test
    fun repeatedAddressesOfOneFingerprintAreCapped() {
        // 同一指纹反复换源地址同样不能无限累加；连接时仍用最先到达的那个
        val subject = registry()
        repeat(100) { index -> subject.report("udn-1", "客厅电视", "DLNA", "192.168.7.$index") }
        assertEquals(1, subject.rows().size)
        assertEquals("192.168.7.0", subject.addressOf("udn-1"))
    }

    @Test
    fun protocolsOfOneDeviceAreCappedToo() {
        // 探测应答里的协议串来自任意 UDP 包：单个条目的协议集合也要封顶
        val subject = registry()
        repeat(20) { index -> subject.report("udn-1", "客厅电视", "P$index", "192.168.7.20") }
        assertEquals(8, subject.rows().single().protocols.size)
    }
}

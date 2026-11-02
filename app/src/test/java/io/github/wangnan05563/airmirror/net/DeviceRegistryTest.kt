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
}

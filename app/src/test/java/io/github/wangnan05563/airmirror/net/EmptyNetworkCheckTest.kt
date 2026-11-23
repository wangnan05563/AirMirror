package io.github.wangnan05563.airmirror.net

import io.github.wangnan05563.airmirror.error.ErrorNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T06106：空态四类状态、15 秒窗口、5 秒重试节奏与诊断数据。 */
class EmptyNetworkCheckTest {

    private val start = 1_000L
    private val wifi = EmptyNetworkCheck.Snapshot(link = EmptyNetworkCheck.Link.WIFI, localIp = "192.168.1.8")
    private val searching = DiscoveryLadder.Verdict.Nothing(DiscoveryLadder.Empty.SEARCHING, "")
    private val apIsolated = DiscoveryLadder.Verdict.Nothing(DiscoveryLadder.Empty.AP_ISOLATED, "ap")
    private val dualBand = DiscoveryLadder.Verdict.Nothing(DiscoveryLadder.Empty.DUAL_BAND, "dual")

    @Test
    fun `没有网络链路时先报未联网`() {
        val result = check(now = start + 20_000L, snapshot = EmptyNetworkCheck.Snapshot(link = EmptyNetworkCheck.Link.NONE))

        assertEquals(EmptyNetworkCheck.State.NO_NETWORK, result.state)
        assertEquals(ErrorNotice.Cause.NO_NETWORK, result.cause)
        assertEquals("AM-NET-04", result.code)
        assertEquals(
            listOf(ErrorNotice.Action.OPEN_SETTINGS, ErrorNotice.Action.RETRY_DISCOVERY),
            result.actions,
        )
    }

    @Test
    fun `十五秒前不提前断言没有接收端`() {
        val result = check(now = start + EmptyNetworkCheck.NO_RESULT_TIMEOUT_MS - 1L, snapshot = wifi)

        assertEquals(EmptyNetworkCheck.State.SEARCHING, result.state)
        assertNull(result.cause)
        assertEquals("AM-ND-00", result.code)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun `十五秒整进入没有发现接收端`() {
        val result = check(now = start + EmptyNetworkCheck.NO_RESULT_TIMEOUT_MS, snapshot = wifi)

        assertEquals(EmptyNetworkCheck.State.NO_RECEIVER, result.state)
        assertEquals(ErrorNotice.Cause.NO_RECEIVER, result.cause)
        assertEquals("AM-ND-02", result.code)
        assertEquals(
            listOf(ErrorNotice.Action.RETRY_DISCOVERY, ErrorNotice.Action.ENTER_CODE, ErrorNotice.Action.ENTER_IP),
            result.actions,
        )
    }

    @Test
    fun `已知设备全离线仍给设备离线原因`() {
        val result = check(
            now = start + EmptyNetworkCheck.NO_RESULT_TIMEOUT_MS,
            snapshot = wifi,
            registry = DeviceRegistry.EmptyReason.ALL_OFFLINE,
        )

        assertEquals(EmptyNetworkCheck.State.NO_RECEIVER, result.state)
        assertEquals(ErrorNotice.Cause.DEVICE_OFFLINE, result.cause)
        assertEquals("AM-OFF-01", result.code)
    }

    @Test
    fun `AP 隔离归到网络策略限制`() {
        val result = check(now = start + 5_000L, snapshot = wifi, verdict = apIsolated)

        assertEquals(EmptyNetworkCheck.State.RESTRICTED, result.state)
        assertEquals(ErrorNotice.Cause.DISCOVERY_AP_ISOLATED, result.cause)
        assertEquals("AM-NET-01", result.code)
    }

    @Test
    fun `双频分离归到网络策略限制`() {
        val result = check(now = start + 5_000L, snapshot = wifi, verdict = dualBand)

        assertEquals(EmptyNetworkCheck.State.RESTRICTED, result.state)
        assertEquals(ErrorNotice.Cause.DISCOVERY_DUAL_BAND, result.cause)
        assertEquals("AM-NET-02", result.code)
    }

    @Test
    fun `平台策略限制与运行时权限分开`() {
        val result = check(
            now = start + 5_000L,
            snapshot = wifi.copy(platformRestricted = true),
        )

        assertEquals(EmptyNetworkCheck.State.RESTRICTED, result.state)
        assertEquals(ErrorNotice.Cause.PERMISSION_NETWORK, result.cause)
        assertEquals("AM-PERM-01", result.code)
    }

    @Test
    fun `已发现接收端但配对失败优先于继续搜索`() {
        val result = check(
            now = start + 5_000L,
            snapshot = wifi.copy(foundReceivers = 1),
            pairingFailed = true,
        )

        assertEquals(EmptyNetworkCheck.State.PAIRING_FAILED, result.state)
        assertEquals(ErrorNotice.Cause.PAIRING_FAILED, result.cause)
        assertEquals("AM-PAIR-01", result.code)
        assertEquals(
            listOf(ErrorNotice.Action.RETRY_REQUEST, ErrorNotice.Action.ENTER_CODE),
            result.actions,
        )
    }

    @Test
    fun `已有在线接收端时不需要空态`() {
        val result = check(
            now = start + 1_000L,
            snapshot = wifi.copy(foundReceivers = 1),
            registry = DeviceRegistry.EmptyReason.HAS_ONLINE,
        )

        assertEquals(EmptyNetworkCheck.State.READY, result.state)
        assertNull(result.cause)
        assertEquals("", result.code)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun `诊断数据保留网络类型 IP 扫描时间和发现数量`() {
        val lastScan = start + 3_000L
        val result = check(
            now = start + 16_000L,
            snapshot = wifi.copy(lastScanAt = lastScan, foundReceivers = 0),
        )

        assertEquals(EmptyNetworkCheck.Link.WIFI, result.diagnostic.link)
        assertEquals("192.168.1.8", result.diagnostic.localIp)
        assertEquals(lastScan, result.diagnostic.lastScanAt)
        assertEquals(0, result.diagnostic.foundReceivers)
        assertEquals(16_000L, result.diagnostic.elapsedMs)
    }

    @Test
    fun `五秒重试节奏可手动停止`() {
        assertTrue("首次扫描允许立即开始", EmptyNetworkCheck.retryDue(start, null))
        assertFalse("未满五秒不自动重试", EmptyNetworkCheck.retryDue(start + 4_999L, start))
        assertTrue("满五秒允许自动重试", EmptyNetworkCheck.retryDue(start + 5_000L, start))
        assertTrue("时钟回拨也允许重试", EmptyNetworkCheck.retryDue(start, start + 10_000L))
    }

    private fun check(
        now: Long,
        snapshot: EmptyNetworkCheck.Snapshot,
        verdict: DiscoveryLadder.Verdict? = searching,
        registry: DeviceRegistry.EmptyReason = DeviceRegistry.EmptyReason.NONE_FOUND,
        pairingFailed: Boolean = false,
    ): EmptyNetworkCheck.Result = EmptyNetworkCheck.check(
        now = now,
        startedAt = start,
        snapshot = snapshot,
        verdict = verdict,
        registry = registry,
        pairingFailed = pairingFailed,
    )
}

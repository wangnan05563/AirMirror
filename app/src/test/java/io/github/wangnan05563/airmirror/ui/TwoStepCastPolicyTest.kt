package io.github.wangnan05563.airmirror.ui

import io.github.wangnan05563.airmirror.net.DeviceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T06105 状态机回归：自动选中与“两台在线必须列表”的边界全部锁在这里。 */
class TwoStepCastPolicyTest {

    private fun candidate(
        fingerprint: String,
        name: String = "电视",
        online: Boolean = true,
        supported: Boolean = true,
    ) = TwoStepCastPolicy.Candidate(fingerprint, name, online, supported)

    private fun input(
        rows: List<TwoStepCastPolicy.Candidate>,
        trusted: TwoStepCastPolicy.Trusted? = TwoStepCastPolicy.Trusted("am-a", NOW - 60_000L),
        settled: Boolean = true,
        userChose: Boolean = false,
        attempted: Boolean = false,
        connecting: Boolean = false,
        casting: Boolean = false,
        now: Long = NOW,
    ) = TwoStepCastPolicy.Input(rows, trusted, now, settled, userChose, attempted, connecting, casting)

    @Test
    fun `唯一在线且是上次成功设备才自动选中`() {
        assertEquals(
            "am-a",
            TwoStepCastPolicy.autoTarget(input(listOf(candidate("am-a"))))?.fingerprint,
        )
    }

    @Test
    fun `两台在线即使其中一台是上次设备也必须列表`() {
        val rows = listOf(candidate("am-a"), candidate("am-b"))
        assertNull("多设备不许静默选错电视", TwoStepCastPolicy.autoTarget(input(rows)))
    }

    @Test
    fun `唯一在线但不是上次设备不自动`() {
        assertNull(TwoStepCastPolicy.autoTarget(input(listOf(candidate("am-b")))))
    }

    @Test
    fun `唯一在线但能力不支持不自动`() {
        assertNull(TwoStepCastPolicy.autoTarget(input(listOf(candidate("am-a", supported = false)))))
    }

    @Test
    fun `发现未稳定或用户已动手时不自动`() {
        assertNull(TwoStepCastPolicy.autoTarget(input(listOf(candidate("am-a")), settled = false)))
        assertNull(TwoStepCastPolicy.autoTarget(input(listOf(candidate("am-a")), userChose = true)))
        assertNull(TwoStepCastPolicy.autoTarget(input(listOf(candidate("am-a")), attempted = true)))
        assertNull(TwoStepCastPolicy.autoTarget(input(listOf(candidate("am-a")), connecting = true)))
        assertNull(TwoStepCastPolicy.autoTarget(input(listOf(candidate("am-a")), casting = true)))
    }

    @Test
    fun `信任记录超过三十天不再自动`() {
        val stale = TwoStepCastPolicy.Trusted("am-a", NOW - 31L * 24 * 60 * 60 * 1000)
        assertNull(TwoStepCastPolicy.autoTarget(input(listOf(candidate("am-a")), trusted = stale)))
    }

    @Test
    fun `默认入口跟相机可用性走`() {
        assertEquals(TwoStepCastPolicy.EntryMode.SCAN, TwoStepCastPolicy.defaultMode(true))
        assertEquals(TwoStepCastPolicy.EntryMode.CODE, TwoStepCastPolicy.defaultMode(false))
    }

    @Test
    fun `在线设备出现后扫码让位给列表`() {
        val online = DeviceRegistry.Row("am-a", "电视", listOf("AMRC"), true, null)
        assertFalse(TwoStepCastPolicy.scanEntryVisible(listOf(online), TwoStepCastPolicy.EntryMode.SCAN))
        assertTrue(TwoStepCastPolicy.scanEntryVisible(emptyList(), TwoStepCastPolicy.EntryMode.SCAN))
        assertFalse(TwoStepCastPolicy.scanEntryVisible(emptyList(), TwoStepCastPolicy.EntryMode.CODE))
    }

    @Test
    fun `决策预算只允许两次并可在失败后重置`() {
        val budget = TwoStepCastPolicy.Budget()
        assertTrue(budget.take())
        assertEquals(1, budget.used)
        assertTrue(budget.take())
        assertEquals(0, budget.remaining)
        assertFalse("第三次主动决策必须被挡住", budget.take())
        budget.reset()
        assertEquals(0, budget.used)
        assertTrue(budget.take())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

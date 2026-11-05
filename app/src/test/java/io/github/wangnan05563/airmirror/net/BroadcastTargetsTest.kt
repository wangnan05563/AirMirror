package io.github.wangnan05563.airmirror.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 受控广播地址：只在有掩码的普通 IPv4 网段上算定向广播。 */
class BroadcastTargetsTest {

    @Test
    fun `24 位掩码算出网段定向广播`() {
        assertEquals(
            "192.168.1.255",
            BroadcastTargets.directedBroadcast(BroadcastTargets.Interface("192.168.1.23", 24)),
        )
        assertEquals(
            "10.255.255.255",
            BroadcastTargets.directedBroadcast(BroadcastTargets.Interface("10.1.2.3", 8)),
        )
    }

    @Test
    fun `没有广播可言的地址跳过`() {
        assertNull(BroadcastTargets.directedBroadcast(BroadcastTargets.Interface("192.168.1.23", 31)))
        assertNull(BroadcastTargets.directedBroadcast(BroadcastTargets.Interface("192.168.1.23", 32)))
        assertNull(BroadcastTargets.directedBroadcast(BroadcastTargets.Interface("169.254.4.5", 16)))
        assertNull(BroadcastTargets.directedBroadcast(BroadcastTargets.Interface("192.168.1.999", 24)))
        assertNull(BroadcastTargets.directedBroadcast(BroadcastTargets.Interface("192.168.1", 24)))
    }

    @Test
    fun `全局广播打头 定向广播去重`() {
        val targets = BroadcastTargets.of(
            listOf(
                BroadcastTargets.Interface("192.168.1.23", 24),
                BroadcastTargets.Interface("192.168.1.88", 24),
                BroadcastTargets.Interface("10.0.0.9", 8),
            ),
        )
        assertEquals(BroadcastTargets.GLOBAL, targets.first())
        assertEquals(listOf("255.255.255.255", "192.168.1.255", "10.255.255.255"), targets)
        assertTrue("关掉全局广播时只剩定向", BroadcastTargets.of(
            listOf(BroadcastTargets.Interface("192.168.1.23", 24)), includeGlobal = false,
        ) == listOf("192.168.1.255"))
    }
}

package io.github.wangnan05563.airmirror.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T06105：发送端「上次成功投屏」记录必须可落盘、可容错，过期后不再参与自动选中。 */
class LastCastTargetTest {

    @Test
    fun `记录可往返且名字不破坏分隔符`() {
        val record = LastCastTarget.Record("am-abc123", "客厅|电视", 1_700_000_000_000L)
        val encoded = LastCastTarget.encode(record)
        assertEquals(record, LastCastTarget.decode(encoded))
        assertFalse("名字不能以原样落盘", encoded.contains("客厅|电视"))
    }

    @Test
    fun `损坏的记录返回 null 而不是猜一台设备`() {
        assertNull(LastCastTarget.decode(""))
        assertNull(LastCastTarget.decode("am-abc|zz|123"))
        assertNull(LastCastTarget.decode("am-abc|41|not-a-time"))
        assertNull(LastCastTarget.decode("am-abc|41|-1"))
        assertNull(LastCastTarget.decode("|41|123"))
    }

    @Test
    fun `超过三十天才过期且时钟回拨不误杀`() {
        val now = 1_700_000_000_000L
        val fresh = LastCastTarget.Record("am-abc", "电视", now - 10_000L)
        assertTrue(fresh.fresh(now))
        assertFalse(fresh.fresh(now + LastCastTarget.DEFAULT_TTL + 1))
        val clockBack = LastCastTarget.Record("am-abc", "电视", now + 60_000L)
        assertTrue("系统时间回拨不应让记录立即失效", clockBack.fresh(now))
    }
}

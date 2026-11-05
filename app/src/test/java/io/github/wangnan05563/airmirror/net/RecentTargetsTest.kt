package io.github.wangnan05563.airmirror.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FS-07 缓存：10 分钟有效、过期不再置顶但仍可搜索、按指纹覆盖、落盘回环。 */
class RecentTargetsTest {

    private val t0 = 100_000L

    @Test
    fun `最近连过的排前面 同指纹换地址只留一条`() {
        val cache = RecentTargets()
        cache.record("am-a", "客厅", "192.168.1.5", 41523, t0)
        cache.record("am-b", "卧室", "192.168.1.6", 41523, t0 + 100)
        assertEquals(listOf("am-b", "am-a"), cache.viable(t0 + 200).map { it.fingerprint })
        cache.record("am-a", "客厅", "192.168.1.9", 41523, t0 + 300)
        assertEquals(2, cache.all().size)
        assertEquals("192.168.1.9", cache.viable(t0 + 400).first { it.fingerprint == "am-a" }.host)
    }

    @Test
    fun `过期退出探测与置顶 记录本身仍留着`() {
        val cache = RecentTargets(ttlMillis = 1_000L)
        cache.record("am-a", "客厅", "192.168.1.5", 41523, t0)
        assertTrue(cache.viable(t0 + 999).isNotEmpty())
        assertTrue(cache.viable(t0 + 1_000).isEmpty())
        assertEquals(1, cache.all().size)
        cache.prune(t0 + 1_000)
        assertEquals("过期后 6 倍 TTL 才真删", 1, cache.all().size)
        cache.prune(t0 + 6_000)
        assertEquals(0, cache.all().size)
    }

    @Test
    fun `LRU 上限 8 条`() {
        val cache = RecentTargets(capacity = 2)
        cache.record("a", "A", "192.168.1.1", 41523, t0)
        cache.record("b", "B", "192.168.1.2", 41523, t0 + 1)
        cache.record("c", "C", "192.168.1.3", 41523, t0 + 2)
        assertEquals(listOf("c", "b"), cache.viable(t0 + 3).map { it.fingerprint })
        assertEquals(2, cache.all().size)
    }

    @Test
    fun `中文设备名落盘回环不破帧`() {
        val cache = RecentTargets()
        cache.record("am-1", "客厅 电视|盒子", "192.168.1.5", 41523, t0)
        val restored = RecentTargets.decode(cache.encode())
        assertEquals("客厅 电视|盒子", restored.all().single().name)
        assertEquals("192.168.1.5", restored.all().single().host)
        assertEquals(t0, restored.all().single().lastConnectedAt)
    }

    @Test
    fun `只按还在有效期内的缓存判断跨网段`() {
        val cache = RecentTargets(ttlMillis = 1_000L)
        cache.record("am-a", "客厅", "192.168.2.5", 41523, t0)
        assertTrue(cache.crossSubnet(t0 + 100, "192.168.1"))
        assertFalse(cache.crossSubnet(t0 + 100, "192.168.2"))
        assertFalse("过期老网段不能拿来给今天的失败找借口", cache.crossSubnet(t0 + 1_100, "192.168.1"))
        assertFalse(cache.crossSubnet(t0, null))
    }

    @Test
    fun `坏数据不入缓存`() {
        val cache = RecentTargets()
        cache.record("", "X", "192.168.1.5", 41523, t0)
        cache.record("am-a", "X", "", 41523, t0)
        cache.record("am-b", "X", "192.168.1.5", 0, t0)
        assertTrue(cache.all().isEmpty())
        assertTrue(RecentTargets.decode("bad|line\nam-zz|zz|192.168.1.5|nan|123").all().isEmpty())
    }
}

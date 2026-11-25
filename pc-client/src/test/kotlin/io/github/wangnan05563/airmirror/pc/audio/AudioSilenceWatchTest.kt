package io.github.wangnan05563.airmirror.pc.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连续静音看门狗口径（PRD v2.0「DRM/独占模式无法捕获时明确提示，不静默失败」）：
 * 只证明「静音计时/只提示一次/有声即清零」，不代表真实 DRM 已现场验证。
 */
class AudioSilenceWatchTest {

    @Test
    fun `silence shorter than the hint window stays quiet`() {
        val watch = AudioSilenceWatch(hintAfterMs = 10_000L)
        assertFalse(watch.onSilent(nowMs = 1_000L))
        assertFalse(watch.onSilent(nowMs = 10_999L))
    }

    @Test
    fun `continuous silence reaches the hint exactly once`() {
        val watch = AudioSilenceWatch(hintAfterMs = 10_000L)
        assertFalse(watch.onSilent(nowMs = 1_000L))
        assertTrue(watch.onSilent(nowMs = 11_000L))
        // 用户没处理前每 100ms 都来一个静音包，不能每包刷一条提示
        assertFalse(watch.onSilent(nowMs = 11_100L))
        assertFalse(watch.onSilent(nowMs = 30_000L))
    }

    @Test
    fun `a single sound packet clears the watch`() {
        val watch = AudioSilenceWatch(hintAfterMs = 10_000L)
        assertFalse(watch.onSilent(nowMs = 1_000L))
        watch.onSound()
        // 计时从新生效，不能把恢复前的那 9 秒带进来
        assertFalse(watch.onSilent(nowMs = 10_000L))
        assertTrue(watch.onSilent(nowMs = 20_000L))
    }

    @Test
    fun `reset forgets both timing and the one-shot flag`() {
        val watch = AudioSilenceWatch(hintAfterMs = 10_000L)
        assertFalse(watch.onSilent(nowMs = 0L))
        assertTrue(watch.onSilent(nowMs = 10_000L))
        watch.reset()
        assertFalse(watch.onSilent(nowMs = 20_000L))
        assertTrue(watch.onSilent(nowMs = 30_000L))
    }

    @Test
    fun `silent duration is observable for diagnostics`() {
        val watch = AudioSilenceWatch(hintAfterMs = 10_000L)
        assertEquals(0L, watch.silentForMs(nowMs = 5_000L))
        watch.onSilent(nowMs = 5_000L)
        assertEquals(2_500L, watch.silentForMs(nowMs = 7_500L))
        watch.onSound()
        assertEquals(0L, watch.silentForMs(nowMs = 8_000L))
    }
}

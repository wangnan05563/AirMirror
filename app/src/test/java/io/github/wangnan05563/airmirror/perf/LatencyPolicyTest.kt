package io.github.wangnan05563.airmirror.perf

import io.github.wangnan05563.airmirror.device.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyLadderTest {

    @Test
    fun `frame rate is halved before the resolution moves`() {
        val ladder = LatencyLadder(QualityTier.FHD60)
        assertEquals(
            listOf("1080p60@60", "1080p60@30", "1080p60@15", "720p30@15", "480p@15"),
            ladder.steps.map { it.text() },
        )
    }

    @Test
    fun `a tier already at the fps floor goes straight to resolution`() {
        val ladder = LatencyLadder(QualityTier.HD30)
        assertEquals(listOf("720p30@30", "720p30@15", "480p@15"), ladder.steps.map { it.text() })
    }

    @Test
    fun `4k ladder keeps resolution steps after the fps floor`() {
        val ladder = LatencyLadder(QualityTier.UHD60)
        assertEquals(7, ladder.steps.size)
        assertEquals(QualityTier.UHD60, ladder.steps[2].tier)
        assertEquals(QualityTier.QHD, ladder.steps[3].tier)
        assertEquals(15, ladder.steps[3].fps)
        assertEquals(QualityTier.SD480, ladder.steps.last().tier)
    }

    @Test
    fun `unusable base has a single step so nothing can be lowered further`() {
        assertEquals(1, LatencyLadder(QualityTier.OFF).steps.size)
    }

    @Test
    fun `index outside the ladder is clamped instead of throwing`() {
        val ladder = LatencyLadder(QualityTier.FHD60)
        assertEquals(ladder.step(0), ladder.step(-5))
        assertEquals(ladder.steps.last(), ladder.step(99))
    }

    @Test
    fun `the bandwidth jump lands on the first rung at or below the sustained tier`() {
        val ladder = LatencyLadder(QualityTier.FHD60)
        // 阶梯是 1080p60/60 → 1080p60/30 → 1080p60/15 → 720p30/15 → 480p/15
        assertEquals(3, ladder.indexOfTierAtOrBelow(QualityTier.HD30))
        assertEquals(4, ladder.indexOfTierAtOrBelow(QualityTier.SD480))
        assertEquals(0, ladder.indexOfTierAtOrBelow(QualityTier.FHD60))
        // 基线本身就已经不比目标档好：落在第 0 档，调用方按「不必跳」处理
        assertEquals(0, ladder.indexOfTierAtOrBelow(QualityTier.QHD))
        assertEquals(-1, ladder.indexOfTierAtOrBelow(QualityTier.OFF))
    }
}

class LatencyPolicyTest {

    private val fullLadder = LatencyLadder(QualityTier.FHD60)

    private fun window(p90: Long, frames: Long = 30L, clockSkew: Long = 0L, over: Long = 0L) =
        LatencyWindow(frames, p50Ms = p90 / 2, p90Ms = p90, averageMs = p90 / 2, maxMs = p90 + 10, overWindow = over, clockSkew = clockSkew)

    /** 策略每次判定都要拿到阶梯长度，测试统一按完整阶梯喂 */
    private fun LatencyPolicy.feed(window: LatencyWindow, ladder: LatencyLadder = fullLadder) = onWindow(window, ladder.steps.size)

    /** 降档要连两窗超标才表态，锁定的「只提示」同样要两窗，按一次超标周期喂 */
    private fun LatencyPolicy.badCycle(): LatencyDecision {
        feed(window(400))
        return feed(window(400))
    }

    @Test
    fun `one bad window is not enough to touch the picture`() {
        val policy = LatencyPolicy()
        assertEquals(LatencyMove.KEEP, policy.feed(window(400)).move)
        assertEquals(0, policy.stepIndex)
    }

    @Test
    fun `two bad windows drop the first rung`() {
        val policy = LatencyPolicy()
        policy.feed(window(400))
        val decision = policy.feed(window(400))
        assertEquals(LatencyMove.DEGRADE, decision.move)
        assertEquals(1, decision.stepIndex)
        assertEquals("1080p60@30", fullLadder.step(decision.stepIndex).text())
    }

    @Test
    fun `windows right after a move are ignored so the new pipeline can settle`() {
        val policy = LatencyPolicy()
        policy.feed(window(400))
        assertEquals(LatencyMove.DEGRADE, policy.feed(window(400)).move)
        repeat(4) { assertEquals(LatencyMove.KEEP, policy.feed(window(400)).move) }
        // 静默期过后再要两窗才动第二档
        assertEquals(LatencyMove.KEEP, policy.feed(window(400)).move)
        val second = policy.feed(window(400))
        assertEquals(LatencyMove.DEGRADE, second.move)
        assertEquals(2, second.stepIndex)
    }

    @Test
    fun `the floor holds the picture instead of killing the stream`() {
        val policy = LatencyPolicy()
        var floorKept = 0
        var moves = 0
        repeat(40) {
            val decision = policy.feed(window(400))
            assertTrue(decision.reason, decision.stepIndex <= fullLadder.steps.size - 1)
            if (decision.move != LatencyMove.KEEP) moves++
            if (decision.reason.contains("保画面")) floorKept++
        }
        assertEquals(fullLadder.steps.size - 1, policy.stepIndex)
        assertEquals("只降到底为止", fullLadder.steps.size - 1, moves)
        assertTrue("最低档要反复表态保持", floorKept > 0)
    }

    @Test
    fun `samples that cannot be trusted never trigger a move`() {
        val policy = LatencyPolicy()
        repeat(20) {
            assertEquals(LatencyMove.KEEP, policy.feed(window(400, clockSkew = 30)).move)
            assertEquals(LatencyMove.KEEP, policy.feed(window(400, frames = 5)).move)
        }
        assertEquals(0, policy.stepIndex)
    }

    @Test
    fun `p90 inside the target but with most frames outside the histogram is still over`() {
        val policy = LatencyPolicy()
        assertEquals(LatencyMove.KEEP, policy.feed(window(100, over = 20)).move)
        assertEquals(LatencyMove.DEGRADE, policy.feed(window(100, over = 20)).move)
    }

    @Test
    fun `recovery climbs one rung only after a long clean streak`() {
        val policy = LatencyPolicy()
        policy.feed(window(400))
        policy.feed(window(400))
        assertEquals(1, policy.stepIndex)
        repeat(4) { policy.feed(window(400)) }
        repeat(11) { assertEquals(LatencyMove.KEEP, policy.feed(window(80)).move) }
        val up = policy.feed(window(80))
        assertEquals(LatencyMove.RECOVER, up.move)
        assertEquals(0, up.stepIndex)
    }

    @Test
    fun `clean streak at the baseline does not pretend to recover`() {
        val policy = LatencyPolicy()
        repeat(30) { assertEquals(LatencyMove.KEEP, policy.feed(window(80)).move) }
        assertEquals(0, policy.stepIndex)
    }

    @Test
    fun `a shorter ladder pulls an over-deep step back`() {
        val policy = LatencyPolicy()
        repeat(8) { policy.feed(window(400)) }
        assertEquals(2, policy.stepIndex)
        // 本机上限被降过之后阶梯变短，当前档不能悬在序列外
        val short = LatencyLadder(QualityTier.SD480)
        assertEquals(2, short.steps.size)
        policy.feed(window(400), short)
        assertEquals(short.steps.size - 1, policy.stepIndex)
        policy.reset()
        assertEquals(0, policy.stepIndex)
    }

    @Test
    fun `rebuilding the ladder after a resolution drop keeps the remaining rungs`() {
        val policy = LatencyPolicy()
        repeat(14) { policy.feed(window(400)) }
        assertEquals("1080p60@60 → @30 → @15 → 720p30@15", 3, policy.stepIndex)
        // 对端按新的 720p 发来 CONFIG：阶梯以实际推流档位重建，当前档要落在同一档上
        val rebuilt = LatencyLadder(QualityTier.HD30)
        val current = fullLadder.step(policy.stepIndex)
        assertEquals(1, rebuilt.indexNearest(current.tier, current.fps))
        policy.anchorAt(rebuilt.indexNearest(current.tier, current.fps))
        assertEquals(1, policy.stepIndex)
        // 这样下一刀才是 480p，而不是「已经到底」
        policy.feed(window(400))
        val down = policy.feed(window(400), rebuilt)
        assertEquals(LatencyMove.DEGRADE, down.move)
        assertEquals(2, down.stepIndex)
        assertEquals("480p@15", rebuilt.step(2).text())
    }

    @Test
    fun `a ladder rebuilt above the current quality starts at its own baseline`() {
        val rebuilt = LatencyLadder(QualityTier.HD30)
        assertEquals(0, rebuilt.indexNearest(QualityTier.FHD60, 60))
        assertEquals(2, rebuilt.indexNearest(QualityTier.SD480, 15))
        assertEquals(1, rebuilt.indexNearest(QualityTier.HD30, 60))
    }

    @Test
    fun `target matches the 1080p60 requirement`() {
        assertEquals(150L, LatencyPolicy.DEFAULT_TARGET_P90_MS)
        val policy = LatencyPolicy()
        assertEquals(LatencyMove.KEEP, policy.feed(window(150)).move)
        assertEquals(LatencyMove.KEEP, policy.feed(window(151)).move)
        assertEquals(LatencyMove.DEGRADE, policy.feed(window(151)).move)
    }

    @Test
    fun `a locked tier warns instead of moving the picture`() {
        val policy = LatencyPolicy()
        policy.locked = true
        assertEquals(LatencyMove.KEEP, policy.feed(window(400)).move)
        val decision = policy.feed(window(400))
        assertEquals(LatencyMove.NOTIFY, decision.move)
        assertEquals("锁定后画质一格都不能动", 0, policy.stepIndex)
        assertEquals(0, decision.stepIndex)
        assertTrue(decision.reason, decision.reason.contains("锁定"))
    }

    @Test
    fun `the lock warning goes quiet so the overlay is not retriggered every window`() {
        val policy = LatencyPolicy()
        policy.locked = true
        assertEquals(LatencyMove.NOTIFY, policy.badCycle().move)
        repeat(LatencyPolicy.NOTIFY_QUIET_WINDOWS) {
            assertEquals("静默期内只保持，不再提示", LatencyMove.KEEP, policy.badCycle().move)
        }
        assertEquals(LatencyMove.NOTIFY, policy.badCycle().move)
        assertEquals("锁定期间画质一格都没动", 0, policy.stepIndex)
    }

    @Test
    fun `a locked tier never climbs back on its own`() {
        val policy = LatencyPolicy()
        repeat(2) { policy.feed(window(400)) }
        assertEquals(1, policy.stepIndex)
        policy.locked = true
        repeat(4) { policy.feed(window(80)) }
        repeat(20) { assertEquals(LatencyMove.KEEP, policy.feed(window(80)).move) }
        assertEquals(1, policy.stepIndex)
        // 取消锁定后同一批达标窗口就该回升：锁的是动作，不是观察
        policy.locked = false
        repeat(2) { policy.feed(window(80)) }
        assertEquals(0, policy.stepIndex)
    }

    @Test
    fun `reaching the picture again clears the notify quiet`() {
        val policy = LatencyPolicy()
        policy.locked = true
        assertEquals(LatencyMove.NOTIFY, policy.badCycle().move)
        repeat(2) { policy.feed(window(80)) }
        // 达标一窗就拆掉静默：画面好转后再次卡住要立刻说，不能让用户等满静默期
        assertEquals(LatencyMove.NOTIFY, policy.badCycle().move)
        // 重新锚定（换档/带宽直跳之后）同样拆掉静默
        policy.anchorAt(0)
        assertEquals(LatencyMove.NOTIFY, policy.badCycle().move)
    }
}

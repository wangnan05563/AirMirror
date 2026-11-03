package io.github.wangnan05563.airmirror.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音画同步口径（PRD FR-09 + FS-06「同步参数」）的数字都在这张表上钉住：
 * 缓冲区间的刹车、单次调整上限、硬对齐阈值与「连续三次降档」的计数。
 */
class AvSyncTest {

    @Test
    fun `a late sound track catches up within the single-step cap`() {
        val buffer = AvSync.correction(driftMs = 260, levelMs = 60)
        assertEquals(AvSync.BufferMove.CATCH_UP, buffer.move)
        // 偏差 260ms 超容忍区 180ms，但单次最多只动 10ms，否则一听就是变速
        assertEquals(AvSync.MAX_ADJUST_MS, buffer.adjustMs)
    }

    @Test
    fun `catching up stops at the buffer floor`() {
        // 水位已经压到区间下限，再抽帧就把缓冲抽穿，声音出现空洞
        assertEquals(AvSync.BufferMove.HOLD, AvSync.correction(driftMs = 400, levelMs = AvSync.MIN_BUFFER_MS).move)
    }

    @Test
    fun `an early sound track is stretched only while the buffer has room`() {
        val stretch = AvSync.correction(driftMs = -120, levelMs = 50)
        assertEquals(AvSync.BufferMove.STRETCH, stretch.move)
        assertEquals(AvSync.MAX_ADJUST_MS, stretch.adjustMs)
        // 垫到区间上限就不再垫：垫静音本身就是在加延迟
        assertEquals(AvSync.BufferMove.HOLD, AvSync.correction(driftMs = -120, levelMs = AvSync.MAX_BUFFER_MS).move)
    }

    @Test
    fun `inside the tolerance band nothing moves`() {
        assertEquals(AvSync.BufferMove.HOLD, AvSync.correction(driftMs = 80, levelMs = 60).move)
        assertEquals(AvSync.BufferMove.HOLD, AvSync.correction(driftMs = -80, levelMs = 60).move)
    }

    @Test
    fun `sub-frame trims are banked until a whole frame is owed`() {
        val pacer = AvSync.Pacer()
        val buffer = AvSync.correction(driftMs = 90, levelMs = 60)
        // 单次只让动 10ms，而一帧约 21ms：连着两拍都不该真丢帧
        assertEquals(AvSync.Pacer.Action.NONE, pacer.onBuffer(buffer).action)
        assertEquals(AvSync.Pacer.Action.NONE, pacer.onBuffer(buffer).action)
        // 第三拍攒够 30ms 才丢一个整帧，抽出来的不是「一串洞」
        val move = pacer.onBuffer(buffer)
        assertEquals(AvSync.Pacer.Action.SKIP, move.action)
        assertEquals(1, move.amount)
    }

    @Test
    fun `a hold clears the banked debt`() {
        val pacer = AvSync.Pacer()
        pacer.onBuffer(AvSync.correction(driftMs = 90, levelMs = 60))
        pacer.onBuffer(AvSync.correction(driftMs = 90, levelMs = 60))
        pacer.onBuffer(AvSync.Buffer(AvSync.BufferMove.HOLD, 0))
        // 已经回到容忍区，之前欠的不能在下一次超区时立刻兑现
        assertEquals(AvSync.Pacer.Action.NONE, pacer.onBuffer(AvSync.correction(driftMs = 90, levelMs = 60)).action)
    }

    @Test
    fun `a late non key frame is dropped but a late key frame renders`() {
        val clock = 1_000_000L
        val late = clock - (AvSync.TOLERANCE_MS + 20) * 1_000L
        assertEquals(AvSync.VideoAction.DROP, AvSync.videoAction(late, clock, keyFrame = false))
        assertEquals(AvSync.VideoAction.RENDER, AvSync.videoAction(late, clock, keyFrame = true))
    }

    @Test
    fun `an early frame waits and an in-band frame renders`() {
        val clock = 1_000_000L
        assertEquals(
            AvSync.VideoAction.WAIT,
            AvSync.videoAction(clock + (AvSync.TOLERANCE_MS + 20) * 1_000L, clock, keyFrame = false),
        )
        assertEquals(AvSync.VideoAction.RENDER, AvSync.videoAction(clock + 50_000L, clock, keyFrame = false))
    }

    @Test
    fun `micro adjust below the hard align threshold`() {
        val tracker = AvSync.Tracker()
        assertEquals(AvSync.Action.MICRO_ADJUST, tracker.onDrift(81, nowMs = 0L))
        assertEquals(AvSync.Action.NONE, tracker.onDrift(80, nowMs = 0L))
        assertEquals(AvSync.Action.HARD_ALIGN, tracker.onDrift(300, nowMs = 0L))
    }

    @Test
    fun `three consecutive hard aligns downgrade the tier`() {
        val tracker = AvSync.Tracker()
        // 每次跳变都要给足起效时间，「连续三次」数的是真试过三次
        assertEquals(AvSync.Action.HARD_ALIGN, tracker.onDrift(320, nowMs = 0L))
        assertEquals(AvSync.Action.HARD_ALIGN, tracker.onDrift(310, nowMs = 1_000L))
        assertEquals(AvSync.Action.DOWNGRADE, tracker.onDrift(305, nowMs = 2_000L))
        // 降档后重新起算，不能攒着次数下一次立刻再降
        assertEquals(AvSync.Action.HARD_ALIGN, tracker.onDrift(400, nowMs = 3_000L))
    }

    @Test
    fun `a hard align that has not had time to work does not fire again`() {
        val tracker = AvSync.Tracker()
        assertEquals(AvSync.Action.HARD_ALIGN, tracker.onDrift(320, nowMs = 0L))
        // 采样每 100ms 一拍，偏差不会一跳变就好；连着抽声音只会把声音切成卡顿
        assertEquals(AvSync.Action.NONE, tracker.onDrift(330, nowMs = 100L))
        assertEquals(AvSync.Action.NONE, tracker.onDrift(340, nowMs = 999L))
        assertEquals(1, tracker.hardAlignStreak)
        assertEquals(AvSync.Action.HARD_ALIGN, tracker.onDrift(340, nowMs = 1_000L))
        assertEquals(2, tracker.hardAlignStreak)
    }

    @Test
    fun `getting back in band breaks the hard align streak`() {
        val tracker = AvSync.Tracker()
        tracker.onDrift(320, nowMs = 0L)
        tracker.onDrift(20, nowMs = 200L)
        assertEquals(0, tracker.hardAlignStreak)
        assertEquals(AvSync.State.SYNCED, tracker.state)
        // 冷却跟着一起清零，回区后再掉出去不该多等一秒
        assertEquals(AvSync.Action.HARD_ALIGN, tracker.onDrift(320, nowMs = 300L))
        assertEquals(1, tracker.hardAlignStreak)
    }

    @Test
    fun `resync that misses the one second window is recorded`() {
        val tracker = AvSync.Tracker()
        tracker.onResync(nowMs = 1_000L)
        assertEquals(AvSync.State.RESYNC, tracker.state)
        tracker.onDrift(150, nowMs = 1_500L)
        assertFalse(tracker.resyncMissed)
        // 超出 1 秒仍未回到容忍区（PRD「恢复后 1 秒内回到容忍区」）
        tracker.onDrift(150, nowMs = 2_100L)
        assertTrue(tracker.resyncMissed)
        tracker.onDrift(10, nowMs = 2_200L)
        assertEquals(AvSync.State.SYNCED, tracker.state)
        assertTrue(tracker.resyncMissed)
    }

    @Test
    fun `a clock jump re-anchors silently`() {
        val tracker = AvSync.Tracker()
        tracker.onDrift(1_000, nowMs = 0L)
        tracker.onClockJump(nowMs = 100L)
        assertEquals(AvSync.State.RESYNC, tracker.state)
        // 重新对齐窗口从跳变那一刻起算 1 秒，且不打扰用户
        assertEquals(AvSync.RESYNC_WINDOW_MS.toLong(), tracker.resyncDeadlineMs - 100L)
    }

    @Test
    fun `one second of silence counts as an interrupted track`() {
        assertFalse(AvSync.audioInterrupted(lastAudioAtMs = 0L, nowMs = 10_000L))
        assertFalse(AvSync.audioInterrupted(1_000L, 1_999L))
        assertTrue(AvSync.audioInterrupted(1_000L, 2_001L))
    }
}

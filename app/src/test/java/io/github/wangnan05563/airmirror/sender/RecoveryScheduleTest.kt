package io.github.wangnan05563.airmirror.sender

import io.github.wangnan05563.airmirror.error.ErrorNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FS-10 重试节奏：3 次铺在 30 秒窗口内，收口原因分「重连失败」和「超时」。 */
class RecoveryScheduleTest {

    @Test
    fun `three attempts stay inside the thirty second window`() {
        assertEquals(3, RecoverySchedule.ATTEMPTS)
        assertEquals(30_000L, RecoverySchedule.WINDOW_MS)
        assertEquals(0L, RecoverySchedule.delayFor(0))
        for (attempt in 0 until RecoverySchedule.ATTEMPTS) {
            assertTrue(
                "attempt ${attempt + 1} starts at ${RecoverySchedule.delayFor(attempt)}ms",
                RecoverySchedule.delayFor(attempt) < RecoverySchedule.WINDOW_MS,
            )
        }
        assertTrue(RecoverySchedule.delayFor(1) < RecoverySchedule.delayFor(2))
    }

    @Test
    fun `finishing the attempts early reports reconnect exhaustion`() {
        assertEquals(
            ErrorNotice.Cause.RECONNECT_EXHAUSTED,
            RecoverySchedule.exhaustedCause(25_000L),
        )
    }

    @Test
    fun `dragging to the window limit reports a timeout`() {
        assertEquals(ErrorNotice.Cause.RECOVERY_TIMEOUT, RecoverySchedule.exhaustedCause(30_000L))
        assertEquals(ErrorNotice.Cause.RECOVERY_TIMEOUT, RecoverySchedule.exhaustedCause(31_200L))
    }
}
package io.github.wangnan05563.airmirror.dlna

import io.github.wangnan05563.airmirror.error.ErrorNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MediaPlayer 错误码到自解释提示的映射（PRD 7.3）。
 * 取证里真实出现的组合是 (what=1, extra=Int.MIN_VALUE)，也就是「拿不到元数据」这一大类，
 * 它必须落到一条有下一步的提示，而不是让大屏停在最后一帧。
 */
class PlayerFailureTest {

    @Test
    fun `io and timeout point at the address`() {
        assertEquals(ErrorNotice.Cause.SOURCE_NOT_FOUND, PlayerFailure.causeFor(PlayerFailure.WHAT_UNKNOWN, PlayerFailure.EXTRA_IO))
        assertEquals(ErrorNotice.Cause.SOURCE_NOT_FOUND, PlayerFailure.causeFor(PlayerFailure.WHAT_UNKNOWN, PlayerFailure.EXTRA_TIMED_OUT))
    }

    @Test
    fun `auth violation is the source refusing to be cast`() {
        assertEquals(ErrorNotice.Cause.SOURCE_FORBIDDEN, PlayerFailure.causeFor(PlayerFailure.WHAT_UNKNOWN, PlayerFailure.EXTRA_AUTH_VIOLATION))
    }

    @Test
    fun `malformed and unsupported are format dead ends with a way out`() {
        assertEquals(ErrorNotice.Cause.SOURCE_FORMAT, PlayerFailure.causeFor(PlayerFailure.WHAT_UNKNOWN, PlayerFailure.EXTRA_MALFORMED))
        assertEquals(ErrorNotice.Cause.SOURCE_FORMAT, PlayerFailure.causeFor(PlayerFailure.WHAT_UNKNOWN, PlayerFailure.EXTRA_UNSUPPORTED))
    }

    @Test
    fun `the undocumented sentinel seen on device still yields a notice`() {
        val cause = PlayerFailure.causeFor(PlayerFailure.WHAT_UNKNOWN, PlayerFailure.EXTRA_NONE)
        assertEquals(ErrorNotice.Cause.SOURCE_FORMAT, cause)
        assertTrue(cause.toString(), ErrorNotice.noticeFor(cause).actions.isNotEmpty())
    }

    @Test
    fun `a dead media server is our capability problem, not the source`() {
        assertEquals(ErrorNotice.Cause.DECODE_UNSUPPORTED, PlayerFailure.causeFor(PlayerFailure.WHAT_SERVER_DIED, 0))
        assertEquals(ErrorNotice.Cause.DECODE_UNSUPPORTED, PlayerFailure.causeFor(PlayerFailure.WHAT_UNKNOWN, PlayerFailure.EXTRA_SERVER_DIED))
    }

    @Test
    fun `every mapped headline stays within the 18 char limit`() {
        for (what in listOf(PlayerFailure.WHAT_UNKNOWN, PlayerFailure.WHAT_SERVER_DIED)) {
            for (extra in listOf(0, -110, -1000, -1003, -1004, -1007, -1010, PlayerFailure.EXTRA_NONE)) {
                val notice = ErrorNotice.noticeFor(PlayerFailure.causeFor(what, extra))
                assertTrue("${notice.headline} 超过 18 字", notice.headline.length <= ErrorNotice.MAX_HEADLINE_CHARS)
            }
        }
    }
}

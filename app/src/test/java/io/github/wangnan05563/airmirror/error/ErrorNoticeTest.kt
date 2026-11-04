package io.github.wangnan05563.airmirror.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorNoticeTest {

    private val forbidden = listOf("投射", "同屏", "共享")

    @Test
    fun `every cause has a notice with what and next step`() {
        for (cause in ErrorNotice.Cause.entries) {
            val notice = ErrorNotice.noticeFor(cause)
            assertTrue("$cause headline blank", notice.headline.isNotBlank())
            assertTrue("$cause next step blank", notice.nextStep.isNotBlank())
        }
    }

    @Test
    fun `headline fits the one line limit`() {
        for (cause in ErrorNotice.Cause.entries) {
            val headline = ErrorNotice.noticeFor(cause).headline
            assertTrue(
                "$cause headline too long: $headline (${headline.length})",
                headline.length <= ErrorNotice.MAX_HEADLINE_CHARS,
            )
        }
    }

    @Test
    fun `next step tells the user what to do rather than repeating the error`() {
        for (cause in ErrorNotice.Cause.entries) {
            val notice = ErrorNotice.noticeFor(cause)
            assertFalse("$cause next step repeated headline", notice.nextStep.contains(notice.headline))
        }
    }

    @Test
    fun `vocabulary follows the shared glossary`() {
        for (cause in ErrorNotice.Cause.entries) {
            val notice = ErrorNotice.noticeFor(cause)
            for (word in forbidden) {
                assertFalse("$cause uses forbidden word $word", notice.headline.contains(word) || notice.nextStep.contains(word))
            }
        }
    }

    @Test
    fun `every category is reachable from at least one cause`() {
        val used = ErrorNotice.Cause.entries.map { ErrorNotice.noticeFor(it).category }.toSet()
        assertEquals(ErrorNotice.Category.entries.toSet(), used)
    }

    @Test
    fun `every rejected connection reason still offers an action`() {
        for (cause in ErrorNotice.Cause.entries) {
            val notice = ErrorNotice.noticeFor(cause)
            assertTrue("$cause has no action", notice.actions.isNotEmpty())
        }
    }

    @Test
    fun `legacy receiver reasons map onto the right causes`() {
        assertEquals(ErrorNotice.Cause.SESSION_BUSY, ErrorNotice.causeOfLegacyReason("已有设备在投屏"))
        assertEquals(ErrorNotice.Cause.CODE_LOCKED, ErrorNotice.causeOfLegacyReason("投屏码错误次数过多"))
        assertEquals(ErrorNotice.Cause.CODE_USED, ErrorNotice.causeOfLegacyReason("投屏码已用过"))
        assertEquals(ErrorNotice.Cause.CODE_WRONG, ErrorNotice.causeOfLegacyReason("投屏码不正确"))
        assertEquals(ErrorNotice.Cause.REFUSED_BY_USER, ErrorNotice.causeOfLegacyReason("已拒绝"))
        assertEquals(ErrorNotice.Cause.NO_AUTH, ErrorNotice.causeOfLegacyReason("缺少授权"))
    }

    @Test
    fun `unknown reason falls back to a generic network notice`() {
        assertEquals(ErrorNotice.Cause.UNREACHABLE, ErrorNotice.causeOfLegacyReason("something odd"))
    }

    @Test
    fun `combined text carries both halves`() {
        val cause = ErrorNotice.Cause.CODE_EXPIRED
        val text = ErrorNotice.textFor(cause)
        val notice = ErrorNotice.noticeFor(cause)
        assertTrue(text.contains(notice.headline))
        assertTrue(text.contains(notice.nextStep))
    }

    @Test
    fun `a locked tier that cannot hold says how to get it back`() {
        val notice = ErrorNotice.noticeFor(ErrorNotice.Cause.TIER_UNSTABLE)
        // 锁是用户自己上的，提示只能教他取消，不能擅自动画质（PRD FS-05「锁定后不再自动升降，仅在严重卡顿时提示」）
        assertTrue(notice.nextStep, notice.actions.contains(ErrorNotice.Action.OPEN_SETTINGS))
        assertTrue(notice.nextStep, notice.actions.contains(ErrorNotice.Action.AUTO_RECOVER))
        assertFalse(notice.nextStep, notice.actions.contains(ErrorNotice.Action.DOWNGRADE))
    }

    @Test
    fun `a bandwidth jump tells the user nothing has to be redone`() {
        val notice = ErrorNotice.noticeFor(ErrorNotice.Cause.BANDWIDTH_JUMP)
        assertEquals(ErrorNotice.Category.NETWORK, notice.category)
        assertTrue(notice.nextStep, notice.actions.contains(ErrorNotice.Action.AUTO_RECOVER))
    }

    @Test
    fun `the 4K unstable wording matches the PRD exception table`() {
        assertEquals("当前网络或设备不足以稳定播放 4K", ErrorNotice.tierUnstable("4K"))
    }
}

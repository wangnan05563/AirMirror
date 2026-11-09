package io.github.wangnan05563.airmirror.ui

import io.github.wangnan05563.airmirror.error.ErrorNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD UX-02 验收：空输入 / 部分输入 / 满 6 位 / 锁定四分支的判据锁。 */
class CodeInputPolicyTest {

    @Test
    fun `空输入换码不清空也不提示重新确认`() {
        val d = CodeInputPolicy.onCodeRotated(inputLength = 0, locked = false)
        assertTrue("空输入也要保留（不清空是 P-05 痛点）", d.preserveInput)
        assertNull("没有内容就无可重新确认", d.notice)
        assertEquals(CodeInputPolicy.InputState.EMPTY, CodeInputPolicy.inputState(0))
    }

    @Test
    fun `部分输入换码保留并提示已更新请重新确认`() {
        val d = CodeInputPolicy.onCodeRotated(inputLength = 3, locked = false)
        assertTrue(d.preserveInput)
        assertEquals("投屏码已更新，请重新确认", d.notice)
        assertEquals(CodeInputPolicy.InputState.PARTIAL, CodeInputPolicy.inputState(3))
    }

    @Test
    fun `满六位换码保留并提示已更新请重新确认`() {
        val d = CodeInputPolicy.onCodeRotated(inputLength = 6, locked = false)
        assertTrue(d.preserveInput)
        assertEquals("投屏码已更新，请重新确认", d.notice)
        assertEquals(CodeInputPolicy.InputState.FULL, CodeInputPolicy.inputState(6))
    }

    @Test
    fun `锁定态优先于输入态并沿用既有锁定文案`() {
        // 锁定优先：无论输入多少，都不换成「请重新确认」，避免用户白跑一轮
        for (len in listOf(0, 3, 6)) {
            val d = CodeInputPolicy.onCodeRotated(inputLength = len, locked = true)
            assertTrue(d.preserveInput)
            assertEquals(A11yCopy.codeLocked(), d.notice)
        }
        assertTrue("锁定文案必须是 PRD 引用的既有口径", A11yCopy.codeLocked().startsWith("投屏码已临时锁定"))
    }

    @Test
    fun `任何分支都保留输入 preserveInput 恒真`() {
        for (len in listOf(-1, 0, 1, 5, 6, 7)) {
            for (locked in listOf(false, true)) {
                assertTrue("len=$len locked=$locked 不得清空输入", CodeInputPolicy.onCodeRotated(len, locked).preserveInput)
            }
        }
    }

    @Test
    fun `输入态边界 0 空 1到5 部分 6及以上满`() {
        assertEquals(CodeInputPolicy.InputState.EMPTY, CodeInputPolicy.inputState(0))
        assertEquals(CodeInputPolicy.InputState.PARTIAL, CodeInputPolicy.inputState(1))
        assertEquals(CodeInputPolicy.InputState.PARTIAL, CodeInputPolicy.inputState(5))
        assertEquals(CodeInputPolicy.InputState.FULL, CodeInputPolicy.inputState(6))
        assertEquals(CodeInputPolicy.InputState.FULL, CodeInputPolicy.inputState(7))
    }

    @Test
    fun `锁定文案单一出口不在策略里另写一份`() {
        assertEquals(A11yCopy.codeLocked(), CodeInputPolicy.lockedNotice())
    }

    @Test
    fun `码类拒绝的渲染文案能反查回 Cause 供界面判决`() {
        // report 存的是 textFor 的结果，causeOfLegacyReason 不能 round-trip，必须用 causeOfRendered
        for (cause in listOf(
            ErrorNotice.Cause.CODE_WRONG,
            ErrorNotice.Cause.CODE_EXPIRED,
            ErrorNotice.Cause.CODE_USED,
            ErrorNotice.Cause.CODE_LOCKED,
        )) {
            assertEquals(cause, ErrorNotice.causeOfRendered(ErrorNotice.textFor(cause)))
        }
    }

    @Test
    fun `非码类文案与画质串反查为 null 不接管原提示`() {
        assertNull(ErrorNotice.causeOfRendered(""))
        assertNull(ErrorNotice.causeOfRendered("1080p · 30fps"))
        assertNull(ErrorNotice.causeOfRendered("接收端未应答授权"))
        assertEquals(
            ErrorNotice.Cause.UNREACHABLE,
            ErrorNotice.causeOfRendered(ErrorNotice.textFor(ErrorNotice.Cause.UNREACHABLE)),
        )
    }
}
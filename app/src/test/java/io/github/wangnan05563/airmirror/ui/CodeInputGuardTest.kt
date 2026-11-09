package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码守卫：UX-02 换码容错必须走「码类拒绝反查 Cause → CodeInputPolicy 四分支 → 保留输入」。
 * 任何退回清空输入、在界面层散落中文串匹配、或另写一份锁定文案的改动都在这里失败；
 * 同时锁住依赖约束——CastCode 的轮换与锁定参数不得被本需求改动。
 */
class CodeInputGuardTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "AirMirror-PRD-v1.1.md").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun uiDir() = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui")

    private fun sender() = File(uiDir(), "SenderActivity.kt").readText()

    private fun policy() = File(uiDir(), "CodeInputPolicy.kt").readText()

    @Test
    fun `刷新入口按码类拒绝接管提示且走四分支判决`() {
        val src = sender()
        assertTrue("缺少码类拒绝反查", src.contains("ErrorNotice.causeOfRendered(report)"))
        assertTrue("缺少四分支判决入口", src.contains("CodeInputPolicy.onCodeRotated(codeInput.text.length, locked)"))
        assertTrue("status 必须先问换码提示再回落原提示", src.contains("codeRotationNotice(report) ?:"))
    }

    @Test
    fun `输入框永不清空 提示只落 status 不弹模态框`() {
        val src = sender()
        assertFalse("不得清空投屏码输入", src.contains("codeInput.setText(\"\")"))
        assertFalse("不得清空投屏码输入", src.contains("codeInput.text.clear()"))
        // 换码提示赋值给 status.text（见上一用例），不是 AlertDialog；这里锁住 helper 只返回 String?
        assertTrue("换码提示必须是返回文案的纯函数", src.contains("private fun codeRotationNotice(report: String): String?"))
    }

    @Test
    fun `锁定文案单一出口 策略里不另写一份`() {
        val src = policy()
        assertTrue("锁定文案必须委托 A11yCopy", src.contains("A11yCopy.codeLocked()"))
        assertFalse("策略里不得散落锁定文案字面量", src.contains("投屏码已临时锁定"))
        assertTrue("换码提示必须是 PRD 逐字口径", src.contains("投屏码已更新，请重新确认"))
    }

    @Test
    fun `反查表落在 ErrorNotice 不在界面层匹配中文串`() {
        val err = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/error/ErrorNotice.kt").readText()
        assertTrue("缺少渲染文案反查", err.contains("fun causeOfRendered"))
        val src = sender()
        assertFalse("界面层不得用 causeOfLegacyReason 反查已渲染文案", src.contains("causeOfLegacyReason(report)"))
    }

    @Test
    fun `preserveInput 恒真 清空输入正是 P-05 痛点`() {
        val src = policy()
        assertTrue(src.contains("val preserveInput: Boolean"))
        assertFalse("任何分支都不得清空输入", src.contains("preserveInput = false"))
        // 每个 Decision 构造都必须 preserveInput = true（部分与满六位共用一个分支，按构造数对齐而非写死 4）
        assertEquals(
            "每个 Decision 构造都必须 preserveInput = true",
            src.split("Decision(preserveInput").size - 1,
            src.split("preserveInput = true").size - 1,
        )
    }

    @Test
    fun `依赖约束 CastCode 轮换与锁定参数没有被改动`() {
        val cast = File(
            projectRoot(),
            "app/src/main/java/io/github/wangnan05563/airmirror/pairing/CastCode.kt",
        ).readText()
        assertTrue(cast.contains("private const val DEFAULT_TTL = 60_000L"))
        assertTrue(cast.contains("private const val DEFAULT_GRACE = 10_000L"))
        assertTrue(cast.contains("private const val DEFAULT_LOCK = 60_000L"))
        assertTrue(cast.contains("private const val DEFAULT_MAX_ATTEMPTS = 5"))
    }
}
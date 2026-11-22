package io.github.wangnan05563.airmirror.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T06114：PIN/仅信任拒绝必须在发送端有独立、可执行的错误文案。 */
class ErrorNoticePinTest {

    private val causes = listOf(
        ErrorNotice.Cause.PIN_REQUIRED,
        ErrorNotice.Cause.PIN_WRONG,
        ErrorNotice.Cause.PIN_LOCKED,
        ErrorNotice.Cause.TRUSTED_ONLY,
    )

    @Test
    fun everyPinCauseHasAHeadlineAndANextStep() {
        for (cause in causes) {
            val notice = ErrorNotice.noticeFor(cause)
            assertEquals(ErrorNotice.Category.PAIRING, notice.category)
            assertTrue("${cause} 标题超长", notice.headline.length <= ErrorNotice.MAX_HEADLINE_CHARS)
            assertTrue("${cause} 副文案为空", notice.nextStep.isNotBlank())
            assertTrue("${cause} 没有可选动作", notice.actions.isNotEmpty())
        }
    }

    @Test
    fun pinReasonsFromReceiverSessionMapBackToDedicatedCauses() {
        assertEquals(ErrorNotice.Cause.PIN_REQUIRED, ErrorNotice.causeOfLegacyReason("电视要求输入 4 位 PIN"))
        assertEquals(ErrorNotice.Cause.PIN_WRONG, ErrorNotice.causeOfLegacyReason("PIN 不正确"))
        assertEquals(ErrorNotice.Cause.PIN_LOCKED, ErrorNotice.causeOfLegacyReason("PIN 已锁定，42 秒后可重试"))
        assertEquals(ErrorNotice.Cause.TRUSTED_ONLY, ErrorNotice.causeOfLegacyReason("仅允许已信任设备连接"))
    }

    @Test
    fun renderedPinTextRoundTripsBackToTheCause() {
        for (cause in causes) {
            assertEquals(cause, ErrorNotice.causeOfRendered(ErrorNotice.textFor(cause)))
        }
    }
}

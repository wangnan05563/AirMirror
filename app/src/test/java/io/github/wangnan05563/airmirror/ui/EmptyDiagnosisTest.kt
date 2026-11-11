package io.github.wangnan05563.airmirror.ui

import io.github.wangnan05563.airmirror.error.ErrorNotice
import io.github.wangnan05563.airmirror.net.DeviceRegistry.EmptyReason
import io.github.wangnan05563.airmirror.net.DiscoveryLadder
import io.github.wangnan05563.airmirror.net.DiscoveryLadder.Channel
import io.github.wangnan05563.airmirror.net.DiscoveryLadder.Empty
import io.github.wangnan05563.airmirror.net.DiscoveryLadder.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX-01 空列表自救判决映射（设计 §6.1）：唯一映射、固定三动作、文案硬门、全组合不抛异常。 */
class EmptyDiagnosisTest {

    private val verdicts = listOf(
        Verdict.Found(downgraded = false, via = setOf(Channel.MULTICAST)),
        Verdict.Found(downgraded = true, via = setOf(Channel.BROADCAST)),
        Verdict.Nothing(Empty.SEARCHING, ""),
        Verdict.Nothing(Empty.AP_ISOLATED, DiscoveryLadder.TEXT_AP_ISOLATED),
        Verdict.Nothing(Empty.DUAL_BAND, DiscoveryLadder.TEXT_DUAL_BAND),
    )

    private val registries = listOf(EmptyReason.NONE_FOUND, EmptyReason.ALL_OFFLINE, EmptyReason.HAS_ONLINE)

    private val diagnosed = setOf(
        ErrorNotice.Cause.DEVICE_OFFLINE,
        ErrorNotice.Cause.DISCOVERY_AP_ISOLATED,
        ErrorNotice.Cause.DISCOVERY_DUAL_BAND,
    )

    private val fixedActions = listOf(
        ErrorNotice.Action.RETRY_DISCOVERY,
        ErrorNotice.Action.ENTER_CODE,
        ErrorNotice.Action.ENTER_IP,
    )

    @Test
    fun `任一通道命中不诊断`() {
        for (registry in registries) {
            assertNull(EmptyDiagnosis.of(Verdict.Found(false, setOf(Channel.MULTICAST)), registry))
        }
    }

    @Test
    fun `组播未中但广播直连命中静默降级`() {
        for (registry in registries) {
            assertNull(EmptyDiagnosis.of(Verdict.Found(true, setOf(Channel.BROADCAST, Channel.DIRECT)), registry))
        }
    }

    @Test
    fun `三轮未走完不下结论`() {
        for (registry in registries) {
            assertNull(EmptyDiagnosis.of(Verdict.Nothing(Empty.SEARCHING, ""), registry))
        }
    }

    @Test
    fun `走完无命中按网段判 AP 隔离`() {
        assertEquals(
            ErrorNotice.Cause.DISCOVERY_AP_ISOLATED,
            EmptyDiagnosis.of(Verdict.Nothing(Empty.AP_ISOLATED, DiscoveryLadder.TEXT_AP_ISOLATED), EmptyReason.NONE_FOUND),
        )
    }

    @Test
    fun `走完无命中按网段判双频分离`() {
        assertEquals(
            ErrorNotice.Cause.DISCOVERY_DUAL_BAND,
            EmptyDiagnosis.of(Verdict.Nothing(Empty.DUAL_BAND, DiscoveryLadder.TEXT_DUAL_BAND), EmptyReason.NONE_FOUND),
        )
    }

    @Test
    fun `已知设备全离线优先于网络判决`() {
        for (empty in listOf(Empty.AP_ISOLATED, Empty.DUAL_BAND)) {
            assertEquals(
                ErrorNotice.Cause.DEVICE_OFFLINE,
                EmptyDiagnosis.of(Verdict.Nothing(empty, ""), EmptyReason.ALL_OFFLINE),
            )
        }
    }

    @Test
    fun `列表有在线设备不诊断`() {
        for (verdict in verdicts) {
            assertNull(EmptyDiagnosis.of(verdict, EmptyReason.HAS_ONLINE))
        }
    }

    @Test
    fun `全部组合不抛异常且返回值在允许集合`() {
        for (verdict in verdicts) {
            for (registry in registries) {
                val cause = EmptyDiagnosis.of(verdict, registry)
                assertTrue("非法 cause $cause（$verdict / $registry）", cause == null || cause in diagnosed)
            }
        }
    }

    @Test
    fun `三条诊断动作恰为固定三按钮且顺序固定`() {
        assertEquals(fixedActions, EmptyDiagnosis.ACTIONS)
        for (cause in diagnosed) {
            assertEquals("$cause 动作口径漂移", fixedActions, ErrorNotice.noticeFor(cause).actions)
        }
    }

    @Test
    fun `三条诊断首行两两不同`() {
        val headlines = diagnosed.map { ErrorNotice.noticeFor(it).headline }
        assertEquals("首行复用会造成误诊", headlines.size, headlines.toSet().size)
    }

    @Test
    fun `三条诊断通过既有文案硬门`() {
        for (cause in diagnosed) {
            val notice = ErrorNotice.noticeFor(cause)
            assertTrue("$cause 首行超 18 字", notice.headline.length <= ErrorNotice.MAX_HEADLINE_CHARS)
            assertFalse("$cause 下一步重复首行", notice.nextStep.contains(notice.headline))
            for (word in listOf("投射", "同屏", "共享")) {
                assertFalse("$cause 用禁用词 $word", notice.headline.contains(word) || notice.nextStep.contains(word))
            }
        }
    }

    @Test
    fun `动作标签符合设计口径`() {
        assertEquals("重试发现", ErrorNotice.actionLabel(ErrorNotice.Action.RETRY_DISCOVERY))
        assertEquals("用投屏码连接", ErrorNotice.actionLabel(ErrorNotice.Action.ENTER_CODE))
        assertEquals("手动输 IP", ErrorNotice.actionLabel(ErrorNotice.Action.ENTER_IP))
    }
}
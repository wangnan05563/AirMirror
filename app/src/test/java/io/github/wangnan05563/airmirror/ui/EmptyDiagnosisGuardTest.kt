package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX-01 源码守卫：空态诊断卡必须由固定三动作驱动、就地生效、不引扫码。
 * 与 [EmptyDiagnosisTest]（纯逻辑）互补：这里锁「判决真的接进了界面渲染」，
 * 防止只留一个没被调用的映射函数（设计 §07「点击动作就地进入对应输入态，不跳新页面」）。
 */
class EmptyDiagnosisGuardTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun sender(): String =
        File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/SenderActivity.kt").readText()

    private fun card(): String =
        File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/ErrorStateCard.kt").readText()

    @Test
    fun `诊断卡由固定三动作循环渲染`() {
        val c = card()
        assertTrue(
            "按钮要遍历 EmptyDiagnosis.ACTIONS",
            c.contains("for ((index, action) in actions.withIndex())"),
        )
        assertTrue("默认动作集走统一出口", c.contains("actions: List<ErrorNotice.Action> = EmptyDiagnosis.ACTIONS"))
        assertTrue("按钮文案走统一出口", c.contains("text = ErrorNotice.actionLabel(action)"))
        assertTrue("按钮触控高度 ≥48dp", c.contains("minimumHeight = buttonHeight"))
    }

    @Test
    fun `诊断卡接进列表上方且按判决显隐`() {
        val s = sender()
        assertTrue("卡片要加进列表容器", s.contains("addView(diagnosisCard, matchWidth())"))
        assertTrue("空态要消费结构化判决", s.contains("val cause = EmptyDiagnosis.of(verdict, registry)"))
        assertTrue("有诊断才亮卡", s.contains("diagnosisCard.visibility = View.VISIBLE"))
        assertTrue("无诊断要隐藏", s.contains("diagnosisCard.visibility = View.GONE"))
        assertTrue("诊断原因交给组件统一绑定", s.contains("diagnosisCard.bind(cause, EmptyDiagnosis.ACTIONS)"))
        assertTrue(
            "搜索中 0 台也要给无设备入口",
            s.contains("diagnosisCard.bind(ErrorNotice.Cause.NO_DISCOVERY, EmptyDiagnosis.ACTIONS)"),
        )
    }

    @Test
    fun `三动作就地生效不跳页`() {
        val s = sender()
        assertTrue("重试发现重起搜索窗口", s.contains("scanner?.begin()"))
        assertTrue("用投屏码连接聚焦码输入框", s.contains("ErrorNotice.Action.ENTER_CODE -> focusInput(codeInput)"))
        assertTrue("手动输 IP 聚焦地址输入框", s.contains("ErrorNotice.Action.ENTER_IP -> focusInput(addressInput)"))
        assertTrue("组件回调用宿主既有动作处理", s.contains("onAction = ::onDiagnosisAction"))
    }

    @Test
    fun `发送端空态不引扫码`() {
        assertFalse("发送端无相机入口，空态不得给 SCAN_QR（设计 §2.6 / §09）", sender().contains("SCAN_QR"))
        assertFalse("空态组件也不得引扫码", card().contains("SCAN_QR"))
    }
}
package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文案层硬规则（PRD UI-03 / 设计 §10.1）：长度、术语、状态差异与「不朗读符号」在这里钉死。
 * 界面层只把状态交给 A11yCopy，不再持有第二套字符串；改动任何一句都必须先过这一组断言。
 */
class A11yCopyTest {

    /** 普通描述上限 40 字（设计 R7）。 */
    private fun samples(): List<String> = listOf(
        A11yCopy.mainSend(),
        A11yCopy.mainReceive(),
        A11yCopy.mainPrivacy(),
        A11yCopy.pause(paused = false),
        A11yCopy.pause(paused = true),
        A11yCopy.stop("客厅电视"),
        A11yCopy.audio(on = true),
        A11yCopy.audio(on = false),
        A11yCopy.permissionFix(listOf("投屏中通知", "镜像带声音")),
        A11yCopy.crossNetSender(configured = true, endpoint = "http://10.0.0.2:8848"),
        A11yCopy.crossNetSender(configured = false, endpoint = ""),
        A11yCopy.checkUpdate(),
        A11yCopy.codeConnect(),
        A11yCopy.addressConnect(),
        A11yCopy.takeover(from = "手机", seconds = 60, yield = false),
        A11yCopy.takeover(from = "手机", seconds = 60, yield = true),
        A11yCopy.card("客厅电视", online = true, supported = true, expanded = false, lastLabel = "还没连过"),
        A11yCopy.card("客厅电视", online = true, supported = true, expanded = true, lastLabel = "还没连过"),
        A11yCopy.card("老盒子", online = false, supported = true, expanded = false, lastLabel = "上次连接 5 分钟前"),
        A11yCopy.card("老盒子", online = true, supported = false, expanded = false, lastLabel = "还没连过"),
        A11yCopy.cardAction("客厅电视", mirror = true),
        A11yCopy.cardAction("客厅电视", mirror = false),
        A11yCopy.codeInputLabel(),
        A11yCopy.addressInputLabel(),
        A11yCopy.diagnosisRetry(),
        A11yCopy.diagnosisCode(),
        A11yCopy.diagnosisIp(),
        A11yCopy.settingsEntry(casting = false),
        A11yCopy.settingsEntry(casting = true),
        A11yCopy.mode("完整显示", "等比完整显示，不裁切画面", selected = true),
        A11yCopy.qualityAuto(selected = true),
        A11yCopy.quality("1080p60", 1920, 1080, 60, selected = false),
        A11yCopy.toggle("旋转跟随", on = true),
        A11yCopy.toggle("旋转跟随", on = false),
        A11yCopy.signaling(configured = true, endpoint = "http://10.0.0.2:8848"),
        A11yCopy.signaling(configured = false, endpoint = ""),
        A11yCopy.diagnostics(enabled = true),
        A11yCopy.diagnostics(enabled = false),
        A11yCopy.clearData(7),
        A11yCopy.finish(),
        A11yCopy.settingsOpened(),
        A11yCopy.settingsClosed(),
        A11yCopy.code("123456", locked = false, seconds = 58),
        A11yCopy.code("", locked = true, seconds = 0),
        A11yCopy.browserUrl("http://10.0.0.2:8848"),
        A11yCopy.promptAllow("手机"),
        A11yCopy.promptDeny("手机"),
        A11yCopy.endCancel(),
        A11yCopy.endConfirm(),
        A11yCopy.endConfirmBar(),
        A11yCopy.moreInfo(expanded = true),
        A11yCopy.moreInfo(expanded = false),
        A11yCopy.onboardingStep(1, 3, "允许后台运行"),
        A11yCopy.onboardingBack(),
        A11yCopy.onboardingSkip(),
        A11yCopy.onboardingNext(lastStep = false),
        A11yCopy.onboardingNext(lastStep = true),
        A11yCopy.onboardingSkipAll(),
        A11yCopy.onboardInputLabel(),
        A11yCopy.nameOverlay("手机"),
        A11yCopy.recovery(),
        A11yCopy.codeRotated("123456", 58),
        A11yCopy.codeLocked(),
    )

    /** 二维码替代说明上限 50 字（设计 R7）。 */
    private fun qrSamples(): List<String> = listOf(
        A11yCopy.mirrorQr(),
        A11yCopy.browserQr(),
    )

    @Test
    fun `所有描述非空且不超过四十字`() {
        samples().forEach {
            assertTrue("空描述", it.isNotBlank())
            assertTrue("超长(${it.length})：$it", it.length <= 40)
        }
    }

    @Test
    fun `二维码替代说明不超过五十字`() {
        qrSamples().forEach {
            assertTrue("空描述", it.isNotBlank())
            assertTrue("超长(${it.length})：$it", it.length <= 50)
        }
    }

    @Test
    fun `暂停两态描述互不相同且带当前状态`() {
        val running = A11yCopy.pause(paused = false)
        val paused = A11yCopy.pause(paused = true)
        assertNotEquals(running, paused)
        assertTrue(running.contains("当前正在投") && running.startsWith("暂停投屏"))
        assertTrue(paused.contains("当前已暂停") && paused.startsWith("继续投屏"))
    }

    @Test
    fun `声音两态描述互不相同且带当前状态`() {
        val on = A11yCopy.audio(on = true)
        val off = A11yCopy.audio(on = false)
        assertNotEquals(on, off)
        assertTrue(on.startsWith("关闭声音") && on.contains("当前有声"))
        assertTrue(off.startsWith("打开声音") && off.contains("当前无声"))
    }

    @Test
    fun `开关行两态描述互不相同且带当前开关`() {
        val on = A11yCopy.toggle("旋转跟随", on = true)
        val off = A11yCopy.toggle("旋转跟随", on = false)
        assertNotEquals(on, off)
        assertTrue(on.contains("旋转跟随") && on.contains("当前开") && on.contains("双击关闭"))
        assertTrue(off.contains("当前关") && off.contains("双击开启"))
    }

    @Test
    fun `停止投屏描述随目标名变化`() {
        val tv = A11yCopy.stop("客厅电视")
        assertTrue(tv.startsWith("停止投屏"))
        assertTrue(tv.contains("客厅电视"))
        assertNotEquals(tv, A11yCopy.stop("卧室盒子"))
    }

    @Test
    fun `设备卡片四态两两不同`() {
        val online = A11yCopy.card("客厅电视", online = true, supported = true, expanded = false, lastLabel = "上次连接 5 分钟前")
        val expanded = A11yCopy.card("客厅电视", online = true, supported = true, expanded = true, lastLabel = "上次连接 5 分钟前")
        val offline = A11yCopy.card("客厅电视", online = false, supported = true, expanded = false, lastLabel = "上次连接 5 分钟前")
        val unsupported = A11yCopy.card("客厅电视", online = true, supported = false, expanded = false, lastLabel = "上次连接 5 分钟前")
        val all = listOf(online, expanded, offline, unsupported)
        assertEquals("四态必须互不相同", 4, all.toSet().size)
        assertTrue(offline.contains("上次连接"))
        assertTrue(offline.contains("双击查看替代"))
        assertTrue(unsupported.contains("不支持"))
        assertTrue(expanded.contains("已展开"))
    }

    @Test
    fun `卡片与动作描述随设备名变化`() {
        assertNotEquals(
            A11yCopy.card("客厅电视", online = true, supported = true, expanded = false, lastLabel = ""),
            A11yCopy.card("卧室盒子", online = true, supported = true, expanded = false, lastLabel = ""),
        )
        assertNotEquals(A11yCopy.cardAction("客厅电视", true), A11yCopy.cardAction("卧室盒子", true))
    }

    @Test
    fun `三种显示模式两态全覆盖且同屏只有一行已选中`() {
        val modes = listOf(
            Triple("完整显示", "等比完整显示，不裁切画面", true),
            Triple("拉伸铺满", "画面铺满整屏，可能变形", false),
            Triple("裁切铺满", "保留比例铺满，超出裁掉", false),
        )
        val texts = modes.flatMap { (label, hint, selected) ->
            listOf(
                A11yCopy.mode(label, hint, selected = true),
                A11yCopy.mode(label, hint, selected = false),
            )
        }
        assertEquals("3 模式 × 2 状态", 6, texts.toSet().size)
        modes.forEach { (label, hint, selected) ->
            val on = A11yCopy.mode(label, hint, selected = true)
            val off = A11yCopy.mode(label, hint, selected = false)
            assertTrue(on.contains(label) && on.contains("已选中"))
            assertTrue(off.contains(label) && off.contains("未选中"))
            if (selected) assertTrue(on.contains(hint)) else assertTrue(off.contains(hint))
        }
        val currentScreen = modes.map { (label, hint, selected) ->
            A11yCopy.mode(label, hint, selected)
        }
        assertEquals("同一屏只能有一行「已选中」", 1, currentScreen.count { it.contains("已选中") })
    }

    @Test
    fun `画质自动与档位选中态互斥且档位含分辨率帧率`() {
        val autoOn = A11yCopy.qualityAuto(selected = true)
        val autoOff = A11yCopy.qualityAuto(selected = false)
        assertTrue(autoOn.contains("自动") && autoOn.contains("已选中"))
        assertTrue(autoOff.contains("未选中"))
        val tierOn = A11yCopy.quality("1080p60", 1920, 1080, 60, selected = true)
        val tierOff = A11yCopy.quality("1080p60", 1920, 1080, 60, selected = false)
        assertTrue(tierOn.contains("1920x1080") && tierOn.contains("60帧") && tierOn.contains("已选中"))
        assertTrue(tierOff.contains("未选中"))
        assertNotEquals(tierOn, tierOff)
    }

    @Test
    fun `投屏码正常态读出六位数字与剩余秒数`() {
        val text = A11yCopy.code("123456", locked = false, seconds = 58)
        assertTrue(text.contains("123456"))
        assertTrue(text.contains("58"))
        assertTrue(text.contains("投屏码"))
    }

    @Test
    fun `锁定态不回读旧码也不读占位符`() {
        val locked = A11yCopy.code("123456", locked = true, seconds = 0)
        assertFalse("锁定期不得出现任何数字", locked.any { it.isDigit() })
        assertFalse(locked.contains("------"))
        assertTrue(locked.contains("锁定"))
    }

    @Test
    fun `二维码描述给投屏码替代路径且不朗读载荷`() {
        val payload = "airmirror://receiver?code=123456&sig=abcdef"
        val text = A11yCopy.mirrorQr()
        assertTrue(text.contains("投屏码"))
        assertFalse(text.contains(payload))
        assertFalse(text.contains("airmirror://"))
        assertFalse(text.contains("123456"))
    }

    @Test
    fun `首启三步标题各自命中且第三步按钮是完成`() {
        listOf("允许后台运行", "开机自启", "设备名").forEachIndexed { index, title ->
            val text = A11yCopy.onboardingStep(index + 1, 3, title)
            assertTrue("第 ${index + 1} 步缺标题：$text", text.contains(title))
            assertTrue(text.contains("共 3 步"))
        }
        assertEquals("完成，保存设备名并开始等待投屏", A11yCopy.onboardingNext(lastStep = true))
        assertFalse(A11yCopy.onboardingNext(lastStep = true).contains("下一步"))
        assertEquals("下一步", A11yCopy.onboardingNext(lastStep = false))
    }

    @Test
    fun `权限修复只列中文权限名不暴露常量`() {
        val text = A11yCopy.permissionFix(listOf("投屏中通知", "镜像带声音"))
        assertTrue(text.contains("投屏中通知") && text.contains("镜像带声音"))
        assertFalse(text.contains("android.permission"))
        assertFalse(text.contains(".permission."))
        assertFalse(text.contains("Permission"))
    }

    @Test
    fun `描述不以控件类型结尾`() {
        (samples() + qrSamples()).forEach {
            assertFalse("不得写控件类型：$it", it.endsWith("按钮") || it.endsWith("文本框"))
            assertFalse(it.contains("按钮") || it.contains("文本框"))
        }
    }

    @Test
    fun `不含禁用术语与装饰符号`() {
        val banned = listOf("投射", "同屏", "共享", "●", "○", "------")
        val hexLiteral = Regex("\\b0x[0-9a-fA-F]+\\b")
        (samples() + qrSamples()).forEach { text ->
            banned.forEach { word ->
                assertFalse("出现禁用词「$word」：$text", text.contains(word))
            }
            assertFalse("出现十六进制字面量：$text", hexLiteral.containsMatchIn(text))
        }
    }

    @Test
    fun `授权两按钮都带设备名且动作相反`() {
        val allow = A11yCopy.promptAllow("手机")
        val deny = A11yCopy.promptDeny("手机")
        assertNotEquals(allow, deny)
        assertTrue(allow.contains("手机") && allow.contains("允许"))
        assertTrue(deny.contains("手机") && deny.contains("拒绝"))
        assertNotEquals(A11yCopy.promptAllow("手机"), A11yCopy.promptAllow("平板"))
    }

    @Test
    fun `接管两态描述互不相同且带设备名`() {
        val deny = A11yCopy.takeover(from = "手机", seconds = 60, yield = false)
        val yield = A11yCopy.takeover(from = "手机", seconds = 60, yield = true)
        assertNotEquals(deny, yield)
        assertTrue(deny.startsWith("拒绝") && deny.contains("手机"))
        assertTrue(yield.startsWith("让出") && yield.contains("手机") && yield.contains("当前投屏将结束"))
    }

    @Test
    fun `投屏中设置入口描述随状态变化`() {
        assertNotEquals(A11yCopy.settingsEntry(casting = false), A11yCopy.settingsEntry(casting = true))
        assertTrue(A11yCopy.settingsEntry(casting = true).contains("正在投屏"))
    }

    @Test
    fun `换码与锁定播报内容不同且换码含新码`() {
        val rotated = A11yCopy.codeRotated("654321", 58)
        val locked = A11yCopy.codeLocked()
        assertNotEquals(rotated, locked)
        assertTrue(rotated.contains("654321") && rotated.contains("58"))
        assertTrue(locked.contains("锁定"))
    }

    @Test
    fun `空态三个诊断入口互不相同`() {
        assertEquals(
            3,
            listOf(A11yCopy.diagnosisRetry(), A11yCopy.diagnosisCode(), A11yCopy.diagnosisIp()).toSet().size,
        )
        assertTrue(A11yCopy.diagnosisIp().contains("IP"))
        assertTrue(A11yCopy.diagnosisCode().contains("投屏码"))
    }

    @Test
    fun `同一状态重复调用结果稳定`() {
        samples().forEachIndexed { index, text ->
            assertEquals("第 $index 条不稳定", text, samples()[index])
        }
        assertEquals(A11yCopy.code("123456", false, 58), A11yCopy.code("123456", false, 58))
        assertEquals(A11yCopy.card("客厅电视", true, true, false, "还没连过"), A11yCopy.card("客厅电视", true, true, false, "还没连过"))
    }

    @Test
    fun `长名字被截断后整句仍在上限内`() {
        val longName = "非常非常长的客厅电视设备名称超长版本"
        val texts = listOf(
            A11yCopy.stop(longName),
            A11yCopy.card(longName, online = true, supported = true, expanded = false, lastLabel = longName),
            A11yCopy.cardAction(longName, mirror = true),
            A11yCopy.takeover(longName, 60, yield = true),
            A11yCopy.promptAllow(longName),
            A11yCopy.nameOverlay(longName),
        )
        texts.forEach {
            assertTrue("超长(${it.length})：$it", it.length <= 40)
        }
    }

    @Test
    fun `访客地址与跨网信令保留可读端点`() {
        // 描述对端点做定长截断，短地址必须原样出现；真实长地址由 refreshWaitingPage 逐次刷新
        assertTrue(A11yCopy.signaling(configured = true, endpoint = "10.0.0.2").contains("10.0.0.2"))
        assertTrue(A11yCopy.crossNetSender(configured = true, endpoint = "10.0.0.2").contains("10.0.0.2"))
        val browser = A11yCopy.browserUrl("http://10.0.0.2:8848")
        assertTrue(browser.contains("浏览器"))
        assertTrue(browser.contains("10.0.0.2"))
        assertFalse(browser.contains("http://"))
        assertEquals("访客上传地址，可在浏览器打开", A11yCopy.browserUrl(""))
    }
}

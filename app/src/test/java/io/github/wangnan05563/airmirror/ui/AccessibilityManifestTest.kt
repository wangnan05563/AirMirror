package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码守卫（PRD UI-03 / 设计 §10.2）：无障碍接线只允许经 A11y 适配层，
 * 新增交互改动接线点数量必须先更新这里的决策表，强迫作者当场做无障碍决策。
 */
class AccessibilityManifestTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun uiDir() = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui")

    private fun source(name: String) = File(uiDir(), name).readText().replace("\r\n", "\n")

    private fun countOf(src: String, needle: String): Int = src.split(needle).size - 1

    /**
     * 设计基线 34（Main 3 / Sender 11 / Receiver 20）之后的两次产品改动各新增一个接线点：
     * UX-01 空态诊断卡（Sender +1）、UI-02 次要入口折叠（Receiver +1），因此当前为 36。
     */
    @Test
    fun `点击接线点数量与无障碍决策表一致`() {
        assertEquals(3, countOf(source("MainActivity.kt"), "setOnClickListener"))
        assertEquals(12, countOf(source("SenderActivity.kt"), "setOnClickListener"))
        assertEquals(21, countOf(source("ReceiverActivity.kt"), "setOnClickListener"))
        assertEquals(1, countOf(source("ReceiverActivity.kt"), "setOnLongClickListener"))
    }

    @Test
    fun `每个点击接线点都配了可访问名`() {
        mapOf(
            "MainActivity.kt" to 3,
            "SenderActivity.kt" to 12,
            "ReceiverActivity.kt" to 21,
        ).forEach { (file, sites) ->
            val describes = countOf(source(file), "A11y.describe(")
            assertTrue("$file 描述接线 $describes < 接线点 $sites", describes >= sites)
        }
    }

    @Test
    fun `界面层不得直写无障碍属性`() {
        val allowed = setOf("A11y.kt", "A11yCopy.kt")
        val offenders = uiDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { file ->
                val src = file.readText()
                src.contains("contentDescription =") ||
                    src.contains("importantForAccessibility =") ||
                    src.contains("accessibilityLiveRegion =") ||
                    src.contains("announceForAccessibility(")
            }
            .map { it.name }
            .toList()
        assertTrue("直写无障碍属性：$offenders", offenders.isEmpty())
    }

    @Test
    fun `文案层不依赖 android`() {
        val src = source("A11yCopy.kt")
        assertFalse("A11yCopy 引入 android 后无法在 JVM 单测里锁文案", src.contains("import android."))
        val frameworkRef = Regex("\\bandroid\\.(content|view|os|app|util|graphics|accessibility)\\.")
        assertFalse("文案层引用了框架类：${frameworkRef.find(src)?.value}", frameworkRef.containsMatchIn(src))
        assertTrue(src.contains("object A11yCopy"))
    }

    @Test
    fun `每秒刷新文本不得接入实时播报`() {
        val src = source("ReceiverActivity.kt")
        listOf("countdownText", "endText", "qualityBadge").forEach { view ->
            assertFalse("$view 每秒变化，不能设 liveStatus", src.contains("A11y.liveStatus($view"))
        }
        assertTrue("倒计时文本仍只更新可见文本", src.contains("countdownText.text ="))
    }

    @Test
    fun `三张图片各有替代描述或标记为装饰`() {
        val src = source("ReceiverActivity.kt")
        assertTrue("投屏二维码缺替代描述", src.contains("A11y.describe(this, A11yCopy.mirrorQr())"))
        assertTrue("浏览器二维码缺替代描述", src.contains("A11y.describe(this, A11yCopy.browserQr())"))
        val dlna = src.substring(src.indexOf("dlnaImageView = ImageView"), src.indexOf("root.addView(\n            dlnaImageView"))
        assertTrue("本地图片层应标记装饰", dlna.contains("A11y.decorative(this)"))
        assertTrue("画面层应标记装饰", src.contains("A11y.decorative(surfaceView)"))
        assertTrue("倒计时环应标记装饰", src.contains("ring = CountdownRingView(this).apply { A11y.decorative(this) }"))
    }

    @Test
    fun `长按捕获层对无障碍隐藏`() {
        val src = source("ReceiverActivity.kt")
        val start = src.indexOf("longPressCatcher =")
        assertTrue("未找到长按捕获层", start >= 0)
        val body = src.substring(start, minOf(src.length, start + 900))
        assertTrue(body.contains("A11y.decorative(this)"))
    }

    @Test
    fun `覆盖层打开时隐藏底页关闭时恢复`() {
        val src = source("ReceiverActivity.kt")
        val showSettings = src.substring(src.indexOf("private fun showSettings"))
            .substringBefore("private fun hideSettings")
        assertTrue(showSettings.contains("A11y.hideSubtree(waiting)"))
        assertTrue(showSettings.contains("A11y.hideSubtree(settingsEntry)"))
        val hideSettings = src.substring(src.indexOf("private fun hideSettings"))
            .substringBefore("private fun buildPromptBar")
        assertTrue(hideSettings.contains("A11y.showSubtree(waiting)"))
        assertTrue(hideSettings.contains("A11y.showSubtree(settingsEntry)"))

        val showOnboarding = src.substring(src.indexOf("private fun showOnboarding"))
            .substringBefore("private fun renderOnboardingStep")
        assertTrue(showOnboarding.contains("A11y.hideSubtree(waiting)"))
        assertTrue(showOnboarding.contains("A11y.hideSubtree(settingsEntry)"))
        val finishOnboarding = src.substring(src.indexOf("private fun finishOnboarding"))
            .substringBefore("private fun renderCode")
        assertTrue(finishOnboarding.contains("A11y.showSubtree(waiting)"))
        assertTrue(finishOnboarding.contains("A11y.showSubtree(settingsEntry)"))
    }

    @Test
    fun `投屏中移动端仍整屏只留画面`() {
        val src = source("ReceiverActivity.kt")
        val start = src.indexOf("private fun onCasting")
        assertTrue(start >= 0)
        val body = src.substring(start, minOf(src.length, start + 1200))
        assertTrue("TV / TalkBack 必须保留设置入口", body.contains("TvFocus.isTvMode(this)"))
        assertTrue(body.contains("A11y.talkBackEnabled(this)"))
        assertTrue(body.contains("settingsEntry.visibility = if (casting && !keepSettingsEntry)"))
    }

    @Test
    fun `码描述只在换码或锁定翻转时改写并播报`() {
        val src = source("ReceiverActivity.kt")
        // 面板先判描述、再改倒计时文本：每秒 tick 不再重写读屏描述（R8）
        val panel = src.substring(src.indexOf("private fun refreshCodePanel"))
            .substringBefore("private fun updateCodeA11y")
        val a11yCall = panel.indexOf("updateCodeA11y(")
        assertTrue("面板必须先判定码描述", a11yCall >= 0)
        val tick = panel.indexOf("countdownText.text =")
        assertTrue("倒计时刷新必须在码描述判定之后", tick > a11yCall)

        val a11y = src.substring(src.indexOf("private fun updateCodeA11y"))
            .substringBefore("private fun refreshBrowserRow")
        assertTrue(a11y.contains("if (a11yKey == lastCodeA11yKey) return"))
        assertTrue(a11y.contains("A11yCopy.codeRotated("))
        assertTrue(a11y.contains("A11yCopy.codeLocked()"))
        assertTrue("首帧不播报", a11y.contains("val first = lastCodeA11yKey == null"))
        val guarded = a11y.indexOf("if (a11yKey == lastCodeA11yKey) return")
        assertTrue("换码播报必须落在变更判定内", a11y.indexOf("A11y.announce(codeSlot, A11yCopy.codeRotated(") > guarded)
        assertTrue("锁定播报必须落在变更判定内", a11y.indexOf("A11y.announce(codeSlot, A11yCopy.codeLocked()") > guarded)
    }

    @Test
    fun `适配层接口保持最小且幂等`() {
        val src = source("A11y.kt")
        listOf(
            "fun describe(",
            "fun switchRow(",
            "fun decorative(",
            "fun hideSubtree(",
            "fun showSubtree(",
            "fun liveStatus(",
            "fun announce(",
            "fun talkBackEnabled(",
            "fun traversalOrder(",
        ).forEach { assertTrue("缺适配接口：$it", src.contains(it)) }
        assertTrue("announce 必须节流", src.contains("THROTTLE_MS"))
        assertFalse("适配层保持最小接口，不引 AccessibilityAction", src.contains("AccessibilityAction."))
        assertFalse("minSdk 22 仍不可用 API 28 标题语义", src.contains("isHeading"))
        assertFalse("适配层不引入 AndroidX", src.contains("androidx."))
        assertTrue("minSdk 22 应启用 API 22 遍历先后（T04908 裁决 1）", src.contains("setAccessibilityTraversalBefore("))
        assertTrue("遍历先后须成对可回退", src.contains("setAccessibilityTraversalAfter("))
    }

    @Test
    fun `待连接页焦点与无障碍遍历顺序按视觉顺序串接（T04908 裁决 1）`() {
        val tv = source("TvFocus.kt")
        assertTrue("TvFocus 缺 D-pad 链助手", tv.contains("fun chain("))
        assertTrue("D-pad 链须用 nextFocusDownId 覆盖几何搜索", tv.contains("nextFocusDownId"))
        assertTrue("D-pad 链须可回退（nextFocusUpId）", tv.contains("nextFocusUpId"))
        val recv = source("ReceiverActivity.kt")
        assertTrue("待连接页须串 显示设置→更多信息", recv.contains("TvFocus.chain(settingsEntry, moreInfoToggle)"))
        assertTrue("待连接页须经 A11y 设 显示设置→更多信息 的无障碍遍历顺序",
            recv.contains("A11y.traversalOrder(settingsEntry, moreInfoToggle)"))
        assertFalse("遍历先后统一走 A11y 适配层，界面层不得直写", recv.contains("setAccessibilityTraversalBefore("))
        assertFalse("遍历先后统一走 A11y 适配层，TvFocus 不得直写", tv.contains("setAccessibilityTraversalBefore("))
    }
}

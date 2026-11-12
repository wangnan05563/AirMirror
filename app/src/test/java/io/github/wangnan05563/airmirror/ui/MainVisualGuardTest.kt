package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PRD UI-02 源码守卫：电视端待连接页主视觉区只保留「设备名 + 投屏码（环形倒计时）+ 二维码」三件；
 * 浏览器入口收进「更多信息」，设备分级与诊断摘要收进设置页；次要入口与主区间距 ≥24dp。
 * 真机 3 米可辨与 D-pad 遍历属 R 级复验，不在本守卫范围。
 */
class MainVisualGuardTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    private fun receiver(): String =
        File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/ReceiverActivity.kt").readText()

    @Test
    fun `主视觉区恰好三件 设备名 投屏码 二维码`() {
        val src = receiver()
        assertEquals("主视觉区只能有 3 个 addView", 3, src.split("mainArea.addView(").size - 1)
        assertTrue("缺设备名", src.contains("mainArea.addView(deviceNameText"))
        assertTrue("缺投屏码槽", src.contains("mainArea.addView(codeSlot"))
        assertTrue("缺二维码", src.contains("mainArea.addView(qrView"))
        assertTrue("主视觉区要挂到待连接页", src.contains("waiting.addView(mainArea"))
    }

    @Test
    fun `浏览器入口收进更多信息 不在主视觉区`() {
        val src = receiver()
        assertFalse("浏览器地址不得进主视觉区", Regex("mainArea\\.addView\\(\\s*browserText").containsMatchIn(src))
        assertFalse("浏览器二维码不得进主视觉区", Regex("mainArea\\.addView\\(\\s*browserQr").containsMatchIn(src))
        assertTrue("浏览器地址要在更多信息内", Regex("moreInfoBody\\.addView\\(\\s*browserText").containsMatchIn(src))
        assertTrue("浏览器二维码要在更多信息内", Regex("moreInfoBody\\.addView\\(\\s*browserQr").containsMatchIn(src))
        assertTrue("要有更多信息入口", src.contains("waiting.addView(moreInfoToggle"))
        assertTrue("入口要能展开收起", src.contains("setOnClickListener { toggleMoreInfo() }"))
        assertTrue("展开收起要改 body 可见性", src.contains("moreInfoBody.visibility = if (show) View.VISIBLE else View.GONE"))
    }

    @Test
    fun `次要入口与主区间距至少 24dp`() {
        val src = receiver()
        val line = src.lines().firstOrNull { it.contains("waiting.addView(moreInfoToggle") }
        assertTrue("未找到更多信息入口的挂载行", line != null)
        assertTrue("次要入口间距要用 ≥24dp 的令牌：$line", line!!.contains("Theme.SPACE_XL_DP"))
        assertEquals("SPACE_XL_DP 必须是 24dp", 24, Theme.SPACE_XL_DP)
    }

    @Test
    fun `设备分级与诊断摘要不再占主视觉区 收进设置页`() {
        val src = receiver()
        assertFalse("gradeText 应已从待连接页移除", src.contains("gradeText"))
        assertTrue("探测摘要要落到设置页 gateLine", src.contains("gateLine.text = if (probeSummary.isBlank()) headline else"))
        assertTrue("probeLocalQuality 要把摘要存起来供设置页用", src.contains("probeSummary = summary"))
    }

    @Test
    fun `环形倒计时与投屏码字号保持不变`() {
        val src = receiver()
        assertTrue("环形倒计时要留在码槽里", src.contains("codeSlot.addView(ring"))
        assertTrue("投屏码字号走 36sp 令牌", src.contains("view.textSize = Theme.CODE_SP"))
        assertEquals("投屏码必须 36sp", 36f, Theme.CODE_SP, 0f)
        assertTrue("设备名走标题字号", src.contains("textSize = Theme.TITLE_SP"))
        assertTrue("主区正文不得小于 24sp", Theme.BODY_SP >= 24f)
    }

    @Test
    fun `投屏中整屏只留画面的行为未被破坏`() {
        val src = receiver()
        assertTrue("投屏中待连接页要让位", src.contains("waiting.visibility = View.GONE"))
        assertTrue("连接成功仍叠 3 秒设备名", src.contains("main.postDelayed(hideOverlay, OVERLAY_MS)"))
        assertTrue("浮层仍是 3 秒", src.contains("const val OVERLAY_MS = 3_000L"))
    }
}
package io.github.wangnan05563.airmirror.web

import io.github.wangnan05563.airmirror.ui.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX-04 访客页：色值只从 Theme 令牌出、成功按文件类型分档、失败给原因+下一步、
 * 上传结束后有「再传一个」。页面是纯字符串生成，这里直接断言 HTML 特征串。
 */
class GuestPageTest {

    private val page = GuestPage.html("客厅电视", "/w/abc123/upload")

    @Test
    fun `CSS 变量全部由 Theme 令牌生成`() {
        assertTrue(page.contains("--bg:${GuestPage.cssColor(Theme.BACKGROUND)}"))
        assertTrue(page.contains("--surface:${GuestPage.cssColor(Theme.SURFACE)}"))
        assertTrue(page.contains("--accent:${GuestPage.cssColor(Theme.ACCENT)}"))
        assertTrue(page.contains("--text:${GuestPage.cssColor(Theme.TEXT_PRIMARY)}"))
        assertTrue(page.contains("--text-dim:${GuestPage.cssAlpha(Theme.TEXT_SECONDARY)}"))
        assertTrue(page.contains("--text-faint:${GuestPage.cssAlpha(Theme.OFFLINE)}"))
    }

    @Test
    fun `视觉标准按 PRD 落值`() {
        assertTrue("背景必须是 #0B1F3A", page.contains("--bg:#0B1F3A"))
        assertTrue("卡片必须是 #12314F", page.contains("--surface:#12314F"))
        assertTrue("主按钮必须是 Accent #17C3B2", page.contains("--accent:#17C3B2"))
        assertTrue("按钮触控高度不足 48px", page.contains("min-height:${GuestPage.TOUCH_MIN_PX}px"))
        assertTrue("正文字号低于 14px", GuestPage.BODY_PX >= 14)
        assertTrue(page.contains("font-size:${GuestPage.BODY_PX}px"))
        // 旧主按钮蓝与旧正文灰不得残留
        assertFalse(page.contains("#4da3ff"))
        assertFalse(page.contains("#f2f6fb"))
        assertFalse(page.contains("#a9b8cc"))
        assertFalse(page.contains("#2a4a6b"))
    }

    @Test
    fun `成功文案按文件类型区分显示与播放`() {
        assertEquals(GuestPage.OK_IMAGE, GuestPage.successOf("image/png"))
        assertEquals(GuestPage.OK_MEDIA, GuestPage.successOf("video/mp4"))
        assertEquals(GuestPage.OK_MEDIA, GuestPage.successOf("audio/mpeg"))
        assertEquals(GuestPage.OK_OTHER, GuestPage.successOf("application/octet-stream"))
        assertEquals("已在大屏显示", GuestPage.OK_IMAGE)
        assertEquals("已在大屏播放", GuestPage.OK_MEDIA)
        assertTrue("成功文案要进页面脚本", page.contains("已在大屏显示") && page.contains("已在大屏播放"))
    }

    @Test
    fun `失败一律给原因加下一步`() {
        listOf(0, 400, 411, 413, 500).forEach { code ->
            val failure = GuestPage.failureOf(code)
            assertTrue("状态码 $code 缺原因", failure.reason.isNotBlank())
            assertTrue("状态码 $code 缺下一步", failure.next.isNotBlank())
            assertTrue("状态码 $code 未把原因与下一步拼成一句", failure.line.contains(failure.reason))
        }
        assertTrue(GuestPage.failureOf(413).next.contains("更小"))
        assertTrue(GuestPage.failureOf(0).next.contains("同一网络"))
        assertTrue(GuestPage.failureOf(400).next.contains("重试"))
        assertTrue("失败表要进页面脚本", page.contains("const FAIL="))
        assertTrue(page.contains("已在大屏显示") || page.contains("FAIL[0]"))
    }

    @Test
    fun `上传结束后有再传一个入口 失败有重试`() {
        assertTrue(page.contains("id=\"again\""))
        assertTrue(page.contains(GuestPage.AGAIN))
        assertTrue(page.contains("id=\"retry\""))
        assertTrue(page.contains(GuestPage.RETRY))
        assertTrue("再传一个要清空已选文件", page.contains("input.value=''"))
    }

    @Test
    fun `标题行显示设备名并按 HTML 转义`() {
        assertTrue(page.contains("<h1>客厅电视</h1>"))
        val injected = GuestPage.html("<img src=x onerror=alert(1)>", "/w/t/upload")
        assertFalse("设备名未转义，注入面直接落到页面", injected.contains("<img src=x"))
        assertTrue(injected.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertEquals("大屏", BrowserCastServer.DEFAULT_DEVICE_NAME)
    }

    @Test
    fun `不加载任何外部字体脚本或图片`() {
        listOf("http://", "https://", "<link", "@import", "<img", "src=").forEach { marker ->
            assertFalse("页面引用了外部资源：$marker", page.contains(marker))
        }
    }

    @Test
    fun `BrowserCastServer 不再手写页面与色值 只委托 GuestPage`() {
        val src = File(
            projectRoot(),
            "app/src/main/java/io/github/wangnan05563/airmirror/web/BrowserCastServer.kt",
        ).readText()
        assertTrue("页面必须由 GuestPage 生成", src.contains("GuestPage.html(deviceName()"))
        val hex = Regex("#[0-9a-fA-F]{6}\\b").findAll(src).map { it.value }.toList()
        assertTrue("服务里仍有色值字面量：$hex", hex.isEmpty())
        assertFalse("页面结构不得回流到服务里", src.contains("<!doctype html>"))
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "AirMirror-PRD-v1.1.md").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }
}

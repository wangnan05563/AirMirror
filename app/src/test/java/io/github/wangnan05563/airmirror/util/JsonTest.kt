package io.github.wangnan05563.airmirror.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun `对象 数组 标量都能读出`() {
        val root = Json.parse(
            """
            {"tag_name":"v0.1.0","draft":false,"size":123,"extra":null,
             "assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk"}]}
            """.trimIndent(),
        )
        assertEquals("v0.1.0", root.text("tag_name"))
        assertEquals(false, (root.field("draft") as JsonValue.Bool).value)
        assertEquals("123", (root.field("size") as JsonValue.Num).raw)
        assertEquals(JsonValue.Null, root.field("extra"))
        val assets = root.field("assets")!!.items()
        assertEquals(1, assets.size)
        assertEquals("https://x/a.apk", assets.first().text("browser_download_url"))
    }

    @Test
    fun `转义与中文按原文还原`() {
        val root = Json.parse("""{"body":"第一行\n第二行 \"引号\" \\ 斜杠 \u4e2d\u6587"}""")
        assertEquals("第一行\n第二行 \"引号\" \\ 斜杠 中文", root.text("body"))
    }

    @Test
    fun `空容器与前后空白`() {
        assertEquals(0, Json.parse("  {}  ").field("x").let { 0 })
        assertTrue(Json.parse("[]").items().isEmpty())
        assertNull(Json.parse("{}").text("missing"))
    }

    @Test
    fun `坏输入一律抛出 不做猜测修复`() {
        listOf(
            "",
            "{",
            "{\"a\":}",
            "[1,]",
            "{\"a\" 1}",
            "tru",
            "\"unterminated",
            "{} trailing",
        ).forEach { bad ->
            val failed = runCatching { Json.parse(bad) }.exceptionOrNull()
            assertTrue("应当拒绝: $bad", failed is JsonException)
        }
    }

    @Test
    fun `过深的嵌套以 JsonException 收口 而不是打爆栈`() {
        // 递归下降遇到几十万层会抛 StackOverflowError（Error 而非 Exception，外层 catch 不住），
        // 所以深度必须在解析器内部封顶
        val deep = "[".repeat(5_000) + "]".repeat(5_000)
        val failed = runCatching { Json.parse(deep) }.exceptionOrNull()
        assertTrue("深层嵌套应当被拒绝，实际: $failed", failed is JsonException)
    }

    @Test
    fun `正常深度的嵌套照常解析`() {
        val ok = "[".repeat(40) + "]".repeat(40)
        Json.parse(ok)
    }
}

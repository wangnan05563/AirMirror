package io.github.wangnan05563.airmirror.update

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {

    @Test
    fun `同一版本不算更新`() {
        assertFalse(UpdateVersion.isNewer("0.1.0-m0", "0.1.0-m0"))
        assertFalse(UpdateVersion.isNewer("v0.1.0-m0", "0.1.0-m0"))
    }

    @Test
    fun `主版本号提升算更新`() {
        assertTrue(UpdateVersion.isNewer("0.2.0-m0", "0.1.0-m0"))
        assertTrue(UpdateVersion.isNewer("1.0.0", "0.9.9"))
        assertFalse(UpdateVersion.isNewer("0.1.0-m0", "0.2.0-m0"))
    }

    @Test
    fun `当天 patch 序号让包在同版本内更新`() {
        assertTrue(UpdateVersion.isNewer("0.1.0-m0.20261106-001", "0.1.0-m0"))
        assertTrue(UpdateVersion.isNewer("0.1.0-m0.20261106-002", "0.1.0-m0.20261106-001"))
        assertFalse(UpdateVersion.isNewer("0.1.0-m0.20261105-001", "0.1.0-m0.20261106-001"))
    }

    @Test
    fun `数字段比数值而非字典序`() {
        // 字典序会认为 "10" < "9"，这里必须按数值比较
        assertTrue(UpdateVersion.isNewer("0.1.10", "0.1.9"))
        assertFalse(UpdateVersion.isNewer("0.1.9", "0.1.10"))
    }

    @Test
    fun `空版本与乱码不做判断`() {
        assertFalse(UpdateVersion.isNewer("", "0.1.0"))
        assertFalse(UpdateVersion.isNewer("0.1.0", ""))
        assertFalse(UpdateVersion.isNewer("   ", "0.1.0"))
    }
}

class ReleaseParserTest {

    private val feed = """
        {
          "tag_name": "v0.1.0-m0.20261106-001",
          "name": "AirMirror 0.1.0-m0.20261106-001",
          "body": "修复若干问题\n\n- 跨网直连",
          "assets": [
            {"name": "AirMirror-armeabi-v7a-release.apk", "browser_download_url": "https://x/v7a.apk"},
            {"name": "AirMirror-universal-release.apk", "browser_download_url": "https://x/universal.apk"},
            {"name": "checksums.txt", "browser_download_url": "https://x/sums.txt"}
          ]
        }
    """.trimIndent()

    @Test
    fun `默认优先 universal 包 忽略非 apk 资产`() {
        val release = ReleaseParser.parse(feed)!!
        assertEquals("v0.1.0-m0.20261106-001", release.tag)
        assertEquals("https://x/universal.apk", release.apkUrl)
        assertEquals("AirMirror-universal-release.apk", release.apkName)
        assertTrue(release.notes.contains("跨网直连"))
    }

    @Test
    fun `指定 ABI 时优先对应独立包`() {
        val release = ReleaseParser.parse(feed, preferAbi = "armeabi-v7a")!!
        assertEquals("https://x/v7a.apk", release.apkUrl)
    }

    @Test
    fun `没有 apk 资产时视为不可更新`() {
        val onlyText = """{"tag_name":"v1.0.0","assets":[{"name":"README.md","browser_download_url":"https://x/r.md"}]}"""
        assertNull(ReleaseParser.parse(onlyText))
    }

    @Test
    fun `缺 tag 或格式非法时返回 null`() {
        assertNull(ReleaseParser.parse("""{"assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk"}]}"""))
        assertNull(ReleaseParser.parse("not json"))
        assertNull(ReleaseParser.parse(""))
    }
}

class UpdateCheckerTest {

    // body 放在最后，调用处才能用尾随 lambda
    private fun checker(current: String = "0.1.0-m0", body: () -> String): UpdateChecker =
        UpdateChecker(
            fetch = object : HttpText {
                override fun get(url: String, timeoutMs: Int): String = body()
            },
            currentVersion = current,
        )

    @Test
    fun `远端版本更新时给出下载地址`() {
        val result = checker {
            """{"tag_name":"v0.1.0-m0.20261106-001","assets":[{"name":"AirMirror-universal-release.apk","browser_download_url":"https://x/u.apk"}]}"""
        }.check()
        assertTrue(result is UpdateResult.Available)
        assertEquals("https://x/u.apk", (result as UpdateResult.Available).release.apkUrl)
    }

    @Test
    fun `远端同版本或更旧时报告已最新`() {
        assertEquals(
            UpdateResult.UpToDate,
            checker {
                """{"tag_name":"v0.1.0-m0","assets":[{"name":"AirMirror-universal-release.apk","browser_download_url":"https://x/u.apk"}]}"""
            }.check(),
        )
    }

    @Test
    fun `网络失败与解析失败分别给出可执行提示`() {
        assertEquals(
            UpdateResult.Failed(UpdateChecker.NETWORK_ERROR),
            checker { throw IOException("offline") }.check(),
        )
        assertEquals(
            UpdateResult.Failed(UpdateChecker.PARSE_ERROR),
            checker { "<html>502</html>" }.check(),
        )
    }
}

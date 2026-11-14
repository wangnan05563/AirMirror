package io.github.wangnan05563.airmirror.update

import io.github.wangnan05563.airmirror.util.Json
import io.github.wangnan05563.airmirror.util.readAtMost
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * 在线更新（PRD 通用需求「菜单新增在线更新，自动检测内置仓库版本」）。
 *
 * 仓库地址内置在代码里、不开放手动输入；数据源只用 GitHub Releases（Q-6 已裁只发 GitHub Release）。
 * 版本比较与 Release 解析都是纯函数，Android 侧只负责「下载 + 拉起安装」。
 */
object UpdateVersion {
    /**
     * 版本比较。本项目 versionName 形如 `0.1.0-m0` 或 `0.1.0-m0.20261105-001`，
     * GitHub tag 可能带 `v` 前缀，所以先归一化再按「段」比较：
     * 数字段比数值、字母数字段比字典序，缺段视为更小——
     * 于是 `0.1.0-m0.20261106-001` > `0.1.0-m0`（打了当天 patch 的包更新），符合构建脚本的语义。
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val left = segments(candidate)
        val right = segments(current)
        if (left.isEmpty() || right.isEmpty()) return false
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val a = left.getOrNull(i) ?: return false
            val b = right.getOrNull(i) ?: return true
            val compared = compareSegment(a, b)
            if (compared != 0) return compared > 0
        }
        return false
    }

    private fun compareSegment(a: String, b: String): Int {
        val leftNumber = a.toLongOrNull()
        val rightNumber = b.toLongOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> 1 // 纯数字段优先于带字母的段（1.0.1 > 1.0.0-rc）
            rightNumber != null -> -1
            else -> a.compareTo(b)
        }
    }

    private fun segments(raw: String): List<String> {
        val text = raw.trim().removePrefix("v").removePrefix("V")
        if (text.isEmpty()) return emptyList()
        return text.split('.', '-', '_').filter { it.isNotEmpty() }
    }
}

data class ReleaseInfo(
    val tag: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
)

object ReleaseParser {
    /** GitHub `/releases/latest` 响应；没有 `.apk` 资产时返回 null（下载不了就不该提示可更新）。 */
    fun parse(body: String, preferAbi: String = ""): ReleaseInfo? {
        val root = try {
            Json.parse(body)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val tag = root.text("tag_name")?.takeIf { it.isNotBlank() } ?: return null
        val assets = root.field("assets")?.items().orEmpty()
        val apks = assets.mapNotNull { asset ->
            val url = asset.text("browser_download_url") ?: return@mapNotNull null
            val name = asset.text("name").orEmpty()
            if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
            Pair(name, url)
        }
        if (apks.isEmpty()) return null
        // 优先 ABI 匹配的独立包，其次 universal，最后任意一个：与构建产物的三种命名一致
        val chosen = apks.firstOrNull { preferAbi.isNotBlank() && it.first.contains(preferAbi) }
            ?: apks.firstOrNull { it.first.contains("universal") }
            ?: apks.first()
        return ReleaseInfo(
            tag = tag,
            title = root.text("name").orEmpty().ifBlank { tag },
            notes = root.text("body").orEmpty(),
            apkUrl = chosen.second,
            apkName = chosen.first,
        )
    }
}

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val release: ReleaseInfo) : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/**
 * 纯文本 GET。抽成接口是为了让「检查更新」能在 JVM 单测里跑：
 * 真实实现走 HttpURLConnection，测试用内存实现，不依赖外网。
 */
fun interface HttpText {
    fun get(url: String, timeoutMs: Int): String
}

class UrlHttpText : HttpText {
    override fun get(url: String, timeoutMs: Int): String {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            // GitHub API 要求带 User-Agent，缺了会直接 403
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("http $code")
            // Release JSON 正常只有几十 KB：不封顶的话，异常或恶意响应能一直读到 OOM
            return connection.inputStream.use { it.readAtMost(MAX_RESPONSE_BYTES) }.toString(Charsets.UTF_8)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private companion object {
        const val USER_AGENT = "AirMirror-Android"

        /** GitHub Release JSON 的上限：正常几十 KB，1MB 已足够容纳较长的 changelog */
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}

class UpdateChecker(
    private val fetch: HttpText,
    private val currentVersion: String,
    private val apiUrl: String = DEFAULT_API,
    private val preferAbi: String = "",
    private val timeoutMs: Int = 8_000,
) {
    fun check(): UpdateResult {
        val body = try {
            fetch.get(apiUrl, timeoutMs)
        } catch (e: IOException) {
            return UpdateResult.Failed(NETWORK_ERROR)
        }
        val release = ReleaseParser.parse(body, preferAbi)
            ?: return UpdateResult.Failed(PARSE_ERROR)
        return if (UpdateVersion.isNewer(release.tag, currentVersion)) {
            UpdateResult.Available(release)
        } else {
            UpdateResult.UpToDate
        }
    }

    companion object {
        const val DEFAULT_API = "https://api.github.com/repos/wangnan05563/AirMirror/releases/latest"
        const val NETWORK_ERROR = "连不上更新服务，请检查网络后重试"
        const val PARSE_ERROR = "更新服务返回了无法识别的内容，请稍后再试"
    }
}

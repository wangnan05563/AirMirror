package io.github.wangnan05563.airmirror.crossnet

import io.github.wangnan05563.airmirror.util.readAtMost
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 信令通道（PRD FS-04 第 3 步）。
 *
 * 服务端只需做「按会话 id 存转发」，不理解候选语义、不接触媒体（PRD FS-04 安全约束）。
 * 请求体一律是 [CrossNetProtocol] 的二进制消息，服务端当成不透明字节串即可，
 * 这样信令服务即使被第三方托管，也拿不到投屏码原文（只有 8 字节哈希）与任何画面内容。
 *
 * 采用「短连接 + 轮询」而不是长连接：信令只在建连的十几秒内使用，
 * 长连接会引入心跳、重连、代理超时一整类问题，而收益只有几百毫秒。
 */
interface SignalingTransport {
    data class Response(val code: Int, val body: ByteArray)

    /** 网络失败或非 2xx 以外的传输异常一律抛出 [IOException]，由上层计入「信令不可用」。 */
    fun post(path: String, body: ByteArray, timeoutMs: Int): Response

    fun get(path: String, timeoutMs: Int): Response
}

/** 真实 HTTP 实现。超时同时约束连接与读，避免轮询把 15 秒的匹配预算耗在一次挂起的请求上。 */
class HttpSignalingTransport(
    private val baseUrl: String,
) : SignalingTransport {
    init {
        val valid = isValidBaseUrl(baseUrl)
        require(valid) { "signaling base url is invalid: $baseUrl" }
    }

    override fun post(path: String, body: ByteArray, timeoutMs: Int): SignalingTransport.Response =
        exchange(path, timeoutMs) { connection ->
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", CONTENT_TYPE)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }

    override fun get(path: String, timeoutMs: Int): SignalingTransport.Response =
        exchange(path, timeoutMs) { connection -> connection.requestMethod = "GET" }

    private fun exchange(
        path: String,
        timeoutMs: Int,
        configure: (HttpURLConnection) -> Unit,
    ): SignalingTransport.Response {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", CONTENT_TYPE)
            configure(connection)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            // 信令消息都是几百字节的控制消息：读总量必须封顶，readTimeout 只管单次等待，
            // 服务器（或中间人）持续回字节就能把堆撑爆
            val body = stream?.use { it.readAtMost(MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            if (code !in 200..299) {
                // 5xx/4xx 也算信令不可用：让上层按「连续失败」收口，而不是把 500 当成「还没匹配上」
                throw IOException("signaling http $code")
            }
            return SignalingTransport.Response(code, body)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        private const val CONTENT_TYPE = "application/octet-stream"

        /** 单条信令响应的上限：候选列表也才几百字节，64KB 已经非常宽松 */
        private const val MAX_RESPONSE_BYTES = 64 * 1024

        /**
         * 只接受 http(s) 且带主机名。空串表示「未配置」——跨网整体关闭，
         * 由调用方给出「请在同一网络内投屏」的可执行提示，不做静默失败。
         */
        fun isValidBaseUrl(raw: String): Boolean {
            val text = raw.trim()
            if (text.isEmpty()) return false
            if (!text.startsWith("http://") && !text.startsWith("https://")) return false
            val host = text.removePrefix("http://").removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
            return host.isNotBlank() && host.any { it.isLetterOrDigit() }
        }
    }
}

/** 信令交互的失败口径，文案集中在 [CrossNetPrompts]，不在这里拼用户可见字符串。 */
class SignalingException(val reason: FailureReason, message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * 发送端：MATCH → MATCHED → 上报候选 → 拉取对端候选。
 * 接收端复用同一套接口：先 [openSession] 声明码哈希，再等待发送端报到。
 *
 * 时钟与轮询间隔都可注入：单测不需要 sleep 15 秒，也不会因为机器慢而抖动。
 */
class SignalingClient(
    private val transport: SignalingTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = 400,
    private val failureLimit: Int = 3,
) {
    /**
     * 发送端：反复提交直到有一台接收端认领这个码。
     * 服务端返回 202 表示「还没人认领」，属于正常等待；网络异常/5xx 才计入失败。
     */
    fun matchSender(
        codeHash: ByteArray,
        timeoutMs: Long,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): String {
        require(timeoutMs > 0) { "match timeout must be positive" }
        val deadline = now() + timeoutMs
        val request = CrossNetProtocol.encode(
            CrossNetProtocol.Message(
                type = CrossNetProtocol.Type.MATCH,
                role = CrossNetProtocol.Role.SENDER,
                codeHash = codeHash,
            ),
        )
        var failures = 0
        while (now() < deadline) {
            val response = try {
                transport.post(PATH_MATCH, request, minOf(REQUEST_TIMEOUT_MS.toLong(), timeoutMs).toInt())
            } catch (e: IOException) {
                failures++
                if (failures >= failureLimit) {
                    throw SignalingException(FailureReason.SIGNALING_UNREACHABLE, "signaling match failed", e)
                }
                sleep(pollIntervalMs)
                continue
            }
            // 只有真正收到响应才清零：把清零放进 try 会让「每次失败都当成第一次」，永远到不了上限
            failures = 0
            if (response.code == 200 && response.body.isNotEmpty()) {
                val matched = decodeOrThrow(response.body)
                if (matched.type == CrossNetProtocol.Type.MATCHED && matched.sessionId.isNotEmpty()) {
                    return matched.sessionId
                }
            }
            // 202（等待中）与「格式不符的 200」都按「还没匹配上」处理，继续轮询到超时
            sleep(pollIntervalMs)
        }
        throw SignalingException(FailureReason.MATCH_TIMEOUT, "signaling match timeout")
    }

    /**
     * 接收端：登记本机当前可受理的码哈希，拿到会话 id 用于后续交换。
     * 服务端按码哈希建立「等待队列」，发送端 MATCH 命中即配对。
     */
    fun openSession(codeHash: ByteArray, timeoutMs: Long): String {
        val request = CrossNetProtocol.encode(
            CrossNetProtocol.Message(
                type = CrossNetProtocol.Type.MATCH,
                role = CrossNetProtocol.Role.RECEIVER,
                codeHash = codeHash,
            ),
        )
        val response = try {
            transport.post(PATH_MATCH, request, timeoutMs.toInt())
        } catch (e: IOException) {
            throw SignalingException(FailureReason.SIGNALING_UNREACHABLE, "signaling open failed", e)
        }
        if (response.code == 200 && response.body.isNotEmpty()) {
            val matched = decodeOrThrow(response.body)
            if (matched.sessionId.isNotEmpty()) return matched.sessionId
        }
        throw SignalingException(FailureReason.SIGNALING_UNREACHABLE, "signaling open refused")
    }

    fun publishCandidates(sessionId: String, role: CrossNetProtocol.Role, candidates: List<CrossNetProtocol.Candidate>) {
        require(candidates.isNotEmpty()) { "no candidates to publish" }
        val request = CrossNetProtocol.encode(
            CrossNetProtocol.Message(
                type = CrossNetProtocol.Type.CANDIDATES,
                role = role,
                sessionId = sessionId,
                candidates = candidates,
            ),
        )
        try {
            transport.post(PATH_CANDIDATES, request, REQUEST_TIMEOUT_MS)
        } catch (e: IOException) {
            throw SignalingException(FailureReason.SIGNALING_UNREACHABLE, "publish candidates failed", e)
        }
    }

    /** 拉取对端候选；超时按 [FailureReason.MATCH_TIMEOUT] 收口，因为用户看到的是「一直没连上」。 */
    fun awaitPeerCandidates(
        sessionId: String,
        role: CrossNetProtocol.Role,
        timeoutMs: Long,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): List<CrossNetProtocol.Candidate> {
        require(timeoutMs > 0) { "candidate timeout must be positive" }
        val deadline = now() + timeoutMs
        val peerRole = if (role == CrossNetProtocol.Role.SENDER) {
            CrossNetProtocol.Role.RECEIVER
        } else {
            CrossNetProtocol.Role.SENDER
        }
        var failures = 0
        while (now() < deadline) {
            val response = try {
                transport.get(
                    "$PATH_CANDIDATES?session=$sessionId&role=${peerRole.code}",
                    minOf(REQUEST_TIMEOUT_MS.toLong(), timeoutMs).toInt(),
                )
            } catch (e: IOException) {
                failures++
                if (failures >= failureLimit) {
                    throw SignalingException(FailureReason.SIGNALING_UNREACHABLE, "signaling candidates failed", e)
                }
                sleep(pollIntervalMs)
                continue
            }
            failures = 0
            if (response.code == 200 && response.body.isNotEmpty()) {
                val message = decodeOrThrow(response.body)
                if (message.candidates.isNotEmpty()) return message.candidates
            }
            sleep(pollIntervalMs)
        }
        throw SignalingException(FailureReason.MATCH_TIMEOUT, "signaling candidates timeout")
    }

    /** 主动放弃：让服务端尽快释放会话，而不是等它自己超时。 */
    fun abort(sessionId: String, role: CrossNetProtocol.Role, reason: FailureReason) {
        val request = CrossNetProtocol.encode(
            CrossNetProtocol.Message(
                type = CrossNetProtocol.Type.ABORT,
                role = role,
                sessionId = sessionId,
                reason = reason,
            ),
        )
        runCatching { transport.post(PATH_ABORT, request, REQUEST_TIMEOUT_MS) }
    }

    private fun decodeOrThrow(body: ByteArray): CrossNetProtocol.Message = try {
        CrossNetProtocol.decode(body)
    } catch (e: IllegalArgumentException) {
        // 服务端返回了看不懂的字节：按不可用处理，不把它当成「还没匹配上」无限轮询
        throw SignalingException(FailureReason.SIGNALING_UNREACHABLE, "signaling reply malformed", e)
    }

    companion object {
        const val PATH_MATCH = "/v1/match"
        const val PATH_CANDIDATES = "/v1/candidates"
        const val PATH_ABORT = "/v1/abort"
        private const val REQUEST_TIMEOUT_MS = 2_000
    }
}

/**
 * 信令端点配置。未配置时跨网整体关闭——不是「静默失败」，
 * 而是由 UI 明确告知「当前只能在同一网络内投屏」。
 */
object SignalingSettings {
    fun normalize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (!HttpSignalingTransport.isValidBaseUrl(text)) return ""
        return text.trimEnd('/')
    }

    fun enabled(raw: String): Boolean = normalize(raw).isNotEmpty()
}

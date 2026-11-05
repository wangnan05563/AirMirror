package io.github.wangnan05563.airmirror.crossnet

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信令客户端语义测试：用真实 TCP 上的极简 HTTP 服务端，验证「等待 / 超时 / 连续失败 / 二进制透传」四条口径。
 * 不用 JDK 的 HttpServer，是为了让测试不依赖模块可见性，也便于精确控制响应码。
 */
class SignalingTest {

    @Test
    fun `发送端遇到 202 继续轮询 收到 MATCHED 即返回会话`() {
        val session = CrossNetProtocol.newSessionId()
        val attempts = AtomicInteger()
        val server = FakeHttpServer { request ->
            assertEquals(SignalingClient.PATH_MATCH, request.path)
            val message = CrossNetProtocol.decode(request.body)
            assertEquals(CrossNetProtocol.Type.MATCH, message.type)
            assertEquals(CrossNetProtocol.Role.SENDER, message.role)
            // 第 3 次才「有接收端认领」，前两次都是等待中
            if (attempts.incrementAndGet() < 3) {
                FakeHttpServer.Reply(202, ByteArray(0))
            } else {
                FakeHttpServer.Reply(
                    200,
                    CrossNetProtocol.encode(
                        CrossNetProtocol.Message(
                            CrossNetProtocol.Type.MATCHED,
                            CrossNetProtocol.Role.RECEIVER,
                            session,
                        ),
                    ),
                )
            }
        }
        server.use {
            val client = SignalingClient(
                HttpSignalingTransport("http://127.0.0.1:${server.port}"),
                pollIntervalMs = 10,
            )
            val id = client.matchSender(CrossNetProtocol.codeHash("123456"), timeoutMs = 5_000)
            assertEquals(session, id)
            assertEquals(3, attempts.get())
        }
    }

    @Test
    fun `服务端持续 5xx 时连续 3 次失败即判信令不可用`() {
        val server = FakeHttpServer { FakeHttpServer.Reply(503, ByteArray(0)) }
        server.use {
            val client = SignalingClient(
                HttpSignalingTransport("http://127.0.0.1:${server.port}"),
                pollIntervalMs = 10,
            )
            val error = runCatching {
                client.matchSender(CrossNetProtocol.codeHash("123456"), timeoutMs = 5_000)
            }.exceptionOrNull()
            assertTrue(error is SignalingException)
            assertEquals(FailureReason.SIGNALING_UNREACHABLE, (error as SignalingException).reason)
        }
    }

    @Test
    fun `一直 202 到超时按匹配超时收口`() {
        val server = FakeHttpServer { FakeHttpServer.Reply(202, ByteArray(0)) }
        server.use {
            val client = SignalingClient(
                HttpSignalingTransport("http://127.0.0.1:${server.port}"),
                pollIntervalMs = 10,
            )
            val error = runCatching {
                client.matchSender(CrossNetProtocol.codeHash("123456"), timeoutMs = 300)
            }.exceptionOrNull()
            assertTrue(error is SignalingException)
            assertEquals(FailureReason.MATCH_TIMEOUT, (error as SignalingException).reason)
        }
    }

    @Test
    fun `候选上报与拉取都用同一套二进制消息`() {
        val session = CrossNetProtocol.newSessionId()
        val peerCandidates = listOf(
            CrossNetProtocol.Candidate("203.0.113.9", 51234, CrossNetProtocol.CandidateKind.SRFLX, 1),
        )
        val published = ArrayList<CrossNetProtocol.Candidate>()
        val server = FakeHttpServer { request ->
            if (request.path == SignalingClient.PATH_CANDIDATES && request.method == "POST") {
                val message = CrossNetProtocol.decode(request.body)
                assertEquals(session, message.sessionId)
                published += message.candidates
                FakeHttpServer.Reply(200, ByteArray(0))
            } else if (request.path.startsWith(SignalingClient.PATH_CANDIDATES)) {
                assertEquals("GET", request.method)
                assertTrue(request.path.contains("role=2"))
                FakeHttpServer.Reply(
                    200,
                    CrossNetProtocol.encode(
                        CrossNetProtocol.Message(
                            CrossNetProtocol.Type.CANDIDATES,
                            CrossNetProtocol.Role.RECEIVER,
                            session,
                            candidates = peerCandidates,
                        ),
                    ),
                )
            } else {
                FakeHttpServer.Reply(200, ByteArray(0))
            }
        }
        server.use {
            val client = SignalingClient(
                HttpSignalingTransport("http://127.0.0.1:${server.port}"),
                pollIntervalMs = 10,
            )
            val mine = listOf(CrossNetProtocol.Candidate("192.168.1.5", 41524))
            client.publishCandidates(session, CrossNetProtocol.Role.SENDER, mine)
            assertEquals(mine, published)
            val peers = client.awaitPeerCandidates(session, CrossNetProtocol.Role.SENDER, timeoutMs = 3_000)
            assertEquals(peerCandidates, peers)
        }
    }

    @Test
    fun `候选一直是 204 时按超时收口`() {
        val server = FakeHttpServer { FakeHttpServer.Reply(204, ByteArray(0)) }
        server.use {
            val client = SignalingClient(
                HttpSignalingTransport("http://127.0.0.1:${server.port}"),
                pollIntervalMs = 10,
            )
            val error = runCatching {
                client.awaitPeerCandidates(
                    CrossNetProtocol.newSessionId(),
                    CrossNetProtocol.Role.RECEIVER,
                    timeoutMs = 300,
                )
            }.exceptionOrNull()
            assertTrue(error is SignalingException)
            assertEquals(FailureReason.MATCH_TIMEOUT, (error as SignalingException).reason)
        }
    }

    @Test
    fun `放弃会话会把原因码发给服务端`() {
        val received = ArrayList<String>()
        val server = FakeHttpServer { request ->
            if (request.path == SignalingClient.PATH_ABORT) {
                val message = CrossNetProtocol.decode(request.body)
                received += "${message.type}:${message.reason?.code}"
            }
            FakeHttpServer.Reply(200, ByteArray(0))
        }
        server.use {
            val client = SignalingClient(HttpSignalingTransport("http://127.0.0.1:${server.port}"))
            client.abort(CrossNetProtocol.newSessionId(), CrossNetProtocol.Role.SENDER, FailureReason.PUNCH_BLOCKED)
            assertEquals(listOf("ABORT:3"), received)
        }
    }

    @Test
    fun `信令端点只接受带主机的 http-s 地址`() {
        assertTrue(HttpSignalingTransport.isValidBaseUrl("http://192.168.1.5:8080"))
        assertTrue(HttpSignalingTransport.isValidBaseUrl("https://signal.example.com"))
        assertTrue(!HttpSignalingTransport.isValidBaseUrl(""))
        assertTrue(!HttpSignalingTransport.isValidBaseUrl("signal.example.com"))
        assertTrue(!HttpSignalingTransport.isValidBaseUrl("ftp://signal.example.com"))
        assertTrue(!HttpSignalingTransport.isValidBaseUrl("http://"))
        assertEquals("https://signal.example.com", SignalingSettings.normalize(" https://signal.example.com/ "))
        assertEquals("", SignalingSettings.normalize("signal.example.com"))
        assertTrue(SignalingSettings.enabled("http://10.0.0.2:9000"))
        assertTrue(!SignalingSettings.enabled(""))
    }

    /** 极简 HTTP/1.1 服务端：足够覆盖 HttpURLConnection 的 POST/GET 行为。 */
    class FakeHttpServer(private val handler: (Request) -> Reply) : Closeable {
        data class Request(val method: String, val path: String, val body: ByteArray)
        data class Reply(val code: Int, val body: ByteArray)

        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val done = CountDownLatch(1)
        private val thread = Thread({ loop() }, "fake-http").apply { isDaemon = true }
        val port: Int get() = server.localPort

        init {
            thread.start()
        }

        override fun close() {
            done.countDown()
            runCatching { server.close() }
            thread.join(1_000)
        }

        private fun loop() {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return
                runCatching { serve(socket) }
                runCatching { socket.close() }
            }
        }

        private fun serve(socket: Socket) {
            val input = socket.getInputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            val length = lines.drop(1)
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(body, read, length - read)
                if (count < 0) return
                read += count
            }
            val reply = handler(Request(method, path, body))
            val out = socket.getOutputStream()
            val head2 = "HTTP/1.1 ${reply.code} X\r\nContent-Length: ${reply.body.size}\r\n" +
                "Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
            out.write(head2.toByteArray(Charsets.US_ASCII))
            out.write(reply.body)
            out.flush()
        }

        private fun readHead(input: java.io.InputStream): String? {
            val buffer = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val value = input.read()
                if (value < 0) return null
                buffer.write(value)
                matched = when {
                    value == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                    value == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                    else -> 0
                }
            }
            return buffer.toByteArray().toString(Charsets.US_ASCII)
        }
    }
}

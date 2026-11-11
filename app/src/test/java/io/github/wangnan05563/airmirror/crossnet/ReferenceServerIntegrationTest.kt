package io.github.wangnan05563.airmirror.crossnet

import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * 真实链路的端到端证据：参考信令服务（scripts/signal-server.py）+ 两个 CrossNetConnector。
 *
 * 与 `CrossNetConnectorTest` 的区别：那里用内存 SignalingTransport，只证明编排；
 * 这里走真实 HTTP 匹配/候选转发与真实 UDP 打洞，证明客户端契约与服务端实现真的对得上。
 * Python 或脚本不存在时跳过，不让缺少可选工具的环境把全量单测拖红。
 */
class ReferenceServerIntegrationTest {

    @Test
    fun `参考信令服务与双端连接器能完成匹配 打洞与数据传输`() {
        val python = findPython()
        val script = findScript()
        Assume.assumeTrue("需要 python 与 scripts/signal-server.py", python != null && script != null)
        val port = ServerSocket(0).use { it.localPort }
        val server = ProcessBuilder(
            python!!,
            script!!.absolutePath,
            "--host", "127.0.0.1",
            "--port", port.toString(),
        ).redirectErrorStream(true).start()
        try {
            waitUntilListening(port, server)
            val base = "http://127.0.0.1:$port"
            val signaling = SignalingClient(HttpSignalingTransport(base), pollIntervalMs = 50)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val receiver = pool.submit(
                    Callable { connector(CrossNetProtocol.Role.RECEIVER, signaling).connectReceiver("123456") },
                )
                Thread.sleep(300)
                val sender = pool.submit(
                    Callable { connector(CrossNetProtocol.Role.SENDER, signaling).connectSender("123456") },
                )
                val receiverOutcome = receiver.get(20, TimeUnit.SECONDS)
                val senderOutcome = sender.get(20, TimeUnit.SECONDS)

                assertTrue("接收端应建连：${receiverOutcome.prompt}", receiverOutcome.connected)
                assertTrue("发送端应建连：${senderOutcome.prompt}", senderOutcome.connected)
                assertEquals(senderOutcome.sessionId, receiverOutcome.sessionId)

                val payload = "real-signaling-e2e".toByteArray(Charsets.UTF_8)
                senderOutcome.link!!.output.write(payload)
                assertEquals(payload.toList(), readExactly(receiverOutcome.link!!, payload.size).toList())
                senderOutcome.link.close()
                receiverOutcome.link.close()
            } finally {
                pool.shutdownNow()
            }
        } finally {
            server.destroyForcibly()
            server.waitFor(3, TimeUnit.SECONDS)
        }
    }

    private fun connector(role: CrossNetProtocol.Role, signaling: SignalingClient): CrossNetConnector =
        CrossNetConnector(
            role = role,
            client = signaling,
            socketFactory = { DatagramSocket(0, InetAddress.getByName("127.0.0.1")) },
            // 生产采集会过滤回环；单机端到端测试必须显式给出环回候选，否则两端永远打不到
            collect = { socket ->
                listOf(CrossNetProtocol.Candidate("127.0.0.1", socket.localPort))
            },
            timeouts = CrossNetConnector.Timeouts(
                matchMs = 5_000,
                candidatesMs = 5_000,
                punchMs = 3_000,
                punchIntervalMs = 50,
            ),
        )

    private fun readExactly(tunnel: UdpTunnel, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        tunnel.setReadTimeout(8_000)
        while (offset < size) {
            val count = tunnel.input.read(result, offset, size - offset)
            if (count < 0) throw IllegalStateException("stream ended at $offset/$size")
            offset += count
        }
        return result
    }

    private fun findPython(): String? {
        val candidates = listOf("python", "python3")
        return candidates.firstOrNull { name ->
            runCatching {
                val process = ProcessBuilder(name, "--version").redirectErrorStream(true).start()
                val ok = process.waitFor(3, TimeUnit.SECONDS)
                if (!ok) process.destroyForcibly()
                ok && process.exitValue() == 0
            }.getOrDefault(false)
        }
    }

    private fun findScript(): File? {
        val roots = listOf(File("scripts/signal-server.py"), File("../scripts/signal-server.py"))
        return roots.firstOrNull { it.isFile }
    }

    private fun waitUntilListening(port: Int, server: Process) {
        // 6 秒在满载机器上不够：全量单测与 lint 并行时 Python 启动会被拖到 10 秒以上，
        // 于是这条端到端用例偶发红。给 20 秒，并在服务进程提前退出时立刻失败、带上它的输出，
        // 免得真出错（参数变了、端口被占）也被表现成「等超时」。
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val connected = runCatching { Socket("127.0.0.1", port).use { true } }.getOrDefault(false)
            if (connected) return
            if (!server.isAlive) {
                val output = runCatching {
                    server.inputStream.bufferedReader().readText().take(400)
                }.getOrDefault("")
                throw AssertionError("reference signaling server exited before listening: $output")
            }
            Thread.sleep(100)
        }
        throw AssertionError("reference signaling server did not start on $port within 20s")
    }
}

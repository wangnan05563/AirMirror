package io.github.wangnan05563.airmirror.crossnet

import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpPuncherTest {

    private fun socket(): DatagramSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))

    @Test
    fun `两个端点在环回口上互打成功`() {
        val left = socket()
        val right = socket()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val session = CrossNetProtocol.newSessionId()
            val leftPuncher = UdpPuncher(left, session, CrossNetProtocol.Role.SENDER, "123456")
            val rightPuncher = UdpPuncher(right, session, CrossNetProtocol.Role.RECEIVER, "123456")
            val leftPeers = listOf(CrossNetProtocol.Candidate("127.0.0.1", right.localPort))
            val rightPeers = listOf(CrossNetProtocol.Candidate("127.0.0.1", left.localPort))
            val results = pool.invokeAll(
                listOf(
                    Callable { leftPuncher.punch(leftPeers, timeoutMs = 3_000, intervalMs = 20) },
                    Callable { rightPuncher.punch(rightPeers, timeoutMs = 3_000, intervalMs = 20) },
                ),
            ).map { it.get(10, TimeUnit.SECONDS) }

            assertTrue("发起端应打通", results[0].connected)
            assertTrue("接收端应打通", results[1].connected)
            assertTrue(results[0].probes >= 1)
            assertTrue(results[0].peer!!.startsWith("127.0.0.1:"))
        } finally {
            left.close()
            right.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `投屏码不一致时探针被丢弃`() {
        val left = socket()
        val right = socket()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val session = CrossNetProtocol.newSessionId()
            val leftPuncher = UdpPuncher(left, session, CrossNetProtocol.Role.SENDER, "123456")
            val rightPuncher = UdpPuncher(right, session, CrossNetProtocol.Role.RECEIVER, "000000")
            val leftPeers = listOf(CrossNetProtocol.Candidate("127.0.0.1", right.localPort))
            val rightPeers = listOf(CrossNetProtocol.Candidate("127.0.0.1", left.localPort))
            val results = pool.invokeAll(
                listOf(
                    Callable { leftPuncher.punch(leftPeers, timeoutMs = 200, intervalMs = 20) },
                    Callable { rightPuncher.punch(rightPeers, timeoutMs = 200, intervalMs = 20) },
                ),
            ).map { it.get(5, TimeUnit.SECONDS) }

            assertFalse(results[0].connected)
            assertFalse(results[1].connected)
        } finally {
            left.close()
            right.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `对端不回应时按超时返回失败`() {
        val left = socket()
        val silent = socket()
        try {
            val session = CrossNetProtocol.newSessionId()
            val puncher = UdpPuncher(left, session, CrossNetProtocol.Role.SENDER, "123456")
            val start = System.currentTimeMillis()
            val result = puncher.punch(
                listOf(CrossNetProtocol.Candidate("127.0.0.1", silent.localPort)),
                timeoutMs = 200,
                intervalMs = 20,
            )
            assertFalse(result.connected)
            assertTrue(result.elapsedMs >= 150)
            assertTrue(result.probes >= 1)
            assertEquals("没有对端时不能凭空给出 peer", null, result.peer)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("打洞超时必须按传入值收口，不能无限等待", elapsed < 2_000)
        } finally {
            left.close()
            silent.close()
        }
    }
}

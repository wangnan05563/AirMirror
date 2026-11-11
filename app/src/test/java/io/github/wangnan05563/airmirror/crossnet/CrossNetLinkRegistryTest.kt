package io.github.wangnan05563.airmirror.crossnet

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨网链路交接的两条不变量：
 * 1. 一条隧道只能交给一个会话——重复取会让两个读者抢同一队列；
 * 2. 没人接手的隧道必须被关掉——否则 socket 与收发线程一直挂着。
 *
 * 用真实 [DatagramSocket] 而不是 mock：这里要证的正是「close 之后系统资源真的释放了」。
 * 隧道不 [UdpTunnel.start]，所以不会起收发线程，用例之间不会互相干扰。
 */
class CrossNetLinkRegistryTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun outcome(): Pair<CrossNetConnector.Outcome, DatagramSocket> {
        val socket = DatagramSocket(0, loopback)
        val tunnel = UdpTunnel(socket, InetSocketAddress(loopback, 9))
        val outcome = CrossNetConnector.Outcome(
            state = CrossNetState.DIRECT,
            prompt = "",
            failure = null,
            link = tunnel,
            credential = "123456",
        )
        return outcome to socket
    }

    @Test
    fun `一条隧道只能被取走一次`() {
        val (outcome, socket) = outcome()
        val token = CrossNetLinkRegistry.put(outcome)
        try {
            assertNotNull("第一次必须取得到", CrossNetLinkRegistry.take(token))
            assertNull("第二次必须取不到，否则两个读者会抢同一队列", CrossNetLinkRegistry.take(token))
        } finally {
            socket.close()
            CrossNetLinkRegistry.discard(token)
        }
    }

    @Test
    fun `放弃交接必须真的关掉链路`() {
        val (outcome, socket) = outcome()
        val token = CrossNetLinkRegistry.put(outcome)
        assertFalse(socket.isClosed)
        CrossNetLinkRegistry.discard(token)
        assertTrue("没人接手的隧道必须释放 socket，否则 NAT 映射与端口一直占着", socket.isClosed)
        assertNull("放弃后不能再被取走", CrossNetLinkRegistry.take(token))
    }

    @Test
    fun `没有链路的建连结果不允许登记`() {
        val failed = CrossNetConnector.Outcome(
            state = CrossNetState.FAILED,
            prompt = "打洞失败",
            failure = FailureReason.PUNCH_BLOCKED,
        )
        var rejected = false
        try {
            CrossNetLinkRegistry.put(failed)
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("没有链路就没有可交接的东西，登记它只会让采集服务拿到一个空会话", rejected)
        assertEquals("被拒绝的登记不能留下残留", 0, CrossNetLinkRegistry.size())
    }
}

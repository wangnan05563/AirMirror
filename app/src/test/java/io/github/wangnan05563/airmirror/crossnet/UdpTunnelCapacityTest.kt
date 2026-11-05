package io.github.wangnan05563.airmirror.crossnet

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隧道容量不变量：这些数字决定「一帧媒体要几轮往返」，改动必须显式过这一关。
 * 不做耗时的端到端计时断言（环回测不出跨网 RTT 差异，容易变成 flaky 用例）。
 */
class UdpTunnelCapacityTest {

    @Test
    fun `在途窗口至少覆盖一个中等关键帧`() {
        val inFlightBytes = UdpTunnel.MSS * UdpTunnel.WINDOW
        assertTrue(
            "窗口只有 ${inFlightBytes}B：镜像关键帧常有几十 KB，会导致单帧多轮往返",
            inFlightBytes >= 64 * 1024,
        )
    }

    @Test
    fun `乱序缓存不小于窗口`() {
        assertTrue(
            "乱序缓存小于窗口时，整窗乱序会被自己挤掉并触发重传风暴",
            UdpTunnel.MAX_OUT_OF_ORDER >= UdpTunnel.WINDOW,
        )
    }

    @Test
    fun `单帧不超过常见以太网 MTU`() {
        assertTrue("分片加头不能超过 1500", UdpTunnel.HEADER + UdpTunnel.MSS <= 1500)
    }
}

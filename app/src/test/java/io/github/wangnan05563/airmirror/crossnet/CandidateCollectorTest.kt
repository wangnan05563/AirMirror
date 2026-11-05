package io.github.wangnan05563.airmirror.crossnet

import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateCollectorTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun `HOST 候选端口必须等于打洞 socket 的本地端口`() {
        val socket = DatagramSocket(0, loopback)
        try {
            val candidates = CandidateCollector.collect(
                socket,
                CandidateCollector.Config(),
                query = { _, _, _, _ -> null },
            )
            assertTrue("不能把回环地址发给对端", candidates.none { it.address.startsWith("127.") })
            assertTrue("端口换了就等于换了 NAT 映射", candidates.all { it.port == socket.localPort })
            assertTrue(candidates.all { it.kind == CrossNetProtocol.CandidateKind.HOST })
            assertTrue(candidates.size <= CrossNetProtocol.MAX_CANDIDATES)
        } finally {
            socket.close()
        }
    }

    @Test
    fun `STUN 成功时追加公网候选`() {
        val socket = DatagramSocket(0, loopback)
        try {
            val candidates = CandidateCollector.collect(
                socket,
                CandidateCollector.Config(stunHost = "stun.example.com"),
                query = { _, _, _, _ -> StunCodec.MappedAddress("203.0.113.9", 51_234) },
            )
            val srflx = candidates.filter { it.kind == CrossNetProtocol.CandidateKind.SRFLX }
            assertEquals(1, srflx.size)
            assertEquals("203.0.113.9", srflx.first().address)
            assertEquals(51_234, srflx.first().port)
        } finally {
            socket.close()
        }
    }

    @Test
    fun `公网映射与本机地址相同时不重复上报`() {
        val socket = DatagramSocket(0, loopback)
        try {
            val host = CandidateCollector.localHosts().firstOrNull()
            if (host == null) {
                // 只有回环的机器无法构造这个场景，跳过而不是假装验证
                return
            }
            val candidates = CandidateCollector.collect(
                socket,
                CandidateCollector.Config(stunHost = "stun.example.com"),
                query = { _, _, _, _ -> StunCodec.MappedAddress(host, socket.localPort) },
            )
            assertEquals(1, candidates.count { it.address == host && it.port == socket.localPort })
        } finally {
            socket.close()
        }
    }

    @Test
    fun `STUN 失败时只给 HOST 候选 不中断建连`() {
        val socket = DatagramSocket(0, loopback)
        try {
            val candidates = CandidateCollector.collect(
                socket,
                CandidateCollector.Config(stunHost = "stun.example.com"),
                query = { _, _, _, _ -> throw java.io.IOException("stun down") },
            )
            assertTrue(candidates.none { it.kind == CrossNetProtocol.CandidateKind.SRFLX })
            assertTrue(candidates.all { it.kind == CrossNetProtocol.CandidateKind.HOST })
        } finally {
            socket.close()
        }
    }

    @Test
    fun `本机地址列表不含回环与链路本地`() {
        val hosts = CandidateCollector.localHosts()
        assertTrue(hosts.none { it.startsWith("127.") })
        assertTrue(hosts.none { it.startsWith("169.254.") })
        assertTrue(hosts.all { Subnet.isIpv4(it) })
        assertEquals("同一条地址不能重复上报", hosts.size, hosts.toSet().size)
    }
}

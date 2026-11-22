package io.github.wangnan05563.airmirror.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

/** T06114：失败来源只允许落掩码，IPv4/IPv6、端口、方括号与非法输入都要有确定结果。 */
class PeerMaskTest {

    @Test
    fun ipv4KeepsOnlyTheFirstThreeSegments() {
        assertEquals("192.168.1.*", PeerMask.mask("192.168.1.42"))
        assertEquals("10.0.0.*", PeerMask.mask("10.0.0.255"))
    }

    @Test
    fun ipv4WithPortIsStillMaskedAsIpv4() {
        assertEquals("192.168.1.*", PeerMask.mask("192.168.1.42:54321"))
        assertEquals("", PeerMask.mask("300.1.2.3:80"))
    }

    @Test
    fun ipv4MappedIpv6MasksTheEmbeddedIpv4Tail() {
        assertEquals("192.168.1.*", PeerMask.mask("::ffff:192.168.1.42"))
        assertEquals("192.168.1.*", PeerMask.mask("2001:db8::192.168.1.42"))
    }

    @Test
    fun bareIpv6KeepsOnlyTheFirstFourGroups() {
        assertEquals("fe80:1:2:3:*", PeerMask.mask("fe80:1:2:3:4:5"))
        assertEquals("fe80:1:*", PeerMask.mask("fe80::1"))
    }

    @Test
    fun bracketedIpv6AndZoneAndPortAreStrippedBeforeMasking() {
        assertEquals("fe80:1:*", PeerMask.mask("[fe80::1]:1234"))
        assertEquals("fe80:1:*", PeerMask.mask("[fe80::1%eth0]:1234"))
    }

    @Test
    fun emptyAndInvalidPeersReturnEmptyMask() {
        assertEquals("", PeerMask.mask(""))
        assertEquals("", PeerMask.mask("   "))
        assertEquals("", PeerMask.mask("not-an-ip"))
        assertEquals("", PeerMask.mask("300.1.2.3"))
        assertEquals("", PeerMask.mask("192.168.1"))
    }
}

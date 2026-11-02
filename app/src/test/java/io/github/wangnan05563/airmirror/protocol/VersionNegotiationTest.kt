package io.github.wangnan05563.airmirror.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionNegotiationTest {

    @Test
    fun picksHighestCommonVersion() {
        assertEquals(3, Version.negotiate(clientMin = 1, clientMax = 3, serverMin = 2, serverMax = 5))
    }

    @Test
    fun fallsBackWhenRangesOnlyTouchAtMin() {
        assertEquals(2, Version.negotiate(clientMin = 2, clientMax = 2, serverMin = 1, serverMax = 2))
    }

    @Test
    fun returnsNullWhenNoOverlap() {
        assertNull(Version.negotiate(clientMin = 4, clientMax = 5, serverMin = 1, serverMax = 3))
    }

    @Test
    fun rejectsInvertedRanges() {
        assertNull(Version.negotiate(clientMin = 5, clientMax = 4, serverMin = 1, serverMax = 3))
    }

    @Test
    fun acceptsCurrentProtocolVersion() {
        assertTrue(Version.CURRENT in Version.MIN_SUPPORTED..Version.CURRENT)
    }
}

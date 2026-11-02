package io.github.wangnan05563.airmirror.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

class ProtocolTest {

    @Test
    fun roundTripsFramesOfEveryType() {
        for (type in Protocol.Type.entries) {
            val frame = Protocol.Frame(type, flags = Protocol.Flags.KEY_FRAME, ptsUs = 123456789L, payload = byteArrayOf(1, 2, 3))
            val out = ByteArrayOutputStream()
            Protocol.write(out, frame)
            val read = Protocol.read(ByteArrayInputStream(out.toByteArray()))
            assertEquals(frame, read)
        }
    }

    @Test
    fun roundTripsEmptyPayload() {
        val frame = Protocol.Frame(Protocol.Type.PING)
        val out = ByteArrayOutputStream()
        Protocol.write(out, frame)
        val read = Protocol.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(frame, read)
    }

    @Test
    fun readReturnsNullOnCleanEof() {
        assertNull(Protocol.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun readFailsOnTruncatedHeader() {
        val out = ByteArrayOutputStream()
        Protocol.write(out, Protocol.Frame(Protocol.Type.VIDEO, payload = ByteArray(10)))
        val truncated = out.toByteArray().copyOf(Protocol.HEADER_SIZE - 3)
        assertThrows(EOFException::class.java) {
            Protocol.read(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun readFailsOnBadMagic() {
        val bytes = ByteArray(Protocol.HEADER_SIZE + 1)
        bytes[0] = 0x7F
        assertThrows(Protocol.ProtocolException::class.java) {
            Protocol.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun readFailsOnUnknownType() {
        val bytes = ByteArrayOutputStream().also {
            Protocol.write(it, Protocol.Frame(Protocol.Type.PING))
        }.toByteArray()
        bytes[5] = 0x7E // type 字节
        assertThrows(Protocol.ProtocolException::class.java) {
            Protocol.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun readFailsOnOversizedPayload() {
        val bytes = ByteArrayOutputStream().also {
            Protocol.write(it, Protocol.Frame(Protocol.Type.VIDEO))
        }.toByteArray()
        val huge = Protocol.MAX_PAYLOAD + 1
        bytes[16] = (huge ushr 24).toByte()
        bytes[17] = (huge ushr 16).toByte()
        bytes[18] = (huge ushr 8).toByte()
        bytes[19] = huge.toByte()
        assertThrows(Protocol.ProtocolException::class.java) {
            Protocol.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun writeRejectsOversizedPayload() {
        val tooBig = Protocol.Frame(Protocol.Type.VIDEO, payload = ByteArray(Protocol.MAX_PAYLOAD + 1))
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.write(ByteArrayOutputStream(), tooBig)
        }
    }

    @Test
    fun rejectsFutureVersionOnRead() {
        val bytes = ByteArrayOutputStream().also {
            Protocol.write(it, Protocol.Frame(Protocol.Type.VIDEO))
        }.toByteArray()
        bytes[4] = (Version.CURRENT + 1).toByte()
        assertThrows(Protocol.ProtocolException::class.java) {
            Protocol.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun headerIsTwentyBytes() {
        val out = ByteArrayOutputStream()
        Protocol.write(out, Protocol.Frame(Protocol.Type.BYE))
        assertEquals(Protocol.HEADER_SIZE, out.size())
    }

    @Test
    fun handshakeRoundTripsAndNegotiates() {
        val hello = Handshake.Hello(Version.MIN_SUPPORTED, Version.CURRENT, Handshake.Capability.VIDEO_H264, "客厅盒子")
        val decoded = Handshake.Hello.decode(hello.encode())
        assertEquals(hello, decoded)

        val ack = Handshake.accept(decoded, deviceName = "AirMirror-TV")
        assertEquals(Version.CURRENT, ack.chosenVersion)
        assertEquals(Handshake.Capability.VIDEO_H264, ack.capabilities)
        assertEquals(ack, Handshake.HelloAck.decode(ack.encode()))
    }

    @Test
    fun handshakeRejectsIncompatibleClient() {
        val hello = Handshake.Hello(Version.CURRENT + 1, Version.CURRENT + 2, Handshake.Capability.ALL, "future")
        assertThrows(IOException::class.java) {
            Handshake.accept(hello, deviceName = "server")
        }
    }

    @Test
    fun capabilitiesAreIntersected() {
        val hello = Handshake.Hello(Version.MIN_SUPPORTED, Version.CURRENT, Handshake.Capability.AUDIO_AAC, "phone")
        val ack = Handshake.accept(hello, deviceName = "tv")
        assertEquals(Handshake.Capability.AUDIO_AAC, ack.capabilities)
        assertTrue(ack.capabilities and Handshake.Capability.VIDEO_H264 == 0)
    }
}

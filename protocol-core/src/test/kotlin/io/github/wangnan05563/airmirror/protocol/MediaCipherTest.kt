package io.github.wangnan05563.airmirror.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaCipherTest {

    private val key = KeyExchange.hkdf("session-key-material".toByteArray(), "salt".toByteArray(), "c2s".toByteArray(), 32)
    private val otherKey = KeyExchange.hkdf("session-key-material".toByteArray(), "salt".toByteArray(), "s2c".toByteArray(), 32)

    private fun videoFrame(payload: ByteArray, pts: Long = 96_000L, flags: Int = Protocol.Flags.KEY_FRAME) =
        Protocol.Frame(Protocol.Type.VIDEO, flags = flags, ptsUs = pts, payload = payload)

    /** 收端视角：帧头已被打上加密位，载荷是线上那串字节 */
    private fun wireFrame(frame: Protocol.Frame, sealed: ByteArray) =
        Protocol.Frame(frame.type, frame.version, frame.flags or Protocol.Flags.ENCRYPTED, frame.ptsUs, sealed)

    @Test
    fun sealsAndOpensAFrame() {
        val cipher = MediaCipher(key)
        val frame = videoFrame(byteArrayOf(0, 0, 0, 1, 67, 10, 20, 30))
        val opened = cipher.open(wireFrame(frame, cipher.seal(frame)))
        assertEquals(frame.payload.toList(), opened.toList())
    }

    @Test
    fun sealedPayloadSharesNothingWithThePlainOne() {
        val cipher = MediaCipher(key)
        val plain = ByteArray(64) { (it % 7).toByte() }
        val sealed = cipher.seal(videoFrame(plain))
        assertEquals(plain.size + MediaCipher.OVERHEAD, sealed.size)
        assertFalse("密文里不能原样留着明文片段", sealed.toList().windowed(plain.size).contains(plain.toList()))
    }

    @Test
    fun samePlainFrameSealsTwiceToDifferentBytes() {
        val cipher = MediaCipher(key)
        val plain = videoFrame(ByteArray(32) { 1 })
        assertNotEquals(cipher.seal(plain).toList(), cipher.seal(plain).toList())
    }

    @Test
    fun wrongKeyCannotOpen() {
        val frame = videoFrame(ByteArray(16) { 2 })
        val sealed = MediaCipher(key).seal(frame)
        assertThrows(Protocol.ProtocolException::class.java) { MediaCipher(otherKey).open(wireFrame(frame, sealed)) }
    }

    @Test
    fun tamperedPayloadIsRejected() {
        val cipher = MediaCipher(key)
        val frame = videoFrame(ByteArray(16) { 3 })
        val sealed = cipher.seal(frame)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertThrows(Protocol.ProtocolException::class.java) { cipher.open(wireFrame(frame, sealed)) }
    }

    @Test
    fun rewritingTheHeaderIsRejected() {
        // 帧头明文送，中间人若把 pts 或「这帧是关键帧」改掉，GCM 的附加认证数据就对不上
        val cipher = MediaCipher(key)
        val frame = videoFrame(ByteArray(16) { 4 }, pts = 96_000L)
        val sealed = cipher.seal(frame)

        val restamped = Protocol.Frame(Protocol.Type.VIDEO, flags = frame.flags or Protocol.Flags.ENCRYPTED, ptsUs = 97_000L, payload = sealed)
        assertThrows(Protocol.ProtocolException::class.java) { cipher.open(restamped) }

        val relabelled = Protocol.Frame(Protocol.Type.AUDIO, flags = Protocol.Flags.ENCRYPTED, ptsUs = frame.ptsUs, payload = sealed)
        assertThrows(Protocol.ProtocolException::class.java) { cipher.open(relabelled) }
    }

    @Test
    fun truncatedPayloadIsRejectedWithoutCrashing() {
        val frame = Protocol.Frame(Protocol.Type.VIDEO, flags = Protocol.Flags.ENCRYPTED, ptsUs = 1L, payload = ByteArray(MediaCipher.OVERHEAD - 1))
        assertThrows(Protocol.ProtocolException::class.java) { MediaCipher(key).open(frame) }
    }

    @Test
    fun configFrameFlagsSurviveTheRoundTrip() {
        val cipher = MediaCipher(key)
        val config = videoFrame(ByteArray(80) { it.toByte() }, pts = 0L, flags = Protocol.Flags.CONFIG or Protocol.Flags.CODEC_HEVC)
        val opened = cipher.open(wireFrame(config, cipher.seal(config)))
        assertEquals(config.payload.toList(), opened.toList())
    }
}

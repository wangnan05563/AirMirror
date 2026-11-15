package io.github.wangnan05563.airmirror.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 跨语言线协议测试向量（T05760 M0）。
 *
 * Android / PC / HarmonyOS 三端实现必须对同一组输入产出逐字节一致的结果；
 * 向量文件放在 `protocol-core/src/test/resources/vectors/`，PC/鸿蒙实现可直接复制使用。
 */
class ProtocolVectorsTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(it) }

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/vectors/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("missing vector resource: $name")

    @Test
    fun videoFrameMatchesWireVector() {
        val frame = Protocol.Frame(
            type = Protocol.Type.VIDEO,
            version = Version.CURRENT,
            flags = Protocol.Flags.KEY_FRAME or Protocol.Flags.ENCRYPTED,
            ptsUs = 123_456_789L,
            payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
        )
        val out = ByteArrayOutputStream()
        Protocol.write(out, frame)
        val expected = "414d52430103110000000000075bcd1500000004deadbeef"
        assertEquals(expected, hex(out.toByteArray()))
        assertTrue(resource("frame-video.json").contains(expected))
    }

    @Test
    fun helloMatchesWireVector() {
        val hello = Handshake.Hello(
            minVersion = 1,
            maxVersion = 1,
            capabilities = Handshake.Capability.VIDEO_H264 or Handshake.Capability.AUDIO_AAC,
            deviceName = "PC",
        )
        val expected = "000100010000000300025043"
        assertEquals(expected, hex(hello.encode()))
        assertTrue(resource("hello.json").contains(expected))
    }

    @Test
    fun hkdfMatchesRfc5869CaseOne() {
        // RFC 5869 Appendix A.1：HKDF-SHA256 标准测试向量
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c,
        )
        val info = byteArrayOf(
            0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(),
            0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(),
            0xf8.toByte(), 0xf9.toByte(),
        )
        val okm = KeyExchange.hkdf(ikm, salt, info, 42)
        val expected =
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        assertEquals(expected, hex(okm))
        assertTrue(resource("hkdf-rfc5869-case1.json").contains(expected))
    }

    @Test
    fun aesGcmFrameVectorDecrypts() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(12) { it.toByte() }
        val cipherText =
            "066ba456ac97b074ff61e1ba919f1d0ef7b9f5a181e786019ebe5ddff563a0ae5d37f6"
        val plain = "AirMirror v1 vector".toByteArray(Charsets.UTF_8)
        val frame = Protocol.Frame(
            type = Protocol.Type.VIDEO,
            version = 1,
            flags = Protocol.Flags.KEY_FRAME,
            ptsUs = 123_456_789L,
            payload = iv + hexToBytes(cipherText),
        )
        assertEquals(plain.toList(), MediaCipher(key).open(frame).toList())
        val vector = resource("aes-gcm-frame.json")
        assertTrue(vector.contains(cipherText))
        assertTrue(vector.contains("01030100000000075bcd15"))
    }

    @Test
    fun codeAnchorMatchesVector() {
        val credential = "123456"
        val clientKey = ByteArray(32) { (it + 1).toByte() }
        val serverKey = ByteArray(32) { (it + 33).toByte() }
        assertEquals("ad5fe2219a10023eaa72ff1ed3cc3fd36d6c84a9ecb1f1e8bbf6b477a33a7795", hex(CodeAnchor.anchorTag(credential)))
        assertEquals(
            "60298e4ec2f256895614c00853aaab9994b51a0fcdeaeff17fc193faf6e036db",
            hex(CodeAnchor.clientProof(credential, clientKey)),
        )
        assertEquals(
            "b7edba41871742c1def1468ae3ec1d53d2f222132459c94dd16b55bd5147b4fb",
            hex(CodeAnchor.serverProof(credential, clientKey, serverKey)),
        )
        val vector = resource("code-anchor.json")
        assertTrue(vector.contains("ad5fe2219a10023eaa72ff1ed3cc3fd36d6c84a9ecb1f1e8bbf6b477a33a7795"))
    }

    private fun hexToBytes(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun vectorFilesAreResolvableFromResources() {
        for (name in listOf(
            "frame-video.json",
            "hello.json",
            "hkdf-rfc5869-case1.json",
            "aes-gcm-frame.json",
            "code-anchor.json",
        )) {
            assertTrue("missing $name", javaClass.getResourceAsStream("/vectors/$name") != null)
        }
    }
}

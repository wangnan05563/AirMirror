package io.github.wangnan05563.airmirror.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

class KeyExchangeTest {

    @Test
    fun generatesP256PublicKeyWithinHandshakeBudget() {
        val ephemeral = KeyExchange.generate()
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ephemeral.publicSpki)) as ECPublicKey
        assertEquals(256, key.params.curve.field.fieldSize)
        assertTrue("公钥要塞得进握手帧：${ephemeral.publicSpki.size}B", ephemeral.publicSpki.size <= KeyExchange.MAX_PUBLIC_KEY)
    }

    @Test
    fun bothEndsDeriveTheSamePairOfKeys() {
        val client = KeyExchange.generate()
        val server = KeyExchange.generate()
        val fromClient = KeyExchange.sessionKeys(
            KeyExchange.sharedSecret(client.privateKey, KeyExchange.decodePublic(server.publicSpki)),
            client.publicSpki,
            server.publicSpki,
            Version.CURRENT,
        )
        val fromServer = KeyExchange.sessionKeys(
            KeyExchange.sharedSecret(server.privateKey, KeyExchange.decodePublic(client.publicSpki)),
            client.publicSpki,
            server.publicSpki,
            Version.CURRENT,
        )
        assertEquals(fromClient, fromServer)
        assertEquals(KeyExchange.KEY_BYTES, fromClient.clientToServer.size)
        assertEquals(KeyExchange.KEY_BYTES, fromClient.serverToClient.size)
    }

    @Test
    fun theTwoDirectionsDoNotShareOneKey() {
        val client = KeyExchange.generate()
        val server = KeyExchange.generate()
        val keys = KeyExchange.sessionKeys(
            KeyExchange.sharedSecret(client.privateKey, KeyExchange.decodePublic(server.publicSpki)),
            client.publicSpki,
            server.publicSpki,
            Version.CURRENT,
        )
        assertNotEquals(keys.clientToServer.toList(), keys.serverToClient.toList())
    }

    @Test
    fun rolesAreBoundIntoTheKeys() {
        // 把 client/server 公钥调换位置必须得到另一套密钥，否则「谁是谁」在密钥里没有区别
        val client = KeyExchange.generate()
        val server = KeyExchange.generate()
        val secret = KeyExchange.sharedSecret(client.privateKey, KeyExchange.decodePublic(server.publicSpki))
        val right = KeyExchange.sessionKeys(secret, client.publicSpki, server.publicSpki, Version.CURRENT)
        val swapped = KeyExchange.sessionKeys(secret, server.publicSpki, client.publicSpki, Version.CURRENT)
        assertNotEquals(right.clientToServer.toList(), swapped.serverToClient.toList())
    }

    @Test
    fun eachConnectionGetsItsOwnKeys() {
        val a = KeyExchange.generate()
        val b = KeyExchange.generate()
        val first = KeyExchange.sessionKeys(
            KeyExchange.sharedSecret(a.privateKey, KeyExchange.decodePublic(b.publicSpki)),
            a.publicSpki,
            b.publicSpki,
            Version.CURRENT,
        )
        val second = KeyExchange.sessionKeys(
            KeyExchange.sharedSecret(KeyExchange.generate().privateKey, KeyExchange.decodePublic(b.publicSpki)),
            a.publicSpki,
            b.publicSpki,
            Version.CURRENT,
        )
        assertNotEquals(first.clientToServer.toList(), second.clientToServer.toList())
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        // RFC 5869 附录 A Test Case 1（同一个向量也用 Node 的 crypto.hkdfSync 复核过一次）
        val ikm = ByteArray(22) { 0x0B }
        val salt = (0..12).map { it.toByte() }.toByteArray()
        val info = (0xF0..0xF9).map { it.toByte() }.toByteArray()
        val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
        assertEquals(expected, KeyExchange.hkdf(ikm, salt, info, 32).toHex())
    }

    @Test
    fun hkdfExpandsAcrossBlocksInOrder() {
        val ikm = ByteArray(22) { 0x0B }
        val salt = (0..12).map { it.toByte() }.toByteArray()
        val info = (0xF0..0xF9).map { it.toByte() }.toByteArray()
        val short = KeyExchange.hkdf(ikm, salt, info, 32)
        val long = KeyExchange.hkdf(ikm, salt, info, 64)
        // 多要字节只能是往后追加，不能把前一段重排——否则方向密钥会跟着握手长度漂移
        assertEquals(short.toList(), long.take(32))
        assertEquals(64, long.size)
        assertNotEquals(short.toList(), long.drop(32))
    }

    @Test
    fun handshakeWithoutAKeyShareDecodesAsLegacy() {
        // 老版本发的就是这段字节：名字之后什么都没有，解码必须认为「这一端不懂加密」
        val legacy = Handshake.Hello(Version.MIN_SUPPORTED, Version.CURRENT, Handshake.Capability.VIDEO_H264, "老盒子")
        val decoded = Handshake.Hello.decode(legacy.encode())
        assertNull(decoded.keyShare)
        assertEquals(legacy, decoded)
    }

    @Test
    fun handshakeKeyShareSurvivesRoundTripAndOldTailIsIgnored() {
        val hello = Handshake.Hello(1, 1, Handshake.Capability.ALL, "手机", KeyExchange.generate().publicSpki)
        val bytes = hello.encode()
        assertEquals(hello, Handshake.Hello.decode(bytes))
        // 老接收端读到设备名就返回，尾巴上的公钥被整段忽略 —— 截掉尾巴后名字与能力位必须一模一样
        val trimmed = Handshake.Hello.decode(bytes.copyOfRange(0, bytes.size - hello.keyShare!!.size))
        assertEquals(hello.deviceName, trimmed.deviceName)
        assertEquals(hello.capabilities, trimmed.capabilities)
    }

    @Test
    fun bogusKeyShareLengthIsTreatedAsNoKey() {
        val hello = Handshake.Hello(1, 1, Handshake.Capability.VIDEO_H264, "phone", KeyExchange.generate().publicSpki)
        val bytes = hello.encode()
        val lyingLength = bytes.copyOf()
        lyingLength[bytes.size - hello.keyShare!!.size - 2] = 0xFF.toByte()
        assertNull(Handshake.Hello.decode(lyingLength).keyShare)
    }

    @Test
    fun ackWithoutKeyShareDecodesAsLegacy() {
        val ack = Handshake.HelloAck(Version.CURRENT, Handshake.Capability.ALL, "电视")
        assertEquals(ack, Handshake.HelloAck.decode(ack.encode()))
        assertNull(Handshake.HelloAck.decode(ack.encode()).keyShare)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

package io.github.wangnan05563.airmirror.protocol

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端媒体加密的密钥协商（PRD 8.9「密钥在配对阶段协商，仅两端持有」、482 行 P0 承诺）。
 *
 * 每条 TCP 连接临时生成一对 P-256 密钥，握手时只交换公钥（SPKI DER），私钥只在内存里、
 * 不落盘也不进日志。因此中继或同网抓包拿到的媒体帧没有对应私钥，解不出内容。
 * 两个方向各用一把派生密钥，避免同一把密钥双向使用带来的分组模式风险。
 */
object KeyExchange {
    const val CURVE = "secp256r1"

    /** P-256 的 SPKI DER 约 91 字节；留出余量只为挡住对端送来的离谱长度，不是协议上限 */
    const val MAX_PUBLIC_KEY = 192

    /** 派生出的每把方向密钥长度（AES-256） */
    const val KEY_BYTES = 32

    class Ephemeral(val privateKey: PrivateKey, val publicSpki: ByteArray)

    data class SessionKeys(val clientToServer: ByteArray, val serverToClient: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SessionKeys) return false
            return clientToServer.contentEquals(other.clientToServer) && serverToClient.contentEquals(other.serverToClient)
        }

        override fun hashCode(): Int = 31 * clientToServer.contentHashCode() + serverToClient.contentHashCode()
    }

    fun generate(): Ephemeral {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE))
        val pair = generator.generateKeyPair()
        return Ephemeral(pair.private, pair.public.encoded)
    }

    fun decodePublic(spki: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    /** ECDH 共享秘密：只用对方公钥的 X 坐标，不引入任何 KDF 之外的材料。 */
    fun sharedSecret(privateKey: PrivateKey, peerPublic: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublic, true)
        return agreement.generateSecret()
    }

    /**
     * 由共享秘密 + 双方握手公钥派生两把方向密钥。
     * 把双方公钥写进 HKDF 的 salt，等于把整段握手记进密钥里：换了任何一侧公钥的中间人
     * 会得到完全不同的密钥，两端随之解不开对方的帧，而不是安静地被人换掉。
     *
     * [anchor] 为 [CodeAnchor.anchorTag]：投屏码也参与 salt（T04493）。带码的连接与不带码的连接
     * 即使公钥巧合相同也派生不出同一套密钥；null 表示这条会话没有锚（匿名/续会/老对端），保持旧口径。
     */
    fun sessionKeys(
        secret: ByteArray,
        clientPublicSpki: ByteArray,
        serverPublicSpki: ByteArray,
        protocolVersion: Int,
        anchor: ByteArray? = null,
    ): SessionKeys {
        val transcript = sha256(clientPublicSpki + serverPublicSpki + (anchor ?: EMPTY))
        val prefix = "AMRC-E2E/v$protocolVersion ".toByteArray(Charsets.UTF_8)
        return SessionKeys(
            clientToServer = hkdf(secret, salt = transcript, info = prefix + "c2s".toByteArray(Charsets.UTF_8), length = KEY_BYTES),
            serverToClient = hkdf(secret, salt = transcript, info = prefix + "s2c".toByteArray(Charsets.UTF_8), length = KEY_BYTES),
        )
    }

    private val EMPTY = ByteArray(0)

    /** HKDF-SHA256（RFC 5869）：只做提取 + 扩展两步，不另加前缀，好让标准测试向量直接对上。 */
    fun hkdf(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_BYTES) { "hkdf output length out of range: $length" }
        val prk = hmac(salt, inputKeyMaterial)
        val output = ByteArray(length)
        var written = 0
        var previous = ByteArray(0)
        var round = 1
        while (written < length) {
            val block = hmac(prk, previous + info + byteArrayOf(round.toByte()))
            val take = minOf(block.size, length - written)
            System.arraycopy(block, 0, output, written, take)
            written += take
            previous = block
            round++
        }
        return output
    }

    private const val HASH_BYTES = 32

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)
}

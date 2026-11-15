package io.github.wangnan05563.airmirror.protocol

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 媒体帧载荷的 AES-256-GCM 加解密（PRD 8.9「局域网媒体流端到端加密」）。
 *
 * 线上格式：`iv(12) || 密文 || 认证标签(16)`，帧头照旧明文。帧头里的类型、pts 与
 * 关键帧/配置位作为 GCM 的附加认证数据一起进算法，所以中间人改不动「这一帧是什么、
 * 什么时间」——改一个比特就解不开。类型与长度本身仍然可见（PRD 只要求内容不可还原，
 * 要连帧形状都藏住得等 M3 中继那轮一起定口径）。
 *
 * 解不开就当场抛 [ProtocolException]：密钥错或帧被动过手脚时，两端不可能对齐，
 * 让会话断掉走 FS-10 恢复重握手，比继续喂垃圾给解码器诚实。
 */
class MediaCipher(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    /** 密文比明文多出来的字节数，发送端拿它给帧长上限留出余量 */
    val overhead: Int get() = OVERHEAD

    fun seal(frame: Protocol.Frame): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData(frame))
        val body = cipher.doFinal(frame.payload)
        return iv + body
    }

    fun open(frame: Protocol.Frame): ByteArray {
        if (frame.payload.size < OVERHEAD) throw Protocol.ProtocolException("encrypted payload too short: ${frame.payload.size}")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keySpec,
            GCMParameterSpec(TAG_BITS, frame.payload, 0, IV_BYTES),
        )
        cipher.updateAAD(associatedData(frame, encryptedHeaderFlags(frame.flags)))
        return try {
            cipher.doFinal(frame.payload, IV_BYTES, frame.payload.size - IV_BYTES)
        } catch (e: Exception) {
            throw Protocol.ProtocolException("media frame did not decrypt: ${e.javaClass.simpleName}")
        }
    }

    /** 加密位自身不参与认证：它只是「这一帧是密文」的路标，两端本来就该一致。 */
    private fun encryptedHeaderFlags(flags: Int) = flags and Protocol.Flags.ENCRYPTED.inv()

    private fun associatedData(frame: Protocol.Frame, flags: Int = encryptedHeaderFlags(frame.flags)): ByteArray {
        val aad = ByteArray(11)
        aad[0] = frame.version.toByte()
        aad[1] = frame.type.code.toByte()
        aad[2] = flags.toByte()
        var pts = frame.ptsUs
        for (i in 10 downTo 3) {
            aad[i] = (pts and 0xFF).toByte()
            pts = pts shr 8
        }
        return aad
    }

    companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val OVERHEAD = IV_BYTES + TAG_BITS / 8
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

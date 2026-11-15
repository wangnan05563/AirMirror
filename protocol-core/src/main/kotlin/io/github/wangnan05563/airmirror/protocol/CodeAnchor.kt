package io.github.wangnan05563.airmirror.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 握手身份锚（T04493）：把投屏码绑进握手校验与密钥派生，堵住「同时站在两端中间、各接一头、替换公钥」的主动中间人。
 *
 * 临时 ECDH 只保证「两把公钥各自成立」，不保证「这两把公钥属于眼前这两台设备」。能同时接管两条连接的人
 * 可以各接一头：把客户端的公钥换成自己的发给服务端，再把服务端的公钥换成自己的发给客户端，两端都以为在直连。
 * 投屏码（或二维码令牌）是唯一只在两端知道的短共享秘密，用它对双方公钥做 HMAC：换掉任何一侧公钥都验不过；
 * 再把同一个锚混进 HKDF 的 salt，**密钥本身也跟着码走**，验签被绕开也还有第二道锁。
 *
 * 边界：空凭据不产生锚。可信设备的匿名连接与断线续会本来就没有「只在两端知道的码」——码已经用掉了，
 * 这类会话由大屏显式确认兜底（T04496），这里如实返回 null，不造一个假的锚。
 */
object CodeAnchor {

    /**
     * 拒绝文案。含「不正确」是刻意的：发送端按 `ErrorNotice.causeOfLegacyReason` 认成 CODE_WRONG，
     * 用户拿到的仍是「投屏码不正确 + 重新读码」这条可执行提示，而不是一句「连不上」。
     */
    const val REFUSED = "投屏码不正确：握手校验与大屏当前码对不上，请重新读码；若反复如此，检查连线是否被替换"

    /** 公钥之后允许携带的校验值上限：HMAC-SHA256 会产出 32 字节，留余量只为挡住离谱长度 */
    const val MAX_PROOF = 64

    fun usable(credential: String): Boolean = credential.isNotBlank()

    /** 客户端签名：把「我是拿这个码来的」和「这把公钥是我的」一次绑在一起 */
    fun clientProof(credential: String, clientKeyShare: ByteArray): ByteArray =
        hmac(anchorKey(credential), CLIENT + clientKeyShare)

    /** 服务端签名：连客户端公钥一起签，中间人换掉任意一侧都过不了 */
    fun serverProof(credential: String, clientKeyShare: ByteArray, serverKeyShare: ByteArray): ByteArray =
        hmac(anchorKey(credential), SERVER + clientKeyShare + serverKeyShare)

    /** 参与 HKDF 的锚标签：同一凭据两端算出同一个 32 字节，别的码算出来的密钥完全不同 */
    fun anchorTag(credential: String): ByteArray = anchorKey(credential)

    /** 定长比较，避免按字节提前返回泄漏匹配前缀（这里不算高价值侧信道，但没必要留） */
    fun matches(expected: ByteArray, actual: ByteArray?): Boolean =
        actual != null && MessageDigest.isEqual(expected, actual)

    private fun anchorKey(credential: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((PREFIX + credential).toByteArray(Charsets.UTF_8))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private const val PREFIX = "AMRC-ANCHOR/v1|"
    private val CLIENT = "client|".toByteArray(Charsets.UTF_8)
    private val SERVER = "server|".toByteArray(Charsets.UTF_8)
}

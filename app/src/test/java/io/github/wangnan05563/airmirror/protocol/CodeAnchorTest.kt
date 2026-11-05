package io.github.wangnan05563.airmirror.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 握手身份锚（T04493）：投屏码是唯一「只在两端知道」的短秘密，用它签字才能在主动中间人面前立住身份。
 *
 * 这里锁的是判据本身：签名必须同时覆盖凭据与公钥，任何一侧的公钥被替换都必须验不过——
 * 这正是任务里要的「替换公钥」变异用例（纯逻辑层；真代理上打一次属于 M3 中继轮的腿 C）。
 */
class CodeAnchorTest {

    private val clientKey = byteArrayOf(1, 2, 3, 4)
    private val serverKey = byteArrayOf(5, 6, 7, 8)

    @Test
    fun aProofIsBoundToBothTheCodeAndTheKey() {
        val proof = CodeAnchor.clientProof("123456", clientKey)
        assertTrue(CodeAnchor.matches(CodeAnchor.clientProof("123456", clientKey), proof))
        // 换个码：同一个公钥也算不出同一份签名（否则拿到一次旧签名就能长期复用）
        assertFalse(CodeAnchor.matches(CodeAnchor.clientProof("123457", clientKey), proof))
        // 换个公钥：签名当场对不上，这就是「替换公钥」被挡住的判据
        assertFalse(CodeAnchor.matches(CodeAnchor.clientProof("123456", clientKey + 9), proof))
    }

    @Test
    fun aReplacedClientKeyCannotPassTheServerCheck() {
        // 中间人把客户端的公钥换成自己的，却只能转抄客户端原来那份签名
        val honestProof = CodeAnchor.clientProof("123456", clientKey)
        val mitmKey = byteArrayOf(10, 11, 12, 13, 14)
        val serverSees = CodeAnchor.clientProof("123456", mitmKey)
        assertFalse("换掉客户端公钥后签名必须验不过", CodeAnchor.matches(serverSees, honestProof))
    }

    @Test
    fun aReplacedServerKeyCannotPassTheClientCheck() {
        val honestServer = CodeAnchor.serverProof("123456", clientKey, serverKey)
        val mitmServerKey = byteArrayOf(20, 21, 22, 23, 24)
        val replaced = CodeAnchor.serverProof("123456", clientKey, mitmServerKey)
        assertFalse("换掉服务端公钥后客户端必须验不过", CodeAnchor.matches(honestServer, replaced))
    }

    @Test
    fun theServerProofCoversBothKeys() {
        // 只签服务端自己的公钥，中间人可以拿客户端公钥做文章；两侧都要进签名
        val both = CodeAnchor.serverProof("123456", clientKey, serverKey)
        val otherClient = CodeAnchor.serverProof("123456", byteArrayOf(9, 9), serverKey)
        assertNotEquals(both.toList(), otherClient.toList())
    }

    @Test
    fun aMissingProofNeverMatches() {
        val expected = CodeAnchor.clientProof("123456", clientKey)
        assertFalse("老端不带签名，不能当成验过了", CodeAnchor.matches(expected, null))
    }

    @Test
    fun blankCredentialsHaveNoAnchor() {
        // 可信设备的匿名连接与断线续会本来就没有码：这里如实报「不可用」，由大屏显式确认兜底
        assertFalse(CodeAnchor.usable(""))
        assertFalse(CodeAnchor.usable("   "))
        assertTrue(CodeAnchor.usable("123456"))
        assertTrue(CodeAnchor.usable("0123456789abcdef"))
    }

    @Test
    fun anchorTagsDifferPerCode() {
        assertNotEquals(CodeAnchor.anchorTag("123456").toList(), CodeAnchor.anchorTag("654321").toList())
    }
}

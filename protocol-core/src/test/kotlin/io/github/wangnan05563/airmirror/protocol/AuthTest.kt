package io.github.wangnan05563.airmirror.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTH / AUTH_RESULT 载荷编解码：值里的 `=` 与 `&` 必须转义，
 * 否则发送端设备名就能伪造出额外字段或顶掉凭据。
 */
class AuthTest {

    @Test
    fun requestAndResultRoundTrip() {
        val request = Auth.parseRequest(Auth.request("am-1", "123456"))
        assertEquals("am-1", request.deviceId)
        assertEquals("123456", request.credential)

        val accepted = Auth.parseResult(Auth.result(true))
        assertTrue(accepted.accepted)
        assertEquals("", accepted.reason)

        val refused = Auth.parseResult(Auth.result(false, "投屏码不正确"))
        assertFalse(refused.accepted)
        assertEquals("投屏码不正确", refused.reason)
    }

    @Test
    fun separatorsInTheDeviceNameCannotSpoofExtraFields() {
        val request = Auth.parseRequest(Auth.request("id=evil&cred=1", ""))
        assertEquals("id=evil&cred=1", request.deviceId)
        assertEquals("", request.credential)
    }

    @Test
    fun anAbsentCredentialMeansTheTrustedListPath() {
        assertEquals("", Auth.parseRequest(Auth.request("am-1", "")).credential)
    }

    @Test
    fun resumeRequestsAreMarkedAndKeepTheDeviceId() {
        val request = Auth.parseRequest(Auth.request("am-1", "", resume = true))
        assertEquals("am-1", request.deviceId)
        assertEquals("", request.credential)
        assertTrue(request.resume)
    }

    @Test
    fun aNormalRequestIsNotAResume() {
        assertFalse(Auth.parseRequest(Auth.request("am-1", "123456")).resume)
    }

    @Test
    fun aPayloadWithoutTheExpectedKeysDecodesToBlanks() {
        val request = Auth.parseRequest("garbage".toByteArray(Charsets.UTF_8))
        assertEquals("", request.deviceId)
        assertEquals("", request.credential)
        assertFalse(Auth.parseResult("garbage".toByteArray(Charsets.UTF_8)).accepted)
    }
}

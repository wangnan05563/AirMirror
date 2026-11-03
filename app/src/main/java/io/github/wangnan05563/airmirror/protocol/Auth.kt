package io.github.wangnan05563.airmirror.protocol

import io.github.wangnan05563.airmirror.pairing.PairingLink

/**
 * AUTH / AUTH_RESULT 帧的载荷编解码：`k=v&k=v`，值按 [PairingLink] 的转义口径。
 */
object Auth {
    private const val KEY_ID = "id"
    private const val KEY_CRED = "cred"
    private const val KEY_OK = "ok"
    private const val KEY_REASON = "reason"

    class Request(val deviceId: String, val credential: String)

    class Result(val accepted: Boolean, val reason: String)

    fun request(deviceId: String, credential: String): ByteArray =
        "$KEY_ID=${PairingLink.escape(deviceId)}&$KEY_CRED=${PairingLink.escape(credential)}"
            .toByteArray(Charsets.UTF_8)

    fun result(accepted: Boolean, reason: String = ""): ByteArray =
        "$KEY_OK=${if (accepted) 1 else 0}&$KEY_REASON=${PairingLink.escape(reason)}".toByteArray(Charsets.UTF_8)

    fun parseRequest(payload: ByteArray): Request = Request(field(payload, KEY_ID), field(payload, KEY_CRED))

    fun parseResult(payload: ByteArray): Result =
        Result(field(payload, KEY_OK) == "1", field(payload, KEY_REASON))

    private fun field(payload: ByteArray, key: String): String {
        val text = String(payload, Charsets.UTF_8)
        for (part in text.split('&')) {
            if (part.substringBefore('=') == key) return PairingLink.unescape(part.substringAfter('=', ""))
        }
        return ""
    }
}

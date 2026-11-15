package io.github.wangnan05563.airmirror.pairing

/**
 * 待连接页二维码的载荷编解码（PRD FR-03：二维码含设备标识与一次性令牌，随投屏码同步轮换）。
 *
 * 纯字符串处理，不依赖 android.net.Uri，因此扫码解析在 JVM 单测里就能全覆盖。
 */
object PairingLink {
    const val SCHEME = "airmirror"

    data class Link(
        val deviceId: String,
        val name: String,
        val token: String,
        val host: String,
        val port: Int,
    )

    fun build(link: Link): String = buildString {
        append(SCHEME).append("://pair")
        append("?id=").append(escape(link.deviceId))
        append("&t=").append(escape(link.token))
        append("&n=").append(escape(link.name))
        append("&h=").append(escape(link.host))
        append("&p=").append(link.port)
    }

    /** 解析不出设备标识或令牌时返回 null，由界面按「不是投屏二维码」处理而不是猜测。 */
    fun parse(url: String): Link? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("$SCHEME://")) return null
        val query = trimmed.substringAfter("?", "")
        if (query.isEmpty()) return null
        val params = HashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val key = part.substringBefore('=').takeIf { it.isNotEmpty() } ?: continue
            params[key] = unescape(part.substringAfter('=', ""))
        }
        val deviceId = params["id"].orEmpty()
        val token = params["t"].orEmpty()
        val port = params["p"]?.toIntOrNull() ?: return null
        if (deviceId.isEmpty() || token.isEmpty() || port !in 1..65_535) return null
        return Link(deviceId, params["n"].orEmpty(), token, params["h"].orEmpty(), port)
    }

    private val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~".toSet()

    fun escape(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt().toChar()
            if (char in UNRESERVED) append(char) else append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }

    /**
     * 反解百分号转义。转义段按 UTF-8 字节还原，段间的裸字符原样保留，
     * 这样第三方实现发来的「设备名没转义」的链接也不会变成乱码。
     */
    fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        val pending = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val parsed = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (parsed != null) {
                    pending.add(parsed.toByte())
                    index += 3
                    continue
                }
            }
            out.append(flush(pending))
            out.append(char)
            index++
        }
        return out.append(flush(pending)).toString()
    }

    private fun flush(pending: ArrayList<Byte>): String {
        if (pending.isEmpty()) return ""
        val decoded = ByteArray(pending.size).also { array -> pending.forEachIndexed { i, b -> array[i] = b } }
            .toString(Charsets.UTF_8)
        pending.clear()
        return decoded
    }
}

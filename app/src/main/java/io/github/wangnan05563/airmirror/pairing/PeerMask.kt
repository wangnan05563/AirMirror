package io.github.wangnan05563.airmirror.pairing

/**
 * T06114：PIN 失败来源只存掩码，不把完整地址写进 SharedPreferences 或日志。
 * IPv4 保留前三段，IPv6 保留前四组；端口、方括号与空白一律先剥掉。
 */
object PeerMask {

    fun mask(raw: String): String {
        val peer = raw.trim()
            .removePrefix("[")
            .substringBefore(']')
            .substringBefore('%')
        if (peer.isEmpty()) return ""
        if (peer.contains(':')) {
            val head = peer.substringBefore(':')
            // IPv4:port（例如 192.168.1.42:54321）仍按 IPv4 处理；非法 IPv4 不能退回 IPv6 分支，
            // 否则拼接的 “300.1.2.3:80:*” 会把原地址原样带出去。
            if (peer.count { it == ':' } == 1 && head.contains('.')) {
                return ipv4Mask(head)
            }
            // IPv4-mapped IPv6（::ffff:192.168.1.42）尾段仍是完整 IPv4，必须先按 IPv4 掩码。
            val tail = peer.substringAfterLast(':')
            if (tail.contains('.')) return ipv4Mask(tail)
            return ipv6Mask(peer)
        }
        return if (peer.contains('.')) ipv4Mask(peer) else ""
    }

    private fun ipv4Mask(value: String): String {
        val parts = value.split('.')
        val invalid = parts.size != 4 || parts.any {
            val number = it.toIntOrNull()
            number == null || number !in 0..255
        }
        if (invalid) return ""
        return "${parts[0]}.${parts[1]}.${parts[2]}.*"
    }

    private fun ipv6Mask(value: String): String {
        val groups = value.split(':').filter { it.isNotEmpty() }
        if (groups.size < 2) return ""
        return groups.take(4).joinToString(":") + ":*"
    }
}

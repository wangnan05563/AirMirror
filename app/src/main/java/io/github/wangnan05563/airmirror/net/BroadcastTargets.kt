package io.github.wangnan05563.airmirror.net

/**
 * 受控广播该往哪些地址发（PRD FR-02 异常「组播被禁用：自动降级为受控广播/直连探测」）。
 *
 * 只在本机网卡确实带着掩码时才算网段定向广播：/31 与 /32 上没有广播可言，
 * 链路本地地址（组播被掐时网卡常只剩它）发出去也无人应答，一并跳过。
 */
object BroadcastTargets {
    /** 全局广播：任何 IPv4 网卡都收，路由器一般不往外转，够用。 */
    const val GLOBAL = "255.255.255.255"

    /** 一条本机 IPv4 接口地址：前缀长度按 CIDR 记（24 即 255.255.255.0）。 */
    data class Interface(val address: String, val prefixLength: Int)

    fun of(interfaces: List<Interface>, includeGlobal: Boolean = true): List<String> {
        val out = LinkedHashSet<String>()
        if (includeGlobal) out.add(GLOBAL)
        for (entry in interfaces) {
            val directed = directedBroadcast(entry) ?: continue
            out.add(directed)
        }
        return out.toList()
    }

    /** 网段定向广播地址：把主机位全置 1。前缀不小于 31、地址不成形或落在链路本地段则没有。 */
    fun directedBroadcast(entry: Interface): String? {
        val octets = entry.address.split('.').mapOrNullFourOctets() ?: return null
        if (entry.prefixLength >= 31 || entry.prefixLength < 8) return null
        if (octets[0] == 169 && octets[1] == 254) return null
        val hostBits = 32 - entry.prefixLength
        val raw = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
        val mask = -0x1 shl hostBits
        val broadcast = (raw and mask) or mask.inv()
        return listOf(broadcast shr 24 and 0xFF, broadcast shr 16 and 0xFF, broadcast shr 8 and 0xFF, broadcast and 0xFF)
            .joinToString(".")
    }

    private fun List<String>.mapOrNullFourOctets(): List<Int>? {
        if (size != 4) return null
        return map {
            val value = it.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            value
        }
    }
}

package io.github.wangnan05563.airmirror.protocol

/**
 * 私有通道协议版本与协商规则（PRD 14.5「协议版本化：新旧端可协商降级」）。
 */
object Version {
    const val CURRENT = 1
    const val MIN_SUPPORTED = 1

    /** 返回双方都支持的最高版本；无交集或区间非法时返回 null。 */
    fun negotiate(clientMin: Int, clientMax: Int, serverMin: Int, serverMax: Int): Int? {
        if (clientMin > clientMax || serverMin > serverMax) return null
        val low = maxOf(clientMin, serverMin)
        val high = minOf(clientMax, serverMax)
        return if (low <= high) high else null
    }
}

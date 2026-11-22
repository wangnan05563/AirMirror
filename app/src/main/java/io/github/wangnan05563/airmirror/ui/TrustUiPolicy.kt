package io.github.wangnan05563.airmirror.ui

import io.github.wangnan05563.airmirror.pairing.TrustRegistry

/**
 * T06113 信任设备中心的纯展示口径：相对时间、指纹缩写、空态与行文案都不依赖 Android，
 * 便于在 JVM 单测里钉死边界（时钟回拨、刚刚、跨天与 30 天过期）。
 * 可信记录本身由 [TrustRegistry] 过滤过期项，这里只负责“怎么读”。
 */
object TrustUiPolicy {

    const val MAX_NAME = 24

    /** 指纹在列表里只露头尾，详情里仍给完整值，避免同名前缀设备无法区分。 */
    fun shortFingerprint(fingerprint: String): String {
        val fp = fingerprint.trim()
        if (fp.length <= 12) return fp
        return fp.take(8) + "…" + fp.takeLast(4)
    }

    /** 相对时间：30 天内的可信记录才会进入列表，因此不需要再显示“已过期”。 */
    fun lastUsedLabel(lastUsedAt: Long, nowMs: Long): String {
        if (lastUsedAt <= 0L) return "尚未使用"
        val diff = (nowMs - lastUsedAt).coerceAtLeast(0L)
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            else -> "${diff / 86_400_000L} 天前"
        }
    }

    fun summary(count: Int): String =
        if (count <= 0) "尚无信任设备 · 新设备连接需先通过访问控制"
        else "已信任 $count 台 · 30 天内使用可免确认"

    fun rowText(device: TrustRegistry.TrustedDevice, nowMs: Long, casting: Boolean): String {
        val last = lastUsedLabel(device.lastUsedAt, nowMs)
        val active = if (casting) " · 正在投屏" else ""
        return "${device.name}\n指纹 ${shortFingerprint(device.fingerprint)} · 上次使用 $last$active"
    }

    fun detailMessage(device: TrustRegistry.TrustedDevice, nowMs: Long, casting: Boolean): String {
        val active = if (casting) " · 正在投屏" else ""
        return buildString {
            append("设备名：").append(device.name).append('\n')
            append("指纹：").append(device.fingerprint).append('\n')
            append("上次使用：").append(lastUsedLabel(device.lastUsedAt, nowMs)).append(active).append('\n')
            append("状态：可信 · 30 天内使用自动回连")
        }
    }

    /** 输入框同一口径：去掉首尾空白后必须非空且不超过 [MAX_NAME]。 */
    fun normalizeName(raw: String): String? {
        val name = raw.trim()
        return name.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
    }
}

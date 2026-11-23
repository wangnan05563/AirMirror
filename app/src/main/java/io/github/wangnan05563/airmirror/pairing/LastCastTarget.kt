package io.github.wangnan05563.airmirror.pairing

/**
 * 发送端侧的「上次成功投屏设备」记录（T06105）。
 *
 * 接收端的 [TrustRegistry] 记录的是“哪些发送端被允许”，发送端无法读取；本机只有
 * “哪台电视真正出过首帧”这一个可信事实，因此两步投屏的自动选中以它为准：
 * 只有恰好一台在线设备命中这份记录，才允许自动连。记录带 30 天有效期，
 * 与 PRD 8.1 对信任设备的口径一致；过期或损坏一律回到手动列表，不猜。
 */
object LastCastTarget {

    const val DEFAULT_TTL = 30L * 24 * 60 * 60 * 1000

    data class Record(
        val fingerprint: String,
        val name: String,
        val usedAt: Long,
    ) {
        /** 时钟回拨时不让旧记录突然过期；真正超过有效期才失效。 */
        fun fresh(now: Long, ttl: Long = DEFAULT_TTL): Boolean {
            if (fingerprint.isBlank() || usedAt <= 0L) return false
            if (now < usedAt) return true
            return now - usedAt < ttl
        }
    }

    /** 落盘格式：`fingerprint|hex(name)|usedAt`；名字走十六进制，避免分隔符与编码问题。 */
    fun encode(record: Record): String = listOf(
        record.fingerprint,
        hexEncode(record.name),
        record.usedAt.toString(),
    ).joinToString("|")

    /** 任一字段损坏都返回 null：宁可重新选电视，也不拿半截记录自动连错。 */
    fun decode(text: String): Record? {
        if (text.isBlank()) return null
        val parts = text.split('|')
        if (parts.size != 3) return null
        val fingerprint = parts[0].trim()
        if (fingerprint.isEmpty()) return null
        val name = hexDecodeOrNull(parts[1]) ?: return null
        val usedAt = parts[2].toLongOrNull() ?: return null
        if (usedAt <= 0L) return null
        return Record(fingerprint, name, usedAt)
    }

    private fun hexEncode(value: String): String = buildString(value.length * 2) {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            append(("%02X").format(byte.toInt() and 0xFF))
        }
    }

    private fun hexDecodeOrNull(value: String): String? {
        if (value.length % 2 != 0) return null
        val bytes = ByteArray(value.length / 2)
        for (i in bytes.indices) {
            val parsed = value.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            bytes[i] = parsed.toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }
}

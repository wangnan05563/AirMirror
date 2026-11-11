package io.github.wangnan05563.airmirror.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 读到 EOF，但总量封顶。
 *
 * [InputStream.readBytes] 会一直读到流结束：对端（或中间人）只要持续回字节就能把堆撑爆，
 * 而 socket 的 `readTimeout` 只约束「单次 read 等多久」，对总量没有任何约束。
 * 信令与更新检查的响应都是小消息，超过上限只可能是异常或恶意流量。
 */
fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "maxBytes must be positive: $maxBytes" }
    val out = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("response larger than $maxBytes bytes")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

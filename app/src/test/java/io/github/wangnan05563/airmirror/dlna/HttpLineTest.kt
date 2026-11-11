package io.github.wangnan05563.airmirror.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/** DLNA 的 HTTP 行读取边界：正常行要原样返回，超长行必须被拒而不是无限吃内存。 */
class HttpLineTest {

    @Test
    fun lineIsReturnedWithoutCarriageReturn() {
        val input = ByteArrayInputStream("GET /description.xml HTTP/1.1\r\n".toByteArray(Charsets.US_ASCII))
        assertEquals("GET /description.xml HTTP/1.1", input.readAsciiLine())
    }

    @Test
    fun emptyStreamReadsAsNull() {
        assertNull(ByteArrayInputStream(ByteArray(0)).readAsciiLine())
    }

    @Test
    fun overlongLineIsRejectedInsteadOfGrowingForever() {
        // 客户端一直发字节不换行时，socket 读超时永远不会触发（每次 read 都有数据），
        // 行缓冲必须有独立上限，否则它会一路涨到 OOM。
        val input = ByteArrayInputStream(ByteArray(64 * 1024) { 'A'.code.toByte() })
        assertNull(input.readAsciiLine())
    }
}

package io.github.wangnan05563.airmirror.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** 读取上限的边界：正常响应原样返回，超限必须当场失败而不是继续吃内存。 */
class LimitedReadTest {

    @Test
    fun payloadUnderTheLimitIsReturnedWhole() {
        val payload = ByteArray(1_000) { (it % 251).toByte() }
        assertArrayEquals(payload, ByteArrayInputStream(payload).readAtMost(4_096))
    }

    @Test
    fun payloadExactlyAtTheLimitIsAccepted() {
        val payload = ByteArray(4_096) { 7 }
        assertArrayEquals(payload, ByteArrayInputStream(payload).readAtMost(4_096))
    }

    @Test
    fun payloadOverTheLimitFailsInsteadOfGrowingForever() {
        val payload = ByteArray(4_097) { 7 }
        assertThrows(IOException::class.java) { ByteArrayInputStream(payload).readAtMost(4_096) }
    }
}

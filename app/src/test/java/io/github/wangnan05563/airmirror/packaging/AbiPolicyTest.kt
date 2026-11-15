package io.github.wangnan05563.airmirror.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbiPolicyTest {

    @Test
    fun `MIN_SDK 守住 Q-5 重签后的下限 22`() {
        // Q-5 原裁 19（Android 4.4）；2026-11-12 T04908 裁决 1 重签为 22（traversal API），
        // 本常量此前仍写 19，与 app/build.gradle.kts 的 minSdk 漂移，本次一并归位。
        assertEquals(22, AbiPolicy.MIN_SDK)
    }

    @Test
    fun `支持的 ABI 只有 arm32 与 arm64 两个`() {
        assertEquals(setOf("armeabi-v7a", "arm64-v8a"), AbiPolicy.SUPPORTED_ABIS)
        assertEquals("armeabi-v7a", AbiPolicy.DEFAULT_ABI)
        assertEquals("x86_64", AbiPolicy.EMULATOR_ABI)
        assertTrue(AbiPolicy.isSupported("armeabi-v7a"))
        assertTrue(AbiPolicy.isSupported("arm64-v8a"))
        assertFalse(AbiPolicy.isSupported("x86_64"))
    }

    @Test
    fun `64 位设备优先选 arm64-v8a`() {
        assertEquals("arm64-v8a", AbiPolicy.selectBest(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("arm64-v8a", AbiPolicy.selectBest(listOf("x86_64", "arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun `只有 32 位的老盒子退回 armeabi-v7a`() {
        assertEquals("armeabi-v7a", AbiPolicy.selectBest(listOf("armeabi-v7a")))
        assertEquals("armeabi-v7a", AbiPolicy.selectBest(listOf("armeabi-v7a", "armeabi")))
    }

    @Test
    fun `仅 x86 系的模拟器或开发机返回 null 由 universal 包覆盖`() {
        assertNull(AbiPolicy.selectBest(listOf("x86_64")))
        assertNull(AbiPolicy.selectBest(listOf("x86")))
        assertNull(AbiPolicy.selectBest(emptyList()))
    }
}

package io.github.wangnan05563.airmirror.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGradeTest {

    private fun facts(
        api: Int = 30,
        ram: Long = 4_000,
        decodeW: Int = 3840,
        decodeH: Int = 2160,
        hw: Boolean = true,
        output4k: Boolean = true,
        storage: Long = 4_000,
        wifi: Boolean = true,
        localService: Boolean = true,
    ) = DeviceFacts(api, ram, decodeW, decodeH, hw, output4k, storage, wifi, localService)

    @Test
    fun `android 10 plus 3gb and 4k everywhere is grade A`() {
        val policy = DeviceGrading.policyFor(facts())
        assertEquals(Grade.A, policy.grade)
        assertEquals(QualityTier.UHD60, policy.maxTier)
        assertTrue(policy.protocolsResident)
        assertTrue(policy.animationsEnabled)
        assertEquals(7, policy.logRetentionDays)
        assertEquals(180, policy.residentMemoryTargetMb)
        assertTrue(policy.unsupportedReason.isEmpty())
    }

    @Test
    fun `no 4k output drops A to B`() {
        val policy = DeviceGrading.policyFor(facts(output4k = false))
        assertEquals(Grade.B, policy.grade)
        assertEquals(QualityTier.FHD60, policy.maxTier)
        assertFalse(policy.animationsEnabled)
        assertEquals(3, policy.logRetentionDays)
    }

    @Test
    fun `android 9 is grade B even with plenty of memory`() {
        assertEquals(Grade.B, DeviceGrading.gradeOf(facts(api = 28)))
    }

    @Test
    fun `android 6 is grade C regardless of hardware`() {
        val policy = DeviceGrading.policyFor(facts(api = 23))
        assertEquals(Grade.C, policy.grade)
        assertEquals(QualityTier.HD30, policy.maxTier)
        assertFalse(policy.protocolsResident)
        assertEquals(0, policy.logRetentionDays)
        assertEquals(80, policy.residentMemoryTargetMb)
    }

    @Test
    fun `low memory drops to C`() {
        assertEquals(Grade.C, DeviceGrading.gradeOf(facts(ram = 1_900)))
    }

    @Test
    fun `no hardware decoder is C and says why`() {
        val policy = DeviceGrading.policyFor(facts(hw = false, decodeW = 0, decodeH = 0))
        assertEquals(Grade.C, policy.grade)
        assertEquals(QualityTier.HD30, policy.maxTier)
        assertTrue(policy.reasons.contains("硬解达不到 720p"))
    }

    @Test
    fun `decoder that cannot reach 1080p drops A to C`() {
        val policy = DeviceGrading.policyFor(facts(decodeW = 1280, decodeH = 720))
        assertEquals(Grade.C, policy.grade)
        assertEquals(QualityTier.HD30, policy.maxTier)
        assertTrue(policy.reasons.isEmpty())
    }

    @Test
    fun `old api gets an actionable unsupported message`() {
        val policy = DeviceGrading.policyFor(facts(api = 18))
        assertEquals(Grade.UNSUPPORTED, policy.grade)
        assertEquals(QualityTier.OFF, policy.maxTier)
        assertTrue(policy.unsupportedReason.contains("Android 4.4"))
        assertTrue(policy.unsupportedReason.contains("自带投屏"))
    }

    @Test
    fun `no wifi is unsupported and explains the fallback`() {
        val policy = DeviceGrading.policyFor(facts(wifi = false))
        assertEquals(Grade.UNSUPPORTED, policy.grade)
        assertTrue(policy.unsupportedReason.contains("Wi-Fi"))
    }

    @Test
    fun `storage under 100mb is unsupported`() {
        val policy = DeviceGrading.policyFor(facts(storage = 99))
        assertEquals(Grade.UNSUPPORTED, policy.grade)
        assertTrue(policy.unsupportedReason.contains("清理"))
    }

    @Test
    fun `system that cannot host a local service is unsupported`() {
        val policy = DeviceGrading.policyFor(facts(localService = false))
        assertEquals(Grade.UNSUPPORTED, policy.grade)
        assertTrue(policy.unsupportedReason.contains("裁剪"))
    }

    @Test
    fun `one hundred mb of storage is still allowed`() {
        assertEquals(Grade.A, DeviceGrading.gradeOf(facts(storage = 100)))
    }

    @Test
    fun `summary names the grade and the top quality`() {
        val summary = DeviceGrading.summary(DeviceGrading.policyFor(facts()))
        assertTrue(summary.contains("A"))
        assertTrue(summary.contains("4K"))
    }
}

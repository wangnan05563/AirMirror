package io.github.wangnan05563.airmirror.ui

import io.github.wangnan05563.airmirror.pairing.TrustRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T06113：信任列表的展示口径与边界，不依赖 Android。 */
class TrustUiPolicyTest {

    private val now = 1_700_000_000_000L
    private fun device(
        name: String = "客厅电视",
        fingerprint: String = "fp-phone-a-0123456789",
        lastUsedAt: Long = now,
    ) = TrustRegistry.TrustedDevice(fingerprint, name, lastUsedAt)

    @Test
    fun relativeTimeCoversJustNowMinutesHoursAndDays() {
        assertEquals("刚刚", TrustUiPolicy.lastUsedLabel(now - 59_000L, now))
        assertEquals("1 分钟前", TrustUiPolicy.lastUsedLabel(now - 60_000L, now))
        assertEquals("59 分钟前", TrustUiPolicy.lastUsedLabel(now - 59 * 60_000L, now))
        assertEquals("1 小时前", TrustUiPolicy.lastUsedLabel(now - 60 * 60_000L, now))
        assertEquals("23 小时前", TrustUiPolicy.lastUsedLabel(now - 23 * 3_600_000L, now))
        assertEquals("1 天前", TrustUiPolicy.lastUsedLabel(now - 24 * 3_600_000L, now))
        assertEquals("29 天前", TrustUiPolicy.lastUsedLabel(now - 29 * 86_400_000L, now))
    }

    @Test
    fun clockRollbackAndZeroTimestampDoNotProduceNegativeLabels() {
        assertEquals("刚刚", TrustUiPolicy.lastUsedLabel(now + 5_000L, now))
        assertEquals("尚未使用", TrustUiPolicy.lastUsedLabel(0L, now))
    }

    @Test
    fun shortFingerprintKeepsShortValuesAndElidesLongOnes() {
        assertEquals("abc123", TrustUiPolicy.shortFingerprint("abc123"))
        assertEquals("abcdefgh…6789", TrustUiPolicy.shortFingerprint("abcdefghijklmnop6789"))
        assertEquals("abcdefgh…6789", TrustUiPolicy.shortFingerprint("  abcdefghijklmnop6789  "))
    }

    @Test
    fun summaryDistinguishesEmptyAndPopulatedLists() {
        assertEquals("尚无信任设备 · 新设备连接需先通过访问控制", TrustUiPolicy.summary(0))
        assertEquals("已信任 3 台 · 30 天内使用可免确认", TrustUiPolicy.summary(3))
    }

    @Test
    fun rowTextShowsNameFingerprintLastUseAndCastingState() {
        val row = TrustUiPolicy.rowText(device(lastUsedAt = now - 5 * 60_000L), now, casting = false)
        assertTrue(row.startsWith("客厅电视\n"))
        assertTrue(row.contains("指纹 fp-phone…6789"))
        assertTrue(row.contains("上次使用 5 分钟前"))
        assertFalse(row.contains("正在投屏"))
        assertTrue(TrustUiPolicy.rowText(device(), now, casting = true).contains("正在投屏"))
    }

    @Test
    fun detailMessageKeepsTheFullFingerprint() {
        val text = TrustUiPolicy.detailMessage(device(), now, casting = true)
        assertTrue(text.contains("设备名：客厅电视"))
        assertTrue(text.contains("指纹：fp-phone-a-0123456789"))
        assertTrue(text.contains("上次使用：刚刚"))
        assertTrue(text.contains("正在投屏"))
        assertTrue(text.contains("30 天内使用自动回连"))
    }

    @Test
    fun normalizeNameTrimsAndEnforcesTheLengthLimit() {
        assertEquals("书房电视", TrustUiPolicy.normalizeName("  书房电视  "))
        assertNull(TrustUiPolicy.normalizeName("   "))
        assertNull(TrustUiPolicy.normalizeName("超".repeat(25)))
        assertEquals("超".repeat(24), TrustUiPolicy.normalizeName("超".repeat(24)))
    }
}

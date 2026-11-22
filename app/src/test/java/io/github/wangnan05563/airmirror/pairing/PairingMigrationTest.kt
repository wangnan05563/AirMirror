package io.github.wangnan05563.airmirror.pairing

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T06112：TrustRegistry 的版本化落盘要在 Pairing 启动时真正生效，
 * 不能只有内存单测。迁移成功写回 v2；迁移失败留下空表并回到每次确认。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingMigrationTest {

    private var clock = 1_000_000L

    @Test
    fun legacySnapshotIsMigratedAndPersistedAsV2() {
        val store = DeviceStore(ApplicationProvider.getApplicationContext())
        store.saveTrustedSnapshot("phone-a\t小明的手机\t$clock")
        val pairing = Pairing(store, now = { clock })
        assertTrue(store.trustedSnapshot().startsWith("# AirMirror TrustRegistry v2\n"))
        assertEquals(TrustRegistry.Verdict.TRUSTED, pairing.trust.decide("phone-a").verdict)
    }

    @Test
    fun unknownSchemaFallsBackToAskAndDoesNotKeepRetryingTheBadFile() {
        val store = DeviceStore(ApplicationProvider.getApplicationContext())
        store.saveTrustedSnapshot("# AirMirror TrustRegistry v9\nphone-a\t小明的手机\t$clock")
        val pairing = Pairing(store, now = { clock })
        assertTrue(pairing.trust.trusted().isEmpty())
        assertEquals(TrustRegistry.Verdict.ASK, pairing.trust.decide("phone-a").verdict)
        assertEquals("# AirMirror TrustRegistry v2", store.trustedSnapshot())
    }

    @Test
    fun clearTrustedWritesAnEmptyV2Snapshot() {
        val store = DeviceStore(ApplicationProvider.getApplicationContext())
        store.saveTrustedSnapshot("phone-a\t小明的手机\t$clock")
        val pairing = Pairing(store, now = { clock })
        assertTrue(pairing.trust.trusted().isNotEmpty())
        pairing.clearTrusted()
        assertTrue(pairing.trust.trusted().isEmpty())
        assertEquals("# AirMirror TrustRegistry v2", store.trustedSnapshot())
    }

    @Test
    fun renameAndRevokeArePersistedImmediately() {
        val store = DeviceStore(ApplicationProvider.getApplicationContext())
        val pairing = Pairing(store, now = { clock })
        pairing.trust.allow("phone-a", "小明的手机", permanent = true)
        pairing.persistTrusted()

        assertTrue(pairing.renameTrusted("phone-a", "书房手机"))
        assertTrue(store.trustedSnapshot().contains(PairingLink.escape("书房手机")))
        assertEquals("书房手机", Pairing(store, now = { clock }).trust.trusted().single().name)
        assertFalse(pairing.renameTrusted("phone-x", "不存在"))
        assertFalse(pairing.renameTrusted("phone-a", "   "))
        assertFalse(pairing.renameTrusted("phone-a", "超".repeat(25)))

        assertFalse(pairing.revokeTrusted("phone-a"))
        assertEquals("# AirMirror TrustRegistry v2", store.trustedSnapshot())
        assertTrue(Pairing(store, now = { clock }).trust.trusted().isEmpty())
    }

    @Test
    fun revokeReportsAnActiveTrustedSession() {
        val store = DeviceStore(ApplicationProvider.getApplicationContext())
        val pairing = Pairing(store, now = { clock })
        pairing.trust.allow("phone-a", "小明的手机", permanent = true)
        assertTrue(pairing.trust.acquireSession("phone-a"))
        assertTrue(pairing.revokeTrusted("phone-a"))
        assertNull(pairing.trust.active())
    }
}

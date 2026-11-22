package io.github.wangnan05563.airmirror.pairing

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
}

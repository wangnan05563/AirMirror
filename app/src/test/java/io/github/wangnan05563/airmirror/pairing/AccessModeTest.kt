package io.github.wangnan05563.airmirror.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** T06113：三档模式的默认值、非法落盘回落与跨实例持久化。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessModeTest {

    @Test
    fun unknownOrMissingValueFallsBackToConfirm() {
        assertEquals(AccessMode.CONFIRM, AccessMode.parse(null))
        assertEquals(AccessMode.CONFIRM, AccessMode.parse(""))
        assertEquals(AccessMode.CONFIRM, AccessMode.parse("PASSWORD"))
        assertEquals(AccessMode.PIN, AccessMode.parse("PIN"))
        assertEquals(AccessMode.TRUSTED_ONLY, AccessMode.parse("TRUSTED_ONLY"))
    }

    @Test
    fun deviceStoreDefaultsToConfirmAndPersistsTheSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = DeviceStore(context)
        assertEquals(AccessMode.CONFIRM, first.accessMode())
        first.saveAccessMode(AccessMode.TRUSTED_ONLY)
        assertEquals(AccessMode.TRUSTED_ONLY, DeviceStore(context).accessMode())
        first.saveAccessMode(AccessMode.PIN)
        assertEquals(AccessMode.PIN, DeviceStore(context).accessMode())
    }
}

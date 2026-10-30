package dev.earshot.virtual

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The demo-mode switch is read with blocking calls inside the connection loop and the family
 * routing: an unreadable file must read as "off", never throw there.
 */
class DemoModeStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun close() = scope.cancel()

    /** A corrupt file the handler could not replace, on full storage. */
    private class BrokenStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw CorruptionException("Unable to parse preferences proto.") }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("No space left on device")
    }

    @Test fun `an unreadable demo mode file reads as off`() {
        val demo = DemoMode(BrokenStore())
        assertFalse(demo.isEnabledBlocking())
        assertEquals(DemoDevice.QC_ULTRA, demo.deviceBlocking())
    }

    @Test fun `the switch and the device survive a round trip`() = runBlocking<Unit> {
        val demo = DemoMode(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "demo_mode.preferences_pb") })
        assertFalse(demo.isEnabledBlocking())
        demo.set(true)
        demo.setDevice(DemoDevice.CRUSHER)
        assertTrue(demo.isEnabledBlocking())
        assertEquals(DemoDevice.CRUSHER, demo.deviceBlocking())
    }
}

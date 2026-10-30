package dev.earshot.audio

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.ConnectionState
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.fake.FakeAudioTuningRepository
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.dsp.EqPreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-headset EQ memory against a real file-backed DataStore: it never forgets a user preset
 * before the tuning store has loaded, and a failing store never reaches the (handler-less)
 * application scope.
 */
class HeadsetEqMemoryTest {

    @get:Rule val folder = TemporaryFolder()

    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Like the application scope (SupervisorJob, Default); the handler only records what would crash it. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> uncaught += t })

    @After fun close() {
        appScope.cancel()
        storeScope.cancel()
    }

    private fun fileStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = storeScope) { File(folder.root, "headset_eq.preferences_pb") }

    private fun key(address: String) = stringPreferencesKey("preset_$address")

    private class TestHeadphones : HeadphoneRepository by FakeHeadphoneRepository(commandDelayMillis = 0) {
        private val _state = MutableStateFlow(HeadphoneState())
        override val state: StateFlow<HeadphoneState> = _state

        /** [address] is now linked for audio (a generic headset needs nothing more). */
        fun audioActive(address: String) {
            _state.value = HeadphoneState(connection = ConnectionState(deviceAddress = address, audioLinked = true))
        }
    }

    /** The fake tuning repository, plus the asynchronous restore the real one has. */
    private class GatedTuning(val inner: FakeAudioTuningRepository = FakeAudioTuningRepository()) :
        AudioTuningRepository by inner, TuningRestore {
        val restored = CompletableDeferred<Unit>()
        override suspend fun awaitRestored() = restored.await()
    }

    /** A store that fails like a corrupt file (reads) or full storage (writes) when told to. */
    private class FlakyStore(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var failingReads = 0
        @Volatile var failWrites = false
        @Volatile var reads = 0

        override val data: Flow<Preferences> = flow {
            reads++
            if (failingReads > 0) {
                failingReads--
                throw CorruptionException("Unable to parse preferences proto.")
            }
            emitAll(real.data)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("No space left on device")
            return real.updateData(transform)
        }
    }

    private suspend fun waitUntil(condition: suspend () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test fun `a user preset is not forgotten while the tuning store is still loading`() = runBlocking<Unit> {
        val store = fileStore()
        store.edit { it[key(ADDRESS)] = "mine" }
        val tuning = GatedTuning()
        val headphones = TestHeadphones()
        HeadsetEqMemory(store, headphones, tuning, appScope).start()

        // A Bluetooth connect started the process: the headset is audio-active before the
        // repository has published the user presets (only the built-ins are listed yet).
        headphones.audioActive(ADDRESS)
        delay(300)
        assertEquals("mine", store.data.first()[key(ADDRESS)], "kept while the presets are unknown")

        // restore() publishes the user presets, then completes.
        tuning.inner.savePreset(EqPreset("mine", "Mine", bands = emptyList()))
        tuning.restored.complete(Unit)
        waitUntil { tuning.state.value.activePreset?.id == "mine" }
        assertEquals("mine", store.data.first()[key(ADDRESS)])
        assertTrue(uncaught.isEmpty(), "uncaught: $uncaught")
    }

    @Test fun `a preset that is really gone is forgotten once the tuning store has loaded`() = runBlocking<Unit> {
        val store = fileStore()
        store.edit { it[key(ADDRESS)] = "deleted" }
        val tuning = GatedTuning().also { it.restored.complete(Unit) }
        val headphones = TestHeadphones()
        HeadsetEqMemory(store, headphones, tuning, appScope).start()

        headphones.audioActive(ADDRESS)
        waitUntil { store.data.first()[key(ADDRESS)] == null }
        assertNull(tuning.state.value.activePreset)
        assertTrue(uncaught.isEmpty(), "uncaught: $uncaught")
    }

    @Test fun `a failing store never crashes the process and the next headset still gets its preset`() = runBlocking<Unit> {
        val real = fileStore()
        real.edit { it[key(OTHER)] = "bass" }
        val store = FlakyStore(real).apply {
            failingReads = 1 // the first headset's read hits a corrupt file
            failWrites = true // and the storage is full
        }
        val tuning = GatedTuning().also { it.restored.complete(Unit) }
        val headphones = TestHeadphones()
        val memory = HeadsetEqMemory(store, headphones, tuning, appScope)
        memory.start()

        headphones.audioActive(ADDRESS)
        waitUntil { store.reads >= 1 }
        memory.rememberPick("vocal") // the user taps a preset on the Sound screen
        delay(200)

        // The collector survived both: the next headset still gets its remembered preset.
        headphones.audioActive(OTHER)
        waitUntil { tuning.state.value.activePreset?.id == "bass" }
        assertTrue(uncaught.isEmpty(), "uncaught: $uncaught")
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
        const val OTHER = "AA:BB:CC:DD:EE:02"
    }
}

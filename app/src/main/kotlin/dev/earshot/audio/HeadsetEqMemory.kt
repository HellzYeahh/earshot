package dev.earshot.audio

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.HeadphoneRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

// A corrupt file (a write cut off by power loss) is replaced with an empty one: the picks are lost,
// but reading it no longer throws each time a headset becomes audio-active.
private val Context.headsetEqStore: DataStore<Preferences> by preferencesDataStore(
    name = "headset_eq",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Pro EQ remembers a preset per headset: the one picked on the Sound screen while that headset was
 * in use is applied again when it next connects for audio. Each pair sounds different, so the
 * Bose's correction should not follow the Soundcore. Works for any headset (only Android's audio
 * link is needed).
 *
 * Only explicit picks are remembered ([rememberPick], called by the Sound screen), never a preset
 * an automation or a per-app profile switched to: those are temporary by design.
 *
 * Runs in the application scope, which has no exception handler: a store failure (full storage,
 * an unreadable file) must end as a log line here, never as an uncaught exception, or every
 * headset connect would crash the process.
 */
class HeadsetEqMemory internal constructor(
    private val store: DataStore<Preferences>,
    private val headphones: HeadphoneRepository,
    private val tuning: AudioTuningRepository,
    private val scope: CoroutineScope,
) {
    constructor(context: Context, headphones: HeadphoneRepository, tuning: AudioTuningRepository, scope: CoroutineScope) :
        this(context.applicationContext.headsetEqStore, headphones, tuning, scope)

    @Volatile
    private var current: String? = null

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            headphones.state
                .map { s -> s.connection.deviceAddress?.uppercase()?.takeIf { s.isAudioActive } }
                .distinctUntilChanged()
                .collect { address ->
                    current = address
                    if (address != null) applyFor(address)
                }
        }
    }

    /** The user picked [presetId] (null = flat / off) while [current] is in use: remember it for that headset. */
    fun rememberPick(presetId: String?) {
        val address = current ?: return
        scope.launch {
            try {
                store.edit { prefs -> if (presetId == null) prefs.remove(keyFor(address)) else prefs[keyFor(address)] = presetId }
                logI("remembered EQ preset ${presetId ?: "none"} for $address")
            } catch (e: IOException) {
                // The preset is applied already; only remembering it for next time failed.
                logW("could not remember the EQ preset for $address: ${e.message}")
            }
        }
    }

    private suspend fun applyFor(address: String) {
        try {
            val key = keyFor(address)
            val id = store.data.first()[key] ?: return
            // The repository loads the user presets asynchronously at process start; until then
            // only the built-ins are listed, and a saved user preset would look deleted (and be
            // forgotten below for good) when a Bluetooth connect is what started the process.
            (tuning as? TuningRestore)?.awaitRestored()
            if (tuning.state.value.activePreset?.id == id) return
            if (tuning.presets.value.none { it.id == id }) {
                logI("preset $id for $address no longer exists; forgetting it")
                // Only if it still names that preset: the user may have picked another one meanwhile.
                store.edit { prefs -> if (prefs[key] == id) prefs.remove(key) }
                return
            }
            logI("headset $address connected: applying its EQ preset $id")
            tuning.applyPreset(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logW("headset EQ store unavailable for $address: ${e.message}")
        } catch (e: Exception) {
            logW("could not apply the EQ preset for $address: ${e.message}")
        }
    }

    private fun keyFor(address: String) = stringPreferencesKey("preset_$address")

    // Wrapped so this also runs in plain JVM unit tests, where android.util.Log is a stub that throws.
    private fun logI(msg: String) { runCatching { Log.i(TAG, msg) } }
    private fun logW(msg: String) { runCatching { Log.w(TAG, msg) } }

    private companion object {
        const val TAG = "Earshot/HeadsetEq"
    }
}

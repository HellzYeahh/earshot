package dev.earshot.audio

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.HeadphoneRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.headsetEqStore: DataStore<Preferences> by preferencesDataStore(name = "headset_eq")

/**
 * Pro EQ remembers a preset per headset: the one picked on the Sound screen while that headset was
 * in use is applied again when it next connects for audio. Each pair sounds different, so the
 * Bose's correction should not follow the Soundcore. Works for any headset (only Android's audio
 * link is needed).
 *
 * Only explicit picks are remembered ([rememberPick], called by the Sound screen), never a preset
 * an automation or a per-app profile switched to: those are temporary by design.
 */
class HeadsetEqMemory(
    context: Context,
    private val headphones: HeadphoneRepository,
    private val tuning: AudioTuningRepository,
    private val scope: CoroutineScope,
) {
    private val store = context.applicationContext.headsetEqStore

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
            store.edit { prefs -> if (presetId == null) prefs.remove(keyFor(address)) else prefs[keyFor(address)] = presetId }
            Log.i(TAG, "remembered EQ preset ${presetId ?: "none"} for $address")
        }
    }

    private suspend fun applyFor(address: String) {
        val id = store.data.first()[keyFor(address)] ?: return
        if (tuning.state.value.activePreset?.id == id) return
        if (tuning.presets.value.none { it.id == id }) {
            Log.i(TAG, "preset $id for $address no longer exists; forgetting it")
            store.edit { it.remove(keyFor(address)) }
            return
        }
        Log.i(TAG, "headset $address connected: applying its EQ preset $id")
        runCatching { tuning.applyPreset(id) }.onFailure { Log.w(TAG, "could not apply $id: ${it.message}") }
    }

    private fun keyFor(address: String) = stringPreferencesKey("preset_$address")

    private companion object {
        const val TAG = "Earshot/HeadsetEq"
    }
}

package dev.earshot.call

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.earshot.core.CallAudioPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

// File-private delegate: exactly one DataStore per process for this file. A corrupt file is
// replaced with an empty one, as for the other stores: without the handler every read and every
// save throws CorruptionException, so the call-boost settings could never be changed again until
// the app's data was cleared. Losing it costs only these few switches (back to their defaults).
private val Context.callAudioDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "call_audio",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal class CallAudioPrefsStore(context: Context) {
    private val store = context.applicationContext.callAudioDataStore

    val preferences: Flow<CallAudioPreferences> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            val defaults = CallAudioPreferences()
            CallAudioPreferences(
                boostEnabled = p[BOOST_ENABLED] ?: defaults.boostEnabled,
                boostPercent = (p[BOOST_PERCENT] ?: defaults.boostPercent).coerceIn(CallAudioPreferences.MIN_BOOST_PERCENT, 100),
                alsoRaiseHeadset = p[RAISE_HEADSET] ?: defaults.alsoRaiseHeadset,
            )
        }

    suspend fun save(p: CallAudioPreferences) {
        store.edit {
            it[BOOST_ENABLED] = p.boostEnabled
            it[BOOST_PERCENT] = p.boostPercent
            it[RAISE_HEADSET] = p.alsoRaiseHeadset
        }
    }

    private companion object {
        val BOOST_ENABLED = booleanPreferencesKey("boost_enabled")
        val BOOST_PERCENT = intPreferencesKey("boost_percent")
        val RAISE_HEADSET = booleanPreferencesKey("also_raise_headset")
    }
}

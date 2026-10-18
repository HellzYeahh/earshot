package dev.earshot.shortcut

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

// File-private delegate: exactly one DataStore per process for this file.
private val Context.shortcutDataStore: DataStore<Preferences> by preferencesDataStore(name = "headset_shortcuts")

/**
 * Shortcuts remapper settings: default action, per-headset choices, spoken feedback and whether a
 * real headset press has reached Earshot yet. The keys and their JSON are the first build's; new
 * fields only add keys (see [ShortcutCodec.decodeSettings]).
 */
class ShortcutPrefs(context: Context) {
    private val store = context.applicationContext.shortcutDataStore

    val settings: Flow<ShortcutSettings> = store.data
        .catch { e ->
            if (e is IOException) { Log.w(TAG, "shortcut store unreadable, using defaults", e); emit(emptyPreferences()) } else throw e
        }
        .map(::decode)

    suspend fun current(): ShortcutSettings = settings.first()

    suspend fun update(transform: (ShortcutSettings) -> ShortcutSettings) {
        store.edit { p ->
            val after = transform(decode(p))
            p[DEFAULT_ACTION] = ShortcutCodec.encodeAction(after.defaultAction)
            p[OVERRIDES] = ShortcutCodec.encodeOverrides(after.overrides)
            p[SPOKEN_FEEDBACK] = after.spokenFeedback
            p[SEEN_HEADSET_PRESS] = after.seenHeadsetPress
        }
    }

    /** Records that a real headset press reached the trigger (once; later calls don't write). */
    suspend fun markHeadsetPressSeen() {
        store.edit { p -> if (p[SEEN_HEADSET_PRESS] != true) p[SEEN_HEADSET_PRESS] = true }
    }

    private fun decode(p: Preferences): ShortcutSettings =
        ShortcutCodec.decodeSettings(p[DEFAULT_ACTION], p[OVERRIDES], p[SPOKEN_FEEDBACK], p[SEEN_HEADSET_PRESS])

    private companion object {
        const val TAG = "Earshot/Shortcut"
        val DEFAULT_ACTION = stringPreferencesKey("default_action")
        val OVERRIDES = stringPreferencesKey("overrides")
        val SPOKEN_FEEDBACK = booleanPreferencesKey("spoken_feedback")
        val SEEN_HEADSET_PRESS = booleanPreferencesKey("seen_headset_press")
    }
}

package dev.earshot.bt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The headphones the user picked (or we auto-detected) last time. */
data class SavedDevice(val address: String, val name: String?)

// File-private delegate so there is exactly one DataStore instance for this file per process.
private val Context.btDeviceDataStore: DataStore<Preferences> by preferencesDataStore(name = "bt_device")

/**
 * Tiny DataStore holding the Bluetooth address of the chosen QC Ultra, so reconnects and the
 * manifest receiver can recognise the right device without heuristics.
 */
class DevicePrefs(context: Context) {
    private val store = context.applicationContext.btDeviceDataStore

    val savedDevice: Flow<SavedDevice?> = store.data.map { prefs ->
        prefs[KEY_ADDRESS]?.let { SavedDevice(it, prefs[KEY_NAME]) }
    }

    suspend fun savedDevice(): SavedDevice? = savedDevice.first()

    suspend fun save(address: String, name: String?) {
        store.edit { prefs ->
            prefs[KEY_ADDRESS] = address.uppercase()
            if (name != null) prefs[KEY_NAME] = name else prefs.remove(KEY_NAME)
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val KEY_ADDRESS = stringPreferencesKey("address")
        val KEY_NAME = stringPreferencesKey("name")
    }
}

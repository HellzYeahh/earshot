package dev.earshot.bt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
            // A different device may use the other channel or family: forget what was learned about the old one.
            if (!prefs[KEY_ADDRESS].equals(address, ignoreCase = true)) { prefs.remove(KEY_CHANNEL); prefs.remove(KEY_BRAGI_PROFILE); prefs.remove(KEY_FAMILY) }
            prefs[KEY_ADDRESS] = address.uppercase()
            if (name != null) prefs[KEY_NAME] = name else prefs.remove(KEY_NAME)
        }
    }

    /**
     * The RFCOMM channel that last answered for [address] (2 on the QC Ultra 2 family, 8 on the
     * QC45 / QC Headphones), so reconnects go straight to it. Null until one has worked.
     */
    suspend fun channelFor(address: String): Int? {
        val prefs = store.data.first()
        return if (prefs[KEY_ADDRESS].equals(address, ignoreCase = true)) prefs[KEY_CHANNEL] else null
    }

    suspend fun saveChannel(address: String, channel: Int) {
        store.edit { prefs ->
            if (prefs[KEY_ADDRESS].equals(address, ignoreCase = true)) prefs[KEY_CHANNEL] = channel
        }
    }

    /** The Bragi link profile ([BragiLinkProfile.id]) that last answered for [address], or null. */
    suspend fun bragiProfileFor(address: String): String? {
        val prefs = store.data.first()
        return if (prefs[KEY_ADDRESS].equals(address, ignoreCase = true)) prefs[KEY_BRAGI_PROFILE] else null
    }

    suspend fun saveBragiProfile(address: String, profileId: String) {
        store.edit { prefs ->
            if (prefs[KEY_ADDRESS].equals(address, ignoreCase = true)) prefs[KEY_BRAGI_PROFILE] = profileId
        }
    }

    /** The user's "Headphone type" choice ("bose" / "bragi"), or null for automatic detection by name. */
    val familyOverrideFlow: Flow<String?> = store.data.map { it[KEY_FAMILY] }

    suspend fun familyOverride(): String? = familyOverrideFlow.first()

    suspend fun setFamilyOverride(key: String?) {
        store.edit { prefs -> if (key == null) prefs.remove(KEY_FAMILY) else prefs[KEY_FAMILY] = key.lowercase() }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val KEY_ADDRESS = stringPreferencesKey("address")
        val KEY_NAME = stringPreferencesKey("name")
        val KEY_CHANNEL = intPreferencesKey("rfcomm_channel")
        val KEY_BRAGI_PROFILE = stringPreferencesKey("bragi_profile")
        val KEY_FAMILY = stringPreferencesKey("family_override")
    }
}

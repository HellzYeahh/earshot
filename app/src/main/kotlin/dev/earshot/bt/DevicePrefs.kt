package dev.earshot.bt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
            // A different device may use the other channel or link variant: forget what was learned about the old one.
            // The "Headphone type" setting stays with the device it was set for: a pre-0.6 one (no
            // owner recorded) is pinned to the device being replaced, so it doesn't follow the new one.
            val old = prefs[KEY_ADDRESS]
            if (!old.equals(address, ignoreCase = true)) {
                prefs.remove(KEY_CHANNEL)
                prefs.remove(KEY_BRAGI_PROFILE)
                if (old != null && prefs[KEY_FAMILY] != null && prefs[KEY_FAMILY_ADDRESS] == null) prefs[KEY_FAMILY_ADDRESS] = old
            }
            prefs[KEY_ADDRESS] = address.uppercase()
            if (name != null) prefs[KEY_NAME] = name else prefs.remove(KEY_NAME)
        }
    }

    /**
     * The BMAP channel that last answered the probe for [address] (2 on the QC Ultra 2 family, 8
     * on the QC45, 8 or 9 on the QC Headphones; an SDP lookup when no fixed channel did), so
     * reconnects try it first. Null until one has worked. Only a hint: [BoseLinkFactory] falls
     * through to the other candidates when it stops answering and stores the new winner.
     */
    suspend fun candidateFor(address: String): BmapCandidate? {
        val prefs = store.data.first()
        return if (prefs[KEY_ADDRESS].equals(address, ignoreCase = true)) BmapCandidate.fromPref(prefs[KEY_CHANNEL]) else null
    }

    suspend fun saveCandidate(address: String, candidate: BmapCandidate) {
        store.edit { prefs ->
            if (prefs[KEY_ADDRESS].equals(address, ignoreCase = true)) prefs[KEY_CHANNEL] = candidate.prefValue
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

    /**
     * The user's "Headphone type" choice ("bose" / "bragi" / "generic") for the saved device, or
     * null for automatic detection. Since 0.6 it belongs to the device it was set for: a "Bose"
     * set for a renamed QC Ultra must not make Earshot send BMAP to AirPods picked later. A
     * choice saved before 0.6 has no address and counts for the saved device.
     */
    val familyOverrideFlow: Flow<String?> = store.data.map { prefs ->
        prefs[KEY_FAMILY]?.takeIf { overrideApplies(prefs, prefs[KEY_ADDRESS]) }
    }

    /** The "Headphone type" choice for [address], or null when none was set for that device. */
    suspend fun familyOverrideFor(address: String?): String? {
        val prefs = store.data.first()
        return prefs[KEY_FAMILY]?.takeIf { overrideApplies(prefs, address) }
    }

    private fun overrideApplies(prefs: Preferences, address: String?): Boolean {
        val owner = prefs[KEY_FAMILY_ADDRESS] ?: prefs[KEY_ADDRESS]
        return address != null && owner.equals(address, ignoreCase = true)
    }

    suspend fun setFamilyOverride(key: String?) {
        store.edit { prefs ->
            if (key == null) {
                prefs.remove(KEY_FAMILY)
                prefs.remove(KEY_FAMILY_ADDRESS)
            } else {
                prefs[KEY_FAMILY] = key.lowercase()
                prefs[KEY_ADDRESS]?.let { prefs[KEY_FAMILY_ADDRESS] = it } ?: prefs.remove(KEY_FAMILY_ADDRESS)
            }
        }
    }

    /**
     * The family whose stack once answered on [address] ([rememberFamily]), or null. Devices
     * that connected before 0.6 left no such record, so a learned BMAP channel or Bragi profile
     * on the saved device counts too: an existing user's renamed Bose keeps working.
     */
    suspend fun knownFamily(address: String): DeviceFamily? {
        val prefs = store.data.first()
        val key = address.uppercase()
        prefs[KEY_KNOWN_FAMILIES].orEmpty().firstNotNullOfOrNull { entry ->
            val (a, f) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@firstNotNullOfOrNull null
            if (a == key) DeviceFamily.fromKey(f) else null
        }?.let { return it }
        if (!prefs[KEY_ADDRESS].equals(address, ignoreCase = true)) return null
        return when {
            prefs[KEY_CHANNEL] != null -> DeviceFamily.BOSE
            prefs[KEY_BRAGI_PROFILE] != null -> DeviceFamily.BRAGI
            else -> null
        }
    }

    /** Record that [family]'s stack answered on [address]. Only real sessions call this, never demo. */
    suspend fun rememberFamily(address: String, family: DeviceFamily) {
        val key = address.uppercase()
        store.edit { prefs ->
            val others = prefs[KEY_KNOWN_FAMILIES].orEmpty().filterNot { it.substringBefore('=') == key }
            // A handful of headsets at most; the cap only guards against a runaway.
            prefs[KEY_KNOWN_FAMILIES] = (others.takeLast(MAX_KNOWN_FAMILIES - 1) + "$key=${family.name}").toSet()
        }
    }

    /** The family for [address] by [DeviceFamily.resolve]: its own "Headphone type", what answered on it before, then its [name]. */
    suspend fun familyFor(address: String?, name: String?): DeviceFamily =
        DeviceFamily.resolve(familyOverrideFor(address), name, address?.let { knownFamily(it) })

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val KEY_ADDRESS = stringPreferencesKey("address")
        val KEY_NAME = stringPreferencesKey("name")
        val KEY_CHANNEL = intPreferencesKey("rfcomm_channel")
        val KEY_BRAGI_PROFILE = stringPreferencesKey("bragi_profile")
        val KEY_FAMILY = stringPreferencesKey("family_override")
        /** The device [KEY_FAMILY] was set for; absent on choices saved before 0.6. */
        val KEY_FAMILY_ADDRESS = stringPreferencesKey("family_override_address")
        /** "ADDRESS=FAMILY" for every device a stack answered on. */
        val KEY_KNOWN_FAMILIES = stringSetPreferencesKey("known_families")
        const val MAX_KNOWN_FAMILIES = 32
    }
}

package dev.earshot.bt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
// A corrupt file is replaced with an empty one (audit R24): without the handler every read throws
// CorruptionException, so every connect stopped with the storage-failed message until the app's
// data was cleared. Losing it costs only the saved headset and what was learned about it (link
// hints, "Headphone type"), which the next connect finds again.
private val Context.btDeviceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bt_device",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Tiny DataStore holding the chosen headset (address and name), so reconnects and the manifest
 * receiver can recognise the right device without heuristics, plus what Earshot learned about it:
 * each stack's link hint ([linkHint]), the "Headphone type" choice and the families that answered.
 *
 * One instance is shared by every stack (`AppContainer.devicePrefs`); the store behind it is the
 * process-wide "bt_device" DataStore either way.
 *
 * Persisted names that must survive refactors and the module split: the key names below, the
 * lowercase "Headphone type" values in `family_override` (matched by [DeviceFamily.fromKey]) and
 * the [DeviceFamily] enum names stored in `known_families` and in the `hint_<family>` keys.
 * Renaming one orphans users' data.
 */
class DevicePrefs internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.btDeviceDataStore)

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
                prefs.asMap().keys.filter { it.name.startsWith(HINT_PREFIX) }.forEach { prefs.remove(stringPreferencesKey(it.name)) }
                if (old != null && prefs[KEY_FAMILY] != null && prefs[KEY_FAMILY_ADDRESS] == null) prefs[KEY_FAMILY_ADDRESS] = old
            }
            prefs[KEY_ADDRESS] = address.uppercase()
            if (name != null) prefs[KEY_NAME] = name else prefs.remove(KEY_NAME)
        }
    }

    /**
     * What [family]'s link factory learned about reaching [address] (a channel, a profile id),
     * so reconnects try it first; null until one has worked. Only for the saved device: like the
     * legacy hints, a hint is forgotten when [save] picks another one.
     *
     * Storage: BOSE and BRAGI keep the keys they always had (`rfcomm_channel` as an Int,
     * `bragi_profile`), so nothing is migrated and a downgrade still finds them; every other family
     * gets `hint_<family>` (lowercase enum name).
     */
    suspend fun linkHint(family: DeviceFamily, address: String): String? {
        val prefs = store.data.first()
        if (!prefs[KEY_ADDRESS].equals(address, ignoreCase = true)) return null
        return when (family) {
            DeviceFamily.BOSE -> prefs[KEY_CHANNEL]?.toString()
            DeviceFamily.BRAGI -> prefs[KEY_BRAGI_PROFILE]
            else -> prefs[hintKey(family)]
        }
    }

    /** Store [value] as [family]'s hint for [address], when that is the saved device. BOSE takes an Int (as text). */
    suspend fun saveLinkHint(family: DeviceFamily, address: String, value: String) {
        val channel = if (family == DeviceFamily.BOSE) requireNotNull(value.toIntOrNull()) { "the Bose hint is a channel code" } else null
        store.edit { prefs ->
            if (!prefs[KEY_ADDRESS].equals(address, ignoreCase = true)) return@edit
            when (family) {
                DeviceFamily.BOSE -> prefs[KEY_CHANNEL] = channel!!
                DeviceFamily.BRAGI -> prefs[KEY_BRAGI_PROFILE] = value
                else -> prefs[hintKey(family)] = value
            }
        }
    }

    /**
     * The BMAP channel that last answered the probe for [address] (2 on the QC Ultra 2 family, 8
     * on the QC45, 8 or 9 on the QC Headphones; an SDP lookup when no fixed channel did), so
     * reconnects try it first. Null until one has worked. Only a hint: [BoseLinkFactory] falls
     * through to the other candidates when it stops answering and stores the new winner.
     */
    suspend fun candidateFor(address: String): BmapCandidate? =
        BmapCandidate.fromPref(linkHint(DeviceFamily.BOSE, address)?.toInt())

    suspend fun saveCandidate(address: String, candidate: BmapCandidate) =
        saveLinkHint(DeviceFamily.BOSE, address, candidate.prefValue.toString())

    /** The Bragi link profile ([BragiLinkProfile.id]) that last answered for [address], or null. */
    suspend fun bragiProfileFor(address: String): String? = linkHint(DeviceFamily.BRAGI, address)

    suspend fun saveBragiProfile(address: String, profileId: String) = saveLinkHint(DeviceFamily.BRAGI, address, profileId)

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

        /** Link hints of the families without a legacy key; see [linkHint]. */
        const val HINT_PREFIX = "hint_"
        fun hintKey(family: DeviceFamily) = stringPreferencesKey(HINT_PREFIX + family.name.lowercase())
    }
}

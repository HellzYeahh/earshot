package dev.earshot.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import okio.Buffer
import java.io.File

/**
 * DataStore Preferences files read and written outside a DataStore, with the library's own
 * public `PreferencesSerializer` (datastore-preferences-core 1.1.7, the format DataStore itself
 * writes to `files/datastore/<name>.preferences_pb`). Decoding and re-encoding keeps every key's
 * type, which a byte-level edit of the protobuf could not promise. Its stream type is Okio's,
 * which datastore-preferences-core exposes as an API dependency (no dependency added here; the
 * `java.io` twin, `PreferencesFileSerializer`, is Kotlin-internal in 1.1.7).
 *
 * Only ever used on files no DataStore has open: a copy for export (DataStore replaces its file
 * by rename, so a read sees one whole version) and the restore at start, before any store opens.
 */
internal object PreferencesFiles {
    /** Parses [bytes]; a file that isn't a Preferences protobuf throws (DataStore's `CorruptionException`). */
    fun decode(bytes: ByteArray): Preferences = runBlocking { PreferencesSerializer.readFrom(Buffer().write(bytes)) }

    fun encode(prefs: Preferences): ByteArray {
        val buffer = Buffer()
        runBlocking { PreferencesSerializer.writeTo(prefs, buffer) }
        return buffer.readByteArray()
    }

    /** The file's preferences, or null when it doesn't exist. */
    fun read(file: File): Preferences? = if (file.isFile) decode(file.readBytes()) else null

    /** [prefs] without the keys named in [names]. */
    fun without(prefs: Preferences, names: Set<String>): Preferences {
        if (names.isEmpty()) return prefs
        val out = prefs.toMutablePreferences()
        prefs.asMap().keys.filter { it.name in names }.forEach { out.remove(it) }
        return out.toPreferences()
    }

    /** [base] with the keys named in [names] taken from [source] (typed as they were there); absent there → absent here. */
    fun withKeysFrom(base: Preferences, source: Preferences?, names: Set<String>): Preferences {
        if (names.isEmpty()) return base
        val out = without(base, names).toMutablePreferences()
        source?.asMap()?.forEach { (key, value) -> if (key.name in names) out.setUntyped(key, value) }
        return out.toPreferences()
    }

    fun empty(): Preferences = emptyPreferences()

    /**
     * [prefs] with only the keys [typeOf] knows, each holding a value of that type. DataStore's
     * typed getters cast without checking, so a `rules_json` stored as an Int would throw a
     * ClassCastException in the repository at every start; such a key never reaches a live file.
     */
    fun typedOnly(prefs: Preferences, typeOf: (String) -> PrefType?): Preferences {
        val out = prefs.toMutablePreferences()
        prefs.asMap().forEach { (key, value) ->
            val type = typeOf(key.name)
            if (type == null || !type.matches(value)) out.remove(key)
        }
        return out.toPreferences()
    }

    /** The names of keys [typeOf] knows but whose value in [prefs] has another type. */
    fun mistyped(prefs: Preferences, typeOf: (String) -> PrefType?): Set<String> =
        prefs.asMap().filter { (key, value) -> typeOf(key.name)?.matches(value) == false }.keys.map { it.name }.toSet()

    /** The String stored under [name], or null (absent or another type). */
    fun string(prefs: Preferences?, name: String): String? =
        prefs?.asMap()?.entries?.firstOrNull { it.key.name == name }?.value as? String

    /** [prefs] with [name] set to [value] as a String, or removed when [value] is null. */
    fun withString(prefs: Preferences, name: String, value: String?): Preferences {
        val out = without(prefs, setOf(name)).toMutablePreferences()
        if (value != null) out[stringPreferencesKey(name)] = value
        return out.toPreferences()
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.setUntyped(key: Preferences.Key<*>, value: Any) {
        this[key as Preferences.Key<Any>] = value
    }
}

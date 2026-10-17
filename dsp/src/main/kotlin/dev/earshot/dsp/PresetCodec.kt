package dev.earshot.dsp

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Serialisable twin of the app's `AppSoundProfile`. Lives here so the codec has no Android
 * dependency; the repository maps between the two (same three fields).
 */
@Serializable
data class AppProfileRecord(val packageName: String, val appLabel: String, val presetId: String)

/**
 * JSON persistence for everything the sound engine stores. One `Json` configuration so that
 * fields added later (with defaults) stay backward-compatible and older files keep loading:
 * `ignoreUnknownKeys` tolerates newer writers, `encodeDefaults` makes files self-describing.
 */
object PresetCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        prettyPrint = false
    }

    private val presetList = ListSerializer(EqPreset.serializer())
    private val profileList = ListSerializer(AppProfileRecord.serializer())

    fun encodePresets(presets: List<EqPreset>): String = json.encodeToString(presetList, presets)
    fun decodePresets(text: String): List<EqPreset> = json.decodeFromString(presetList, text)

    fun encodeSettings(settings: TuningSettings): String = json.encodeToString(TuningSettings.serializer(), settings)
    fun decodeSettings(text: String): TuningSettings = json.decodeFromString(TuningSettings.serializer(), text)

    fun encodeAppProfiles(profiles: List<AppProfileRecord>): String = json.encodeToString(profileList, profiles)
    fun decodeAppProfiles(text: String): List<AppProfileRecord> = json.decodeFromString(profileList, text)

    /** Non-throwing variants for restoring persisted state: a corrupt blob falls back to [default]. */
    fun decodePresetsOrNull(text: String?): List<EqPreset>? = text?.let { runCatching { decodePresets(it) }.getOrNull() }
    fun decodeSettingsOrNull(text: String?): TuningSettings? = text?.let { runCatching { decodeSettings(it) }.getOrNull() }
    fun decodeAppProfilesOrNull(text: String?): List<AppProfileRecord>? = text?.let { runCatching { decodeAppProfiles(it) }.getOrNull() }
}

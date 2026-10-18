package dev.earshot.shortcut

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a headset's press-and-hold (HFP voice recognition, delivered to us as `VOICE_COMMAND`) does.
 *
 * A sealed class with per-action parameters rather than an enum, so later actions with their own
 * settings (switch to listening mode N, play/pause) slot in as one more subclass. The serial names
 * are the stored format: never rename one, only add new ones. Unknown names decode to null and the
 * caller falls back to the default (see [ShortcutCodec]).
 */
@Serializable
sealed interface ShortcutAction {
    /** Step to the next Pro EQ preset (turning the Pro EQ on when it is off). */
    @Serializable
    @SerialName("cycle_eq")
    data object CycleEq : ShortcutAction

    /** Press to start a voice memo, press again to stop and save it. */
    @Serializable
    @SerialName("voice_memo")
    data object VoiceMemo : ShortcutAction

    /** Open an app. [packageName] null = not chosen yet (the trigger then tells the user). */
    @Serializable
    @SerialName("launch_app")
    data class LaunchApp(val packageName: String? = null, val label: String? = null) : ShortcutAction

    /**
     * Forward to a real assistant. [component] is a flattened `ComponentName` the user picked
     * ("pkg/cls"); null = automatic (the assistant role holder, else the first other handler).
     */
    @Serializable
    @SerialName("open_assistant")
    data class OpenAssistant(val component: String? = null, val label: String? = null) : ShortcutAction
}

/** A per-headset choice, keyed by Bluetooth address; [name] is only for display. */
@Serializable
data class HeadsetOverride(val address: String, val name: String? = null, val action: ShortcutAction)

data class ShortcutSettings(
    /**
     * "Open my assistant" by default: picking Earshot as the gesture's handler must not take the
     * assistant away until the user chooses something else.
     */
    val defaultAction: ShortcutAction = ShortcutAction.OpenAssistant(),
    val overrides: List<HeadsetOverride> = emptyList(),
    val spokenFeedback: Boolean = true,
) {
    fun overrideFor(address: String?): HeadsetOverride? =
        address?.let { a -> overrides.firstOrNull { it.address.equals(a, ignoreCase = true) } }

    /** The action for the headset at [address] (null = unknown headset): its override, else the default. */
    fun actionFor(address: String?): ShortcutAction = overrideFor(address)?.action ?: defaultAction

    /** Sets ([action] non-null) or removes ([action] null = "Use default") the override for [address]. */
    fun withOverride(address: String, name: String?, action: ShortcutAction?): ShortcutSettings {
        val rest = overrides.filterNot { it.address.equals(address, ignoreCase = true) }
        return copy(overrides = if (action == null) rest else rest + HeadsetOverride(address.uppercase(), name, action))
    }
}

/** JSON for the DataStore strings. Pure, so the stored format is unit-tested. */
object ShortcutCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "type" }

    fun encodeAction(action: ShortcutAction): String = json.encodeToString(ShortcutAction.serializer(), action)

    /** Null for missing, corrupt or unknown (newer) actions. */
    fun decodeAction(text: String?): ShortcutAction? =
        text?.let { runCatching { json.decodeFromString(ShortcutAction.serializer(), it) }.getOrNull() }

    fun encodeOverrides(list: List<HeadsetOverride>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HeadsetOverride.serializer()), list)

    /**
     * Decodes element by element, so one override with an action this version doesn't know (stored
     * by a newer build) is dropped instead of losing all of them.
     */
    fun decodeOverrides(text: String?): List<HeadsetOverride> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(HeadsetOverride.serializer(), element) }.getOrNull()
        }.distinctBy { it.address.uppercase() }
    }
}

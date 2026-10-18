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

    /**
     * Not an Earshot action: the Bose headphones' own Shortcut button mode (`[1.9]` action code,
     * e.g. 19 Immersive Audio, 14 Disabled). Earshot writes it to the headphones and steps aside;
     * the button then sends no voice request. Only stored as a per-headset choice. If a press does
     * reach Earshot anyway (another firmware, the write failed), it is passed to the assistant.
     */
    @Serializable
    @SerialName("headphone_button")
    data class HeadphoneButton(val mode: Int) : ShortcutAction
}

/** True for the actions Earshot runs itself (they need the headset's button on "Voice assistant"). */
val ShortcutAction.isEarshotAction: Boolean get() = this !is ShortcutAction.HeadphoneButton

/** A per-headset choice, keyed by Bluetooth address; [name] is only for display. */
@Serializable
data class HeadsetOverride(val address: String, val name: String? = null, val action: ShortcutAction)

data class ShortcutSettings(
    /**
     * "Open my assistant" by default: picking Earshot as the gesture's handler must not take the
     * assistant away until the user chooses something else.
     */
    val defaultAction: ShortcutAction = FACTORY_DEFAULT,
    val overrides: List<HeadsetOverride> = emptyList(),
    val spokenFeedback: Boolean = true,
    /**
     * Earshot's trigger has run for a real headset press at least once (started by the Bluetooth
     * stack or Android's chooser, not by Earshot). Until then the remapper shows its one-line
     * "pick Earshot, then Always" hint.
     */
    val seenHeadsetPress: Boolean = false,
) {
    /** The user picked something for the headset at [address] (or changed the default) rather than leaving the factory default. */
    fun hasExplicitChoice(address: String?): Boolean = overrideFor(address) != null || defaultAction != FACTORY_DEFAULT

    /**
     * Saves the remapper's choice: for the headset shown at the top ([address]), or the default
     * when nothing is connected ([address] null).
     */
    fun withChoice(address: String?, name: String?, action: ShortcutAction): ShortcutSettings =
        if (address == null) copy(defaultAction = action) else withOverride(address, name, action)

    fun overrideFor(address: String?): HeadsetOverride? =
        address?.let { a -> overrides.firstOrNull { it.address.equals(a, ignoreCase = true) } }

    /** The action for the headset at [address] (null = unknown headset): its override, else the default. */
    fun actionFor(address: String?): ShortcutAction = overrideFor(address)?.action ?: defaultAction

    /** Sets ([action] non-null) or removes ([action] null = "Use default") the override for [address]. */
    fun withOverride(address: String, name: String?, action: ShortcutAction?): ShortcutSettings {
        val rest = overrides.filterNot { it.address.equals(address, ignoreCase = true) }
        return copy(overrides = if (action == null) rest else rest + HeadsetOverride(address.uppercase(), name, action))
    }

    companion object {
        val FACTORY_DEFAULT: ShortcutAction = ShortcutAction.OpenAssistant()
    }
}

/** JSON for the DataStore strings. Pure, so the stored format is unit-tested. */
object ShortcutCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "type" }

    fun encodeAction(action: ShortcutAction): String = json.encodeToString(ShortcutAction.serializer(), action)

    /**
     * The DataStore's raw values to settings. Missing keys take the defaults, so settings stored
     * by an older build (the first remapper build had no [ShortcutSettings.seenHeadsetPress]) keep working.
     */
    fun decodeSettings(defaultAction: String?, overrides: String?, spokenFeedback: Boolean?, seenHeadsetPress: Boolean?): ShortcutSettings {
        val defaults = ShortcutSettings()
        return ShortcutSettings(
            defaultAction = decodeAction(defaultAction)
                // A headphone mode belongs to one headset; as the default it would be meaningless.
                ?.takeIf { it.isEarshotAction } ?: defaults.defaultAction,
            overrides = decodeOverrides(overrides),
            spokenFeedback = spokenFeedback ?: defaults.spokenFeedback,
            seenHeadsetPress = seenHeadsetPress ?: defaults.seenHeadsetPress,
        )
    }

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

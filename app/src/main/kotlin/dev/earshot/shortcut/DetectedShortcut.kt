package dev.earshot.shortcut

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** Which physical control the user says sent the assistant gesture (asked after a detection). */
@Serializable
enum class PhysicalControl {
    @SerialName("power") POWER_BUTTON,
    @SerialName("play_pause") PLAY_PAUSE_BUTTON,
    @SerialName("volume_strip") VOLUME_STRIP,
    @SerialName("other") OTHER,
}

/** How it was pressed. */
@Serializable
enum class PressStyle {
    @SerialName("single") SINGLE_PRESS,
    @SerialName("double") DOUBLE_PRESS,
    @SerialName("long") LONG_PRESS,
    @SerialName("touch_hold") TOUCH_AND_HOLD,
}

/**
 * A headset whose assistant gesture reached Earshot during "Detect a button", and what the user
 * called the control. Kept per headset because the QC Ultra 2 taught us not to guess: Bose
 * documents the Shortcut as a strip hold, the owner's unit fired on a power-button press.
 */
@Serializable
data class DetectedShortcut(
    val address: String,
    val at: Long,
    val control: PhysicalControl? = null,
    val press: PressStyle? = null,
    /** The user's own words when [control] is [PhysicalControl.OTHER]. */
    val customName: String? = null,
)

/**
 * The mode the user picked for a Bose `[1.9]` record other than the primary one (earbuds: the
 * second bud). [mode] is a `ButtonAction` wire value; Voice assistant (1) means "same Earshot
 * action as Shortcut Button 1", since the phone can't tell two Vpa buttons apart.
 */
@Serializable
data class ButtonChoice(val address: String, val buttonId: Int, val mode: Int)

/** JSON for the two lists; element by element, so one bad entry drops alone. */
internal object ShortcutExtrasCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encodeDetected(list: List<DetectedShortcut>): String = json.encodeToString(ListSerializer(DetectedShortcut.serializer()), list)

    fun decodeDetected(text: String?): List<DetectedShortcut> =
        decodeEach(text) { json.decodeFromJsonElement(DetectedShortcut.serializer(), it) }.distinctBy { it.address.uppercase() }

    fun encodeButtonChoices(list: List<ButtonChoice>): String = json.encodeToString(ListSerializer(ButtonChoice.serializer()), list)

    fun decodeButtonChoices(text: String?): List<ButtonChoice> =
        decodeEach(text) { json.decodeFromJsonElement(ButtonChoice.serializer(), it) }.distinctBy { it.address.uppercase() to it.buttonId }

    private fun <T> decodeEach(text: String?, one: (kotlinx.serialization.json.JsonElement) -> T): List<T> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { runCatching { one(it) }.getOrNull() }
    }
}

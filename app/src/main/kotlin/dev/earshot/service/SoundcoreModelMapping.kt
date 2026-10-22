package dev.earshot.service

import dev.earshot.core.BatteryDetail
import dev.earshot.core.BatteryLevel
import dev.earshot.core.DeviceInfo
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCapabilities
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.VendorControl
import dev.earshot.protocol.soundcore.AmbientMode
import dev.earshot.protocol.soundcore.SoundcoreModel
import dev.earshot.protocol.soundcore.SoundcoreSetting
import dev.earshot.protocol.soundcore.SoundcoreState
import dev.earshot.protocol.soundcore.SoundcoreWrites

/**
 * [SoundcoreState] (every Soundcore model except the Sleep A30, which keeps [SoundcoreStateMapping])
 * → [HeadphoneState]. Pure, unit-tested. The three ambient modes are the listening modes, slot 0
 * noise cancelling, 1 transparency, 2 normal, so Home's carousel, the tile, the widget and the
 * automations work unchanged (slot 0 is the quiet mode everywhere, as on the Crusher). The rest of
 * what the model has goes to [VendorControl]s: noise-cancelling type and level, transparency type,
 * wind, and the single-byte settings its table documents.
 *
 * Per the user's rule that tools never hide settings, a model whose layout is known shows its
 * controls even when the state did not match (the values are blank, a change is refused with a
 * message). A model with no layout shows battery and firmware and says so.
 */
object SoundcoreModelMapping {
    const val MODE_NC = 0
    const val MODE_TRANSPARENCY = 1
    const val MODE_NORMAL = 2

    val MODES = listOf(
        ListeningMode(MODE_NC, "Noise cancelling", isPreset = true, isEditable = false, isConfigured = true),
        ListeningMode(MODE_TRANSPARENCY, "Transparency", isPreset = true, isEditable = false, isConfigured = true),
        ListeningMode(MODE_NORMAL, "Normal", isPreset = true, isEditable = false, isConfigured = true),
    )

    const val EXPERIMENTAL = "Experimental: built from protocol notes and not yet tested on this model."

    fun modeIndex(mode: AmbientMode): Int = mode.wire

    fun ambientFor(index: Int): AmbientMode? = AmbientMode.fromWire(index)

    /** The text for an auto power-off option: minutes, or hours from 60. */
    fun durationLabel(minutes: Int): String = when {
        minutes == 60 -> "1 hour"
        minutes > 60 && minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> "$minutes min"
    }

    fun controls(s: SoundcoreState): List<VendorControl> {
        val layout = s.model.layout ?: return emptyList()
        if (!layout.writable) return emptyList()
        val codec = s.modes?.codec ?: layout.codec
        val modes = s.modes
        val out = ArrayList<VendorControl>()
        if (codec.ncTypes.isNotEmpty()) {
            out += VendorControl(
                SoundcoreWrites.NC_TYPE, "Noise cancelling type", "Changes while noise cancelling is the active mode",
                value = modes?.ncType, options = codec.ncTypes,
            )
        }
        codec.manualLevels?.let { range ->
            out += VendorControl(
                SoundcoreWrites.NC_LEVEL, "Manual noise cancelling level", "Applies to the Manual type; ${range.first} is the lightest",
                value = modes?.manualLevel?.let { it - range.first }, options = range.map { it.toString() },
            )
        }
        if (codec.transparencyTypes.isNotEmpty()) {
            out += VendorControl(
                SoundcoreWrites.TRANSPARENCY_TYPE, "Transparency type", "Changes while transparency is the active mode",
                value = modes?.transparencyType, options = codec.transparencyTypes,
            )
        }
        if (codec.hasWind) {
            out += VendorControl(
                SoundcoreWrites.WIND, "Wind noise reduction", "With noise cancelling or transparency",
                value = modes?.windOn?.let { if (it) 1 else 0 },
            )
        }
        for (setting in layout.settings) out += settingControl(s, setting, layout.autoOffMinutes)
        return out
    }

    private fun settingControl(s: SoundcoreState, setting: SoundcoreSetting, autoOffMinutes: List<Int>): VendorControl =
        VendorControl(
            setting.id, setting.title, setting.subtitle,
            value = s.settings[setting.id],
            options = if (setting.autoOff) listOf("Off") + autoOffMinutes.map(::durationLabel) else null,
        )

    fun capabilities(s: SoundcoreState): HeadphoneCapabilities = HeadphoneCapabilities(
        spatialAudio = false,
        ancToggle = false,
        liveNoiseControl = false,
        // The modes carry it; the level and type are vendor controls, not the Bose noise slider.
        noiseCancelling = false,
        windBlock = false,
        editableModes = false,
        favoriteModes = false,
        listeningModes = s.ambient != null,
        modesOnHome = true,
        verified = false,
        formFactor = if (s.model.earbuds) FormFactor.EARBUDS else FormFactor.HEADPHONES,
        supportNote = if (s.writable) EXPERIMENTAL else "$EXPERIMENTAL ${s.note.orEmpty()}".trim(),
    )

    fun apply(state: HeadphoneState, s: SoundcoreState, now: Long = System.currentTimeMillis()): HeadphoneState =
        withBattery(state, s).copy(
            isCharging = s.charging,
            modes = if (s.ambient != null) MODES else emptyList(),
            currentModeIndex = s.ambient?.let(::modeIndex),
            vendorControls = controls(s),
            info = DeviceInfo(
                productName = s.model.displayName,
                modelName = s.model.displayName,
                firmwareVersion = s.firmware,
                serialNumber = s.serial,
            ),
            capabilities = capabilities(s),
            lastUpdatedMillis = now,
        )

    /** Per-part battery; the headline is the lower bud (headphones: their one battery). */
    fun withBattery(state: HeadphoneState, s: SoundcoreState): HeadphoneState = state.copy(
        batteryPercent = s.headlinePercent,
        battery = BatteryDetail(
            overall = s.overallPercent?.let { BatteryLevel(it) },
            left = s.leftPercent?.let { BatteryLevel(it) },
            right = s.rightPercent?.let { BatteryLevel(it) },
            case = s.casePercent?.let { BatteryLevel(it) },
        ),
    )

    /** The state for a headset that answered `01 01` with something no layout fits: name and a note, nothing else. */
    fun unreadable(state: HeadphoneState, model: SoundcoreModel, size: Int, now: Long = System.currentTimeMillis()): HeadphoneState = state.copy(
        modes = emptyList(),
        currentModeIndex = null,
        vendorControls = emptyList(),
        info = DeviceInfo(productName = model.displayName, modelName = model.displayName),
        capabilities = HeadphoneCapabilities(
            spatialAudio = false, ancToggle = false, liveNoiseControl = false, noiseCancelling = false, windBlock = false,
            editableModes = false, favoriteModes = false, listeningModes = false, modesOnHome = true, verified = false,
            formFactor = if (model.earbuds) FormFactor.EARBUDS else FormFactor.HEADPHONES,
            supportNote = "$EXPERIMENTAL The ${model.displayName.removePrefix("Soundcore ")} connected, but its state ($size bytes) isn't a layout Earshot recognises, so nothing is shown or changed.",
        ),
        lastUpdatedMillis = now,
    )
}

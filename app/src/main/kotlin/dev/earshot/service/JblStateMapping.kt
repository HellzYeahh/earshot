package dev.earshot.service

import dev.earshot.core.BatteryDetail
import dev.earshot.core.BatteryLevel
import dev.earshot.core.DeviceInfo
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCapabilities
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeMeaning
import dev.earshot.core.VendorControl
import dev.earshot.protocol.jbl.JblBattery
import dev.earshot.protocol.jbl.JblCommands as C
import dev.earshot.protocol.jbl.JblFrame
import dev.earshot.protocol.jbl.JblModel
import dev.earshot.protocol.jbl.JblStatus

/**
 * What a session reads from an H-AA headset one probe at a time. H-AA has no "features" list and a
 * model that lacks a feature simply stays silent, so a feature is shown and written only once it
 * has been seen ([JblSession.answered]).
 */
enum class JblFeature { MODES, LEVEL, TLV, EQ, VOICE_PROMPTS, TWS_BATTERY }

/**
 * [JblStatus] → [HeadphoneState]. Pure, unit-tested.
 *
 * JBL has three listening modes plus Off, and they sit in fixed Earshot slots so Home's carousel,
 * the tile, the widget and automations work unchanged: 0 "Noise cancelling" (meaning QUIET),
 * 1 "Ambient Aware" (AWARE), 2 "Off" (OFF), 3 "TalkThru" (CUSTOM: it has no meaning of its own, so
 * rules match it by name). The slots stay put whatever the headset lists, which keeps the numbering
 * from 0; a mode the headset didn't list is an empty slot ([ListeningMode.isConfigured] false, not
 * a preset, so the carousel leaves it out and nothing can pick it). Off exists whenever any mode
 * does: it is "all pairs off" in `0x91 10`.
 *
 * The rest is [VendorControl]s: the manual noise-cancelling level, EQ preset and voice prompts, each
 * only once its read was answered. The headset's own 10-band EQ is not the app's three-band one, and
 * the model has no live noise card, so [HeadphoneState.noise] and [HeadphoneState.eq] stay null.
 */
object JblStateMapping {
    const val MODE_ANC = 0
    const val MODE_AMBIENT = 1
    const val MODE_OFF = 2
    const val MODE_TALKTHRU = 3

    // VendorControl ids.
    const val ANC_LEVEL = "anc_level"
    const val EQ_PRESET = "eq_preset"
    const val VOICE_PROMPTS = "voice_prompts"

    const val SUPPORT_NOTE =
        "Experimental: Earshot's first JBL driver, built from protocol notes and captures from jbl-control (MIT) and not yet " +
            "tried on our own headphones. Listening modes, the noise-cancelling level, EQ presets and battery."
    const val SILENT_NOTE =
        "Experimental JBL driver: these headphones didn't report their listening modes or settings, so Earshot shows only what they did report."

    /** One Earshot slot and the `0x91` mode number behind it ([wire] null = Off, all pairs 0). */
    class Slot(val index: Int, val name: String, val wire: Int?, val meaning: ModeMeaning)

    // Explicit meanings: saved rules and the tile resolve by them, not by these English names.
    val SLOTS: List<Slot> = listOf(
        Slot(MODE_ANC, "Noise cancelling", C.WIRE_ANC, ModeMeaning.QUIET),
        Slot(MODE_AMBIENT, "Ambient Aware", C.WIRE_AMBIENT, ModeMeaning.AWARE),
        Slot(MODE_OFF, "Off", null, ModeMeaning.OFF),
        Slot(MODE_TALKTHRU, "TalkThru", C.WIRE_TALKTHRU, ModeMeaning.CUSTOM),
    )

    fun slot(index: Int): Slot? = SLOTS.firstOrNull { it.index == index }

    /** The slot for the mode the headset reports as on ([JblStatus.activeMode]: 0 = Off, else a wire number). */
    fun slotOfActive(active: Int?): Slot? = active?.let { a -> SLOTS.firstOrNull { (it.wire ?: 0) == a } }

    /** What the headset has told us, whether or not a probe was the one that asked (a push counts). */
    fun reported(s: JblStatus): Set<JblFeature> = buildSet {
        if (s.modes != null) add(JblFeature.MODES)
        if (s.ancLevel != null) add(JblFeature.LEVEL)
        if (s.infoIds.isNotEmpty()) add(JblFeature.TLV)
        if (s.eqCategory != null) add(JblFeature.EQ)
        if (s.voicePrompts != null) add(JblFeature.VOICE_PROMPTS)
        if (s.budPair != null) add(JblFeature.TWS_BATTERY)
    }

    /** Earbuds when a bud reported a battery or the table says so; the table may leave it open (null). */
    fun isEarbuds(s: JblStatus, model: JblModel?): Boolean = s.reportsBuds || s.budPair != null || model?.earbuds == true

    // ---- Modes ------------------------------------------------------------------------------

    fun modes(s: JblStatus): List<ListeningMode> {
        val listed = s.modes?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return SLOTS.map { slot ->
            val available = slot.wire == null || slot.wire in listed
            ListeningMode(slot.index, slot.name, isPreset = available, isEditable = false, isConfigured = available, meaning = slot.meaning)
        }
    }

    // ---- Settings -----------------------------------------------------------------------------

    /** Everything this headset has told us it can change, in the order a screen should show it. */
    fun controls(s: JblStatus, answered: Set<JblFeature>): List<VendorControl> = buildList {
        if (JblFeature.LEVEL in answered) {
            add(
                VendorControl(
                    ANC_LEVEL, "Noise-cancelling level", "1 to 7; Earshot hasn't confirmed which end cancels more",
                    value = s.ancLevel?.coerceIn(C.ANC_LEVELS), onHome = true, range = C.ANC_LEVELS,
                ),
            )
        }
        if (JblFeature.EQ in answered) {
            val preset = s.eqCategory?.let { category -> C.EQ_PRESETS.indexOfFirst { it.category == category } }?.takeIf { it >= 0 }
            add(
                VendorControl(
                    EQ_PRESET, "EQ preset",
                    // A curve set in JBL's app (category 230) or one this table lacks reads as no choice: the write only ever sends a preset.
                    subtitle = if (preset == null) "A custom EQ set elsewhere is kept until you pick a preset" else null,
                    value = preset, options = C.EQ_PRESETS.map { it.label },
                ),
            )
        }
        if (JblFeature.VOICE_PROMPTS in answered) {
            add(VendorControl(VOICE_PROMPTS, "Voice prompts", "Spoken status messages from the headphones", value = s.voicePrompts?.let { if (it) 1 else 0 }))
        }
    }

    /** The frame that sets [id] to [value], or null for an unknown id or a value out of range. Whether the headset has the setting is [controls]'s business. */
    fun write(id: String, value: Int): JblFrame? = when (id) {
        ANC_LEVEL -> if (value in C.ANC_LEVELS) C.ancLevelSet(value) else null
        EQ_PRESET -> C.EQ_PRESETS.getOrNull(value)?.let { C.eqSet(it.category, it.gainsDb) }
        VOICE_PROMPTS -> if (value in 0..1) C.voicePromptsSet(value == 1) else null
        else -> null
    }

    // ---- Whole state ----------------------------------------------------------------------------

    fun capabilities(s: JblStatus, model: JblModel?, answered: Set<JblFeature>): HeadphoneCapabilities = HeadphoneCapabilities(
        spatialAudio = false,
        ancToggle = false,
        liveNoiseControl = true,
        noiseCancelling = false,
        windBlock = false,
        editableModes = false,
        favoriteModes = false,
        listeningModes = !s.modes.isNullOrEmpty(),
        modesOnHome = true,
        verified = false,
        formFactor = if (isEarbuds(s, model)) FormFactor.EARBUDS else FormFactor.HEADPHONES,
        supportNote = if (answered.isEmpty()) SILENT_NOTE else SUPPORT_NOTE,
    )

    fun info(s: JblStatus, model: JblModel?): DeviceInfo {
        // The table's name when the product id is known: the device-info name is the Bluetooth name, which the user may have changed.
        val name = model?.fullName ?: s.info?.name
        return DeviceInfo(productName = name, modelName = name, firmwareVersion = s.firmware, productId = s.info?.productId)
    }

    fun apply(
        state: HeadphoneState,
        s: JblStatus,
        model: JblModel?,
        answered: Set<JblFeature>,
        now: Long = System.currentTimeMillis(),
    ): HeadphoneState {
        val modes = modes(s)
        return withBattery(state, s, model).copy(
            modes = modes,
            currentModeIndex = if (modes.isEmpty()) null else slotOfActive(s.activeMode)?.index,
            vendorControls = controls(s, answered),
            info = info(s, model),
            capabilities = capabilities(s, model, answered),
            lastUpdatedMillis = now,
        )
    }

    private fun level(b: JblBattery?): BatteryLevel? = b?.let { BatteryLevel(it.percent) }

    /**
     * Earbuds: left, right and case; the headline is the lower known bud and charging means any bud
     * is. The older `0x25` read names its buds primary and secondary, not left and right, so it
     * gives one figure (the lower). A bud in its case reads unknown (0xFF): then the device-info
     * byte is the best figure there is. Headphones: the headband battery, else the device-info byte.
     */
    fun withBattery(state: HeadphoneState, s: JblStatus, model: JblModel?): HeadphoneState {
        if (!isEarbuds(s, model)) {
            val b = s.headband ?: s.info?.battery ?: return state
            return state.copy(batteryPercent = b.percent, battery = BatteryDetail(overall = BatteryLevel(b.percent)), isCharging = b.charging)
        }
        val buds = listOfNotNull(s.left, s.right)
        val pair = s.budPair?.toList().orEmpty()
        if (buds.isEmpty() && pair.isEmpty() && s.case == null) {
            val b = s.info?.battery ?: return state
            return state.copy(batteryPercent = b.percent, isCharging = b.charging)
        }
        val known = (buds.ifEmpty { pair }).mapNotNull { it.percent }
        val headline = known.minOrNull() ?: s.info?.battery?.percent
        val detail = if (buds.isNotEmpty()) {
            BatteryDetail(left = level(s.left), right = level(s.right), case = level(s.case))
        } else {
            BatteryDetail(overall = known.minOrNull()?.let { BatteryLevel(it) }, case = level(s.case))
        }
        val charging = (buds + pair).takeIf { it.isNotEmpty() }?.any { it.charging }
        return state.copy(batteryPercent = headline, battery = detail, isCharging = charging ?: state.isCharging)
    }
}

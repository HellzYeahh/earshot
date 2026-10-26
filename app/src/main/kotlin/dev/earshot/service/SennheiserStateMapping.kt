package dev.earshot.service

import dev.earshot.core.BatteryDetail
import dev.earshot.core.BatteryLevel
import dev.earshot.core.DeviceInfo
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCapabilities
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeMeaning
import dev.earshot.core.NoiseSettings
import dev.earshot.core.VendorControl
import dev.earshot.protocol.sennheiser.SennheiserCommands
import dev.earshot.protocol.sennheiser.SennheiserFeature
import dev.earshot.protocol.sennheiser.SennheiserModel
import dev.earshot.protocol.sennheiser.SennheiserNoiseFamily
import dev.earshot.protocol.sennheiser.SennheiserSetting
import dev.earshot.protocol.sennheiser.SennheiserStatus
import dev.earshot.protocol.sennheiser.SennheiserWire

/**
 * [SennheiserStatus] → [HeadphoneState]. Pure, unit-tested.
 *
 * Every family gets three stand-in modes, slot 0 "Noise cancelling", 1 "Transparency", 2 "Off",
 * so Home's carousel, the tile, the widget, the notification and automations work unchanged (slot 0
 * is the quiet mode and slot 1 the aware one everywhere). The headband slider family (Momentum 4,
 * Accentum Plus, Momentum 5) also gets the noise card: its 0..100 ANC↔transparency level is
 * Earshot's 0..10 slider (level = (10 − n) × 10), plus the ANC switch. Everything else the model
 * declares and the headset reported is a [VendorControl].
 *
 * The EQ (generation 2 only) is shown on the existing three-band headphone EQ: the bands split
 * into low / middle / high thirds (5 bands: 2 + 1 + 2), each third shows its bands' average and a
 * change writes that value to every band in it.
 */
object SennheiserStateMapping {
    const val MODE_ANC = 0
    const val MODE_TRANSPARENCY = 1
    const val MODE_OFF = 2

    const val SUPPORT_NOTE =
        "Experimental: Earshot's first Sennheiser driver, built from protocol notes and other people's captures and not yet " +
            "tried on our own headphones. Noise control, battery, EQ and the headphones' own settings."
    const val NO_FEATURES_NOTE =
        "Experimental Sennheiser driver: these headphones didn't list their features, so Earshot reads nothing more and sends them nothing."

    // VendorControl ids.
    const val ANTI_WIND = "anti_wind"
    const val ADAPTIVE = "adaptive"
    const val COMFORT = "comfort"
    const val TRANSPARENCY_LEVEL = "transparency_level"
    const val PAUSE_IN_TRANSPARENCY = "pause_in_transparency"
    const val ON_HEAD = "on_head"
    const val SMART_PAUSE = "smart_pause"
    const val AUTO_ANSWER = "auto_answer"
    const val COMFORT_CALL = "comfort_call"
    const val SIDETONE = "sidetone"
    const val AUTO_OFF = "auto_off"
    const val BASS_BOOST = "bass_boost"

    /** A headset whose model isn't known (the user set "Headphone type: Sennheiser" on something else): only the settings every model has. */
    private val FALLBACK_SETTINGS = setOf(SennheiserSetting.PAUSE_IN_TRANSPARENCY, SennheiserSetting.AUTO_OFF)

    // Explicit meanings: saved rules and the tile resolve by them, not by these English names.
    val MODES = listOf(
        ListeningMode(MODE_ANC, "Noise cancelling", isPreset = true, isEditable = false, isConfigured = true, meaning = ModeMeaning.QUIET),
        ListeningMode(MODE_TRANSPARENCY, "Transparency", isPreset = true, isEditable = false, isConfigured = true, meaning = ModeMeaning.AWARE),
        ListeningMode(MODE_OFF, "Off", isPreset = true, isEditable = false, isConfigured = true, meaning = ModeMeaning.OFF),
    )

    fun family(model: SennheiserModel?): SennheiserNoiseFamily = model?.noise ?: SennheiserNoiseFamily.TOGGLES

    private fun settings(model: SennheiserModel?): Set<SennheiserSetting> = model?.settings ?: FALLBACK_SETTINGS

    private fun Boolean?.bit(): Int? = this?.let { if (it) 1 else 0 }

    /** The mode the headset is in: transparency wins (it needs ANC on the headbands), then ANC, else off. */
    fun currentMode(s: SennheiserStatus): Int? = when {
        s.transparencyOn == true -> MODE_TRANSPARENCY
        s.ancOn == true -> MODE_ANC
        s.ancOn == false -> MODE_OFF
        else -> null
    }

    fun noise(s: SennheiserStatus, model: SennheiserModel?): NoiseSettings? {
        val anc = s.ancOn ?: return null
        val level = if (family(model) == SennheiserNoiseFamily.SLIDER) s.ancLevel?.let(SennheiserWire::cancellingFromLevel) ?: 10 else 10
        return NoiseSettings(cancellingLevel = level, ancEnabled = anc)
    }

    /** Everything this model lets you set, in the order a screen should show it; the noise ones also on Home. */
    fun controls(s: SennheiserStatus, model: SennheiserModel?): List<VendorControl> = buildList {
        val settings = settings(model)
        val family = family(model)
        val headband = model?.earbuds != true
        fun has(setting: SennheiserSetting, feature: Int) = setting in settings && s.has(feature)

        if (family == SennheiserNoiseFamily.SLIDER && has(SennheiserSetting.ADAPTIVE, SennheiserFeature.ANC)) {
            add(VendorControl(ADAPTIVE, "Adaptive noise cancelling", "Adjusts the cancelling to your surroundings", value = s.ancModes[SennheiserCommands.MODE_ADAPTIVE]?.let { if (it != 0) 1 else 0 }, onHome = true))
        }
        if (has(SennheiserSetting.ANTI_WIND, SennheiserFeature.ANC)) {
            val raw = s.ancModes[SennheiserCommands.MODE_ANTI_WIND]
            add(
                if (family == SennheiserNoiseFamily.SLIDER) {
                    VendorControl(ANTI_WIND, "Anti-wind", value = raw?.takeIf { it in 0..2 }, options = listOf("Off", "Max", "Auto"), onHome = true)
                } else {
                    VendorControl(ANTI_WIND, "Anti-wind", "Lowers wind noise while noise cancelling is on", value = raw?.let { if (it != 0) 1 else 0 }, onHome = true)
                },
            )
        }
        if (family == SennheiserNoiseFamily.SLIDER && has(SennheiserSetting.COMFORT, SennheiserFeature.ANC)) {
            add(VendorControl(COMFORT, "Comfort mode", "Sennheiser's comfort setting for noise cancelling; its exact effect is unconfirmed", value = s.ancModes[SennheiserCommands.MODE_COMFORT]?.let { if (it != 0) 1 else 0 }))
        }
        if (has(SennheiserSetting.TRANSPARENCY_LEVEL, SennheiserFeature.TRANSPARENT_HEARING)) {
            add(VendorControl(TRANSPARENCY_LEVEL, "Transparency level", value = s.transparencyLevel?.let(SennheiserWire::transparencyPreset), options = listOf("Low", "Medium", "High"), onHome = true))
        }
        if (has(SennheiserSetting.PAUSE_IN_TRANSPARENCY, SennheiserFeature.TRANSPARENT_HEARING)) {
            add(VendorControl(PAUSE_IN_TRANSPARENCY, "Pause music in transparency", "Pauses playback while transparency is fully on", value = s.pauseInTransparency.bit()))
        }
        if (has(SennheiserSetting.ON_HEAD, SennheiserFeature.DEVICE)) {
            add(VendorControl(ON_HEAD, "On-head detection", "Lets the headphones notice when you take them off", value = s.onHeadDetection.bit()))
        }
        if (has(SennheiserSetting.SMART_PAUSE, SennheiserFeature.GENERIC_AUDIO)) {
            val subtitle = if (headband) "Pauses music when you take them off (needs on-head detection)" else "Pauses music when you take an earbud out"
            add(VendorControl(SMART_PAUSE, "Smart pause", subtitle, value = s.smartPause.bit()))
        }
        if (has(SennheiserSetting.AUTO_ANSWER, SennheiserFeature.GENERIC_AUDIO)) {
            add(VendorControl(AUTO_ANSWER, "Auto-answer calls", value = s.autoAnswer.bit()))
        }
        if (has(SennheiserSetting.COMFORT_CALL, SennheiserFeature.GENERIC_AUDIO)) {
            add(VendorControl(COMFORT_CALL, "Comfort call", value = s.comfortCall.bit()))
        }
        if (has(SennheiserSetting.SIDETONE, SennheiserFeature.GENERIC_AUDIO)) {
            add(VendorControl(SIDETONE, "Sidetone", "Hear your own voice during calls", value = s.sidetone?.takeIf { it in 0..2 }, options = listOf("Off", "Low", "High")))
        }
        if (has(SennheiserSetting.AUTO_OFF, SennheiserFeature.BATTERY)) {
            add(
                VendorControl(
                    AUTO_OFF, "Auto power off", "Switch off after a while without a connection",
                    value = s.autoOffSeconds?.let { SennheiserWire.AUTO_OFF_CHOICES.indexOf(it) }?.takeIf { it >= 0 },
                    options = listOf("Never", "15 min", "30 min", "1 h"),
                ),
            )
        }
        if (s.eqGen2 && s.eqConfig != null) {
            add(VendorControl(BASS_BOOST, "Bass boost", value = s.bassBoost.bit()))
        }
    }

    /** One write the session sends for a control, and what it re-reads afterwards. */
    class Write(val command: Int, val payload: ByteArray, val reread: List<Int>, val touchesNoise: Boolean = false)

    /** The write that sets [id] to [value], or null for an id this model doesn't have or a value out of range. */
    fun write(id: String, value: Int, s: SennheiserStatus, model: SennheiserModel?): Write? {
        if (controls(s, model).none { it.id == id }) return null
        val bit = { if (value in 0..1) byteArrayOf(value.toByte()) else null }
        return when (id) {
            ADAPTIVE -> bit()?.let { Write(SennheiserCommands.ANC_MODE_SET, SennheiserWire.ancModePair(SennheiserCommands.MODE_ADAPTIVE, value), emptyList(), touchesNoise = true) }
            COMFORT -> bit()?.let { Write(SennheiserCommands.ANC_MODE_SET, SennheiserWire.ancModePair(SennheiserCommands.MODE_COMFORT, value), emptyList(), touchesNoise = true) }
            ANTI_WIND -> {
                val max = if (family(model) == SennheiserNoiseFamily.SLIDER) 2 else 1
                if (value !in 0..max) null else Write(SennheiserCommands.ANC_MODE_SET, SennheiserWire.ancModePair(SennheiserCommands.MODE_ANTI_WIND, value), emptyList(), touchesNoise = true)
            }
            TRANSPARENCY_LEVEL -> SennheiserWire.TRANSPARENCY_PRESETS.getOrNull(value)?.let {
                Write(SennheiserCommands.TH_LEVEL_SET, byteArrayOf(it.toByte()), listOf(SennheiserCommands.TH_LEVEL_GET), touchesNoise = true)
            }
            PAUSE_IN_TRANSPARENCY -> bit()?.let { Write(SennheiserCommands.TH_BEHAVIOUR_SET, it, listOf(SennheiserCommands.TH_BEHAVIOUR_GET)) }
            ON_HEAD -> bit()?.let { Write(SennheiserCommands.ON_HEAD_SET, it, listOf(SennheiserCommands.ON_HEAD_GET)) }
            SMART_PAUSE -> bit()?.let { Write(SennheiserCommands.SMART_PAUSE_SET, it, listOf(SennheiserCommands.SMART_PAUSE_GET)) }
            AUTO_ANSWER -> bit()?.let { Write(SennheiserCommands.AUTO_ANSWER_SET, it, listOf(SennheiserCommands.AUTO_ANSWER_GET)) }
            COMFORT_CALL -> bit()?.let { Write(SennheiserCommands.COMFORT_CALL_SET, it, listOf(SennheiserCommands.COMFORT_CALL_GET)) }
            SIDETONE -> if (value !in 0..2) null else Write(SennheiserCommands.SIDETONE_SET, byteArrayOf(value.toByte()), listOf(SennheiserCommands.SIDETONE_GET))
            // Auto-off writes send no notification (momentum4-control capture): the re-read is what shows it.
            AUTO_OFF -> SennheiserWire.AUTO_OFF_CHOICES.getOrNull(value)?.let {
                Write(SennheiserCommands.AUTO_OFF_SET, SennheiserWire.autoOffSet(it), listOf(SennheiserCommands.AUTO_OFF_GET))
            }
            BASS_BOOST -> bit()?.let { Write(SennheiserCommands.EQ_SUBMODE_SET, it, listOf(SennheiserCommands.EQ_SUBMODE_GET)) }
            else -> null
        }
    }

    // ---- EQ -------------------------------------------------------------------------------

    /** Band indices of the low, middle and high thirds of [bands] bands. */
    fun eqGroups(bands: Int): List<IntRange> {
        val third = (bands + 2) / 3
        val lowEnd = minOf(third, bands)
        val highStart = maxOf(lowEnd, bands - third)
        return listOf(0 until lowEnd, lowEnd until highStart, highStart until bands)
    }

    fun eq(s: SennheiserStatus): HeadphoneEq? {
        val config = s.eqConfig ?: return null
        val gains = s.eqGains ?: return null
        if (!s.eqGen2) return null
        val range = eqRange(s) ?: return null
        val values = eqGroups(config.bands).map { group ->
            val g = group.filter { it < gains.size }.map { gains[it] }
            if (g.isEmpty()) 0 else SennheiserWire.tenthsToDb(g.sum() / g.size).coerceIn(range).coerceIn(-10, 10)
        }
        return HeadphoneEq(bass = values[0], mid = values[1], treble = values[2])
    }

    /** The headset's own range in whole dB, inside Earshot's −10..10. */
    fun eqRange(s: SennheiserStatus): IntRange? {
        val r = s.eqConfig?.rangeDb ?: return null
        return maxOf(r.first, -10)..minOf(r.last, 10)
    }

    /** `0x1001 [band, gain]` writes that turn [current] into [target]: only the thirds that changed. */
    fun eqWrites(s: SennheiserStatus, current: HeadphoneEq?, target: HeadphoneEq): List<ByteArray> {
        val config = s.eqConfig ?: return emptyList()
        val range = eqRange(s) ?: return emptyList()
        val groups = eqGroups(config.bands)
        val before = listOf(current?.bass, current?.mid, current?.treble)
        val after = listOf(target.bass, target.mid, target.treble)
        return groups.indices.flatMap { i ->
            if (before[i] == after[i]) emptyList()
            else {
                val tenths = SennheiserWire.dbToTenths(after[i].coerceIn(range)).coerceIn(config.minTenths, config.maxTenths)
                groups[i].map { band -> SennheiserWire.eqBandSet(band, tenths) }
            }
        }
    }

    // ---- Whole state ----------------------------------------------------------------------

    fun capabilities(s: SennheiserStatus, model: SennheiserModel?): HeadphoneCapabilities {
        val slider = family(model) == SennheiserNoiseFamily.SLIDER
        return HeadphoneCapabilities(
            spatialAudio = false,
            ancToggle = slider,
            liveNoiseControl = true,
            noiseCancelling = slider && s.has(SennheiserFeature.ANC),
            windBlock = false,
            editableModes = false,
            favoriteModes = false,
            listeningModes = s.has(SennheiserFeature.ANC) || s.has(SennheiserFeature.TRANSPARENT_HEARING),
            modesOnHome = true,
            verified = false,
            formFactor = if (model?.earbuds == true) FormFactor.EARBUDS else FormFactor.HEADPHONES,
            supportNote = if (s.features == null) NO_FEATURES_NOTE else SUPPORT_NOTE,
        )
    }

    fun apply(state: HeadphoneState, s: SennheiserStatus, model: SennheiserModel?, now: Long = System.currentTimeMillis()): HeadphoneState {
        val listening = s.has(SennheiserFeature.ANC) || s.has(SennheiserFeature.TRANSPARENT_HEARING)
        return withBattery(state, s).copy(
            modes = if (listening) MODES else emptyList(),
            currentModeIndex = if (listening) currentMode(s) else null,
            noise = noise(s, model),
            eq = eq(s),
            eqRange = eqRange(s),
            vendorControls = controls(s, model),
            info = DeviceInfo(
                productName = model?.let { "Sennheiser ${it.displayName}" } ?: s.modelId,
                modelName = model?.let { "Sennheiser ${it.displayName}" },
                firmwareVersion = s.firmware,
            ),
            capabilities = capabilities(s, model),
            lastUpdatedMillis = now,
        )
    }

    /** `[p]` headphones; `[L, R]` or `[L, R, case]` earbuds, headline = the lower bud. */
    fun withBattery(state: HeadphoneState, s: SennheiserStatus): HeadphoneState {
        val b = s.battery ?: return state
        val charging = s.charging?.let { c -> c.any { it == 1 } }
        return when (b.size) {
            0 -> state
            1 -> state.copy(batteryPercent = b[0], battery = BatteryDetail(overall = BatteryLevel(b[0])), isCharging = charging ?: state.isCharging)
            else -> state.copy(
                batteryPercent = listOfNotNull(b[0], b[1]).minOrNull(),
                battery = BatteryDetail(left = BatteryLevel(b[0]), right = BatteryLevel(b[1]), case = b.getOrNull(2)?.let { BatteryLevel(it) }),
                isCharging = charging ?: state.isCharging,
            )
        }
    }
}

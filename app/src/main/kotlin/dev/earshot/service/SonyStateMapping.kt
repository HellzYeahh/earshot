package dev.earshot.service

import dev.earshot.core.BatteryDetail
import dev.earshot.core.BatteryLevel
import dev.earshot.core.DeviceInfo
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCapabilities
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.NoiseSettings
import dev.earshot.core.VendorControl
import dev.earshot.protocol.sony.AudioType
import dev.earshot.protocol.sony.SonyAudio
import dev.earshot.protocol.sony.SonyAutoOff
import dev.earshot.protocol.sony.SonyAutoPowerOff
import dev.earshot.protocol.sony.SonyBattery
import dev.earshot.protocol.sony.SonyBatteryLevel
import dev.earshot.protocol.sony.SonyBatteryReading
import dev.earshot.protocol.sony.SonyDeviceInfo
import dev.earshot.protocol.sony.SonyEq
import dev.earshot.protocol.sony.SonyEqCapability
import dev.earshot.protocol.sony.SonyEqPresets
import dev.earshot.protocol.sony.SonyEqSetting
import dev.earshot.protocol.sony.SonyFunction
import dev.earshot.protocol.sony.SonyGeneralSetting
import dev.earshot.protocol.sony.SonyMessage
import dev.earshot.protocol.sony.SonyModel
import dev.earshot.protocol.sony.SonyNcAsm
import dev.earshot.protocol.sony.SonyNcAsmShape
import dev.earshot.protocol.sony.SonyNoiseMode
import dev.earshot.protocol.sony.SonyProtocolInfo
import dev.earshot.protocol.sony.SonySystem
import dev.earshot.protocol.sony.SonyTable
import dev.earshot.protocol.sony.SonyVoiceGuidance
import dev.earshot.protocol.sony.SystemType
import dev.earshot.protocol.sony.T1
import dev.earshot.protocol.sony.T2
import kotlin.math.roundToInt

/**
 * Everything read from a Sony headset in one connection: what it announced (support functions and
 * capabilities) and the current values. Built up by [SonyStateMapping.update] from replies and
 * pushes alike, so a NTFY from the headset's own buttons lands the same way as a reply.
 */
data class SonySnapshot(
    val protocol: SonyProtocolInfo? = null,
    val modelName: String? = null,
    val firmware: String? = null,
    val series: Int? = null,
    val t1: Set<Int> = emptySet(),
    val t2: Set<Int> = emptySet(),
    val single: SonyBatteryLevel? = null,
    val left: SonyBatteryLevel? = null,
    val right: SonyBatteryLevel? = null,
    val case: SonyBatteryLevel? = null,
    val ncAsmType: Int? = null,
    val ncAsm: SonyNcAsm? = null,
    val ambientRange: IntRange? = null,
    val eqType: Int? = null,
    val eqCapability: SonyEqCapability? = null,
    val eq: SonyEqSetting? = null,
    val autoOffType: Int? = null,
    val autoOffChoices: List<SonyAutoOff> = emptyList(),
    val autoOff: SonyAutoOff? = null,
    val autoOffLast: SonyAutoOff? = null,
    /** Boolean general settings by type (D1–D4), as the headset named them. */
    val generalSettings: Map<Int, SonyGeneralSetting> = emptyMap(),
    val generalValues: Map<Int, Boolean> = emptyMap(),
    val upscalingType: Int? = null,
    val dsee: Boolean? = null,
    val connectionModeType: Int? = null,
    val connectionMode: Int? = null,
    val speakToChat: Boolean? = null,
    val pauseWhenTakenOff: Boolean? = null,
    val voiceGuidanceType: Int? = null,
    val voiceGuidance: Boolean? = null,
) {
    /** Writes are refused for an MDR version Sound Connect wouldn't run (see [SonyProtocolInfo.isKnownVersion]). */
    val writable: Boolean get() = protocol?.isKnownVersion == true

    val levelRange: IntRange get() = ambientRange ?: SonyNcAsmShape.DEFAULT_LEVEL_RANGE

    /** The EQ step count: the capability's, else the layout's default. */
    val eqSteps: Int get() = eqCapability?.steps?.takeIf { it >= 3 } ?: SonyEq.defaultSteps(eq?.steps?.size ?: 6)

    /** Which per-function types this support list selects. */
    fun withSupport(t1: Set<Int>, t2: Set<Int>): SonySnapshot = copy(
        t1 = t1, t2 = t2,
        ncAsmType = SonyNcAsm.typeFor(t1),
        eqType = SonyEq.typeFor(t1),
        autoOffType = SonyAutoPowerOff.typeFor(t1),
        connectionModeType = SonyAudio.connectionModeTypeFor(t1),
        voiceGuidanceType = SonyVoiceGuidance.typeFor(t2),
    )
}

/**
 * Sony state → [HeadphoneState], and vendor-control changes → messages. Pure, unit-tested.
 *
 * Noise is a **mode** on Sony; only ambient sound has a level. So the three Earshot slots are
 * slot 0 "Noise cancelling", slot 1 "Ambient sound", slot 2 "Off" (the same meaning as on Bose and
 * the Crusher, so rules, the tile and the widget work), and the ambient level (its range from the
 * headset's capability reply) and focus on voice are vendor controls on Home. EQ presets, bands,
 * auto power off and the headset's own toggles are vendor controls in More.
 */
object SonyStateMapping {
    const val MODE_NC = 0
    const val MODE_AMBIENT = 1
    const val MODE_OFF = 2

    const val SUPPORT_NOTE =
        "Experimental: Sony support is built from protocol notes and other people's captures and hasn't been tried on a real Sony headset yet."
    const val READ_ONLY_NOTE =
        "Experimental, read-only: these Sony headphones report a protocol version Earshot doesn't know, so it only reads their settings."

    // VendorControl ids.
    const val AMBIENT_LEVEL = "ambient_level"
    const val FOCUS_ON_VOICE = "focus_on_voice"
    const val AUTO_AMBIENT = "auto_ambient"
    const val AUTO_AMBIENT_SENSITIVITY = "auto_ambient_sensitivity"
    const val EQ_PRESET = "eq_preset"
    const val EQ_BAND_PREFIX = "eq_band_"
    const val AUTO_POWER_OFF = "auto_power_off"
    const val SPEAK_TO_CHAT = "speak_to_chat"
    const val PAUSE_WHEN_TAKEN_OFF = "pause_when_taken_off"
    const val DSEE = "dsee"
    const val CONNECTION_PRIORITY = "connection_priority"
    const val VOICE_GUIDANCE = "voice_guidance"
    const val GENERAL_PREFIX = "general_"

    fun modeFor(index: Int): SonyNoiseMode? = when (index) {
        MODE_NC -> SonyNoiseMode.NOISE_CANCELLING
        MODE_AMBIENT -> SonyNoiseMode.AMBIENT
        MODE_OFF -> SonyNoiseMode.OFF
        else -> null
    }

    fun indexFor(mode: SonyNoiseMode): Int = when (mode) {
        SonyNoiseMode.NOISE_CANCELLING -> MODE_NC
        SonyNoiseMode.AMBIENT -> MODE_AMBIENT
        SonyNoiseMode.OFF -> MODE_OFF
    }

    // ---- Updates ------------------------------------------------------------------------

    /** Fold one reply or push into [s]; anything unrecognised leaves it as it is. */
    fun update(s: SonySnapshot, m: SonyMessage): SonySnapshot {
        val p = m.payload
        if (m.table == SonyTable.T2) {
            return if (m.command == T2.VOICE_GUIDANCE_RET_PARAM && m.type == s.voiceGuidanceType) {
                SonyVoiceGuidance.parse(p)?.let { s.copy(voiceGuidance = it) } ?: s
            } else s
        }
        return when (m.command) {
            T1.CONNECT_RET_PROTOCOL_INFO -> SonyProtocolInfo.parse(p)?.let { s.copy(protocol = it) } ?: s
            T1.CONNECT_RET_DEVICE_INFO -> when (m.type) {
                SonyDeviceInfo.MODEL_NAME -> s.copy(modelName = SonyDeviceInfo.parseString(p, SonyDeviceInfo.MODEL_NAME) ?: s.modelName)
                SonyDeviceInfo.FW_VERSION -> s.copy(firmware = SonyDeviceInfo.parseString(p, SonyDeviceInfo.FW_VERSION) ?: s.firmware)
                SonyDeviceInfo.SERIES_AND_COLOR -> s.copy(series = SonyDeviceInfo.parseSeries(p)?.first ?: s.series)
                else -> s
            }
            T1.POWER_RET_STATUS, T1.POWER_NTFY_STATUS -> when (val r = SonyBattery.parse(p)) {
                is SonyBatteryReading.Single -> s.copy(single = r.level)
                is SonyBatteryReading.LeftRight -> s.copy(left = r.left, right = r.right)
                is SonyBatteryReading.Case -> s.copy(case = r.level)
                null -> s
            }
            T1.POWER_RET_CAPABILITY -> SonyAutoPowerOff.parseCapability(p)?.let { s.copy(autoOffChoices = it) } ?: s
            T1.POWER_RET_PARAM, T1.POWER_NTFY_PARAM -> SonyAutoPowerOff.parseParam(p)?.let { (current, last) ->
                s.copy(autoOff = current ?: s.autoOff, autoOffLast = last?.takeIf { it.minutes != null } ?: s.autoOffLast)
            } ?: s
            T1.NCASM_RET_CAPABILITY -> SonyNcAsm.parseAmbientRange(p)?.let { s.copy(ambientRange = it) } ?: s
            T1.NCASM_RET_PARAM, T1.NCASM_NTFY_PARAM -> SonyNcAsm.parse(p)?.takeIf { it.type == s.ncAsmType || s.ncAsmType == null }?.let { s.copy(ncAsm = it) } ?: s
            T1.EQEBB_RET_CAPABILITY -> SonyEqCapability.parse(p)?.let { s.copy(eqCapability = it) } ?: s
            T1.EQEBB_RET_PARAM, T1.EQEBB_NTFY_PARAM -> SonyEqSetting.parse(p)?.takeIf { it.type == s.eqType }?.let { s.copy(eq = it) } ?: s
            T1.GENERAL_SETTING_RET_CAPABILITY -> SonyGeneralSetting.parseCapability(p)?.takeIf { it.isBoolean }?.let {
                s.copy(generalSettings = s.generalSettings + (it.type to it))
            } ?: s
            T1.GENERAL_SETTING_RET_PARAM, T1.GENERAL_SETTING_NTFY_PARAM -> SonyGeneralSetting.parseBoolean(p)?.let { (type, on) ->
                s.copy(generalValues = s.generalValues + (type to on))
            } ?: s
            T1.AUDIO_RET_CAPABILITY -> SonyAudio.parseUpscalingType(p)?.let { s.copy(upscalingType = it) } ?: s
            T1.AUDIO_RET_PARAM, T1.AUDIO_NTFY_PARAM -> SonyAudio.parseParam(p)?.let { (type, value) ->
                when (type) {
                    AudioType.UPSCALING -> s.copy(dsee = value == 1)
                    s.connectionModeType -> s.copy(connectionMode = value)
                    else -> s
                }
            } ?: s
            T1.SYSTEM_RET_PARAM, T1.SYSTEM_NTFY_PARAM -> SonySystem.parseBoolean(p)?.let { (type, on) ->
                when (type) {
                    SystemType.SMART_TALKING_MODE_TYPE2 -> s.copy(speakToChat = on)
                    SystemType.PLAYBACK_CONTROL_BY_WEARING -> s.copy(pauseWhenTakenOff = on)
                    else -> s
                }
            } ?: s
            else -> s
        }
    }

    // ---- HeadphoneState --------------------------------------------------------------------

    fun modes(s: SonySnapshot): List<ListeningMode> {
        val nc = s.ncAsm ?: return emptyList()
        fun mode(index: Int, name: String, settings: NoiseSettings) =
            ListeningMode(index, name, isPreset = true, isEditable = false, isConfigured = true, settings = settings)
        return buildList {
            if (nc.hasNoiseCancelling) add(mode(MODE_NC, "Noise cancelling", NoiseSettings(10, ancEnabled = true)))
            if (nc.hasAmbient) add(mode(MODE_AMBIENT, "Ambient sound", NoiseSettings(ambientAsCancelling(s), ancEnabled = true)))
            add(mode(MODE_OFF, "Off", NoiseSettings(0, ancEnabled = false)))
        }
    }

    /**
     * The ambient level on Earshot's 0..10 "cancelling" scale (10 = quietest), for automations and
     * the notification: the lowest ambient level is 10, the highest 0. Lossy (20 levels into 11);
     * the vendor slider shows the headset's own scale.
     */
    fun ambientAsCancelling(s: SonySnapshot): Int {
        val level = s.ncAsm?.ambientLevel ?: return 0
        val r = s.levelRange
        if (r.last <= r.first) return 0
        return (10 - (level - r.first) * 10.0 / (r.last - r.first)).roundToInt().coerceIn(0, 10)
    }

    /** The inverse of [ambientAsCancelling]: a 0..10 cancelling level as an ambient level in the headset's range. */
    fun cancellingAsAmbient(s: SonySnapshot, cancelling: Int): Int {
        val r = s.levelRange
        return (r.first + (10 - cancelling.coerceIn(0, 10)) * (r.last - r.first) / 10.0).roundToInt().coerceIn(r)
    }

    fun noise(s: SonySnapshot): NoiseSettings? {
        val nc = s.ncAsm ?: return null
        return when (nc.mode) {
            SonyNoiseMode.NOISE_CANCELLING -> NoiseSettings(10, ancEnabled = true)
            SonyNoiseMode.AMBIENT -> NoiseSettings(ambientAsCancelling(s), ancEnabled = true)
            SonyNoiseMode.OFF -> NoiseSettings(0, ancEnabled = false)
        }
    }

    fun formFactor(s: SonySnapshot): FormFactor = when (SonyModel.shapeOf(s.modelName, s.series)) {
        SonyModel.Shape.HEADPHONES -> FormFactor.HEADPHONES
        SonyModel.Shape.EARBUDS -> FormFactor.EARBUDS
        SonyModel.Shape.OPEN_EARBUDS -> FormFactor.OPEN_EARBUDS
    }

    fun capabilities(s: SonySnapshot) = HeadphoneCapabilities(
        spatialAudio = false,
        ancToggle = false,
        liveNoiseControl = true,
        // Noise cancelling has no level on Sony: the modes carry it, and the ambient level is a vendor slider.
        noiseCancelling = false,
        windBlock = false,
        editableModes = false,
        favoriteModes = false,
        listeningModes = s.ncAsm != null,
        modesOnHome = true,
        verified = false,
        formFactor = formFactor(s),
        supportNote = if (s.writable) SUPPORT_NOTE else READ_ONLY_NOTE,
    )

    fun apply(state: HeadphoneState, s: SonySnapshot, now: Long = System.currentTimeMillis()): HeadphoneState {
        val nc = s.ncAsm
        return withBattery(state, s).copy(
            modes = modes(s),
            currentModeIndex = nc?.let { indexFor(it.mode) },
            noise = noise(s),
            vendorControls = controls(s),
            info = DeviceInfo(
                productName = s.modelName?.let { "Sony $it" },
                modelName = s.modelName,
                firmwareVersion = s.firmware,
                family = s.series?.let(SonyDeviceInfo::seriesName),
            ),
            capabilities = capabilities(s),
            lastUpdatedMillis = now,
        )
    }

    /**
     * Battery: a headband reports one level, earbuds report each bud and the case. The headline is
     * the single level, else the lower bud (the one that runs out first).
     */
    fun withBattery(state: HeadphoneState, s: SonySnapshot): HeadphoneState {
        fun level(l: SonyBatteryLevel?) = l?.let { BatteryLevel(it.percent) }
        val headline = s.single?.percent ?: listOfNotNull(s.left?.percent, s.right?.percent).minOrNull()
        val parts = listOfNotNull(s.single, s.left, s.right)
        return state.copy(
            batteryPercent = headline,
            isCharging = if (parts.isEmpty()) null else parts.any { it.charging },
            battery = if (s.single == null && s.left == null && s.case == null) state.battery else BatteryDetail(
                overall = level(s.single),
                left = level(s.left),
                right = level(s.right),
                case = level(s.case),
            ),
        )
    }

    // ---- Vendor controls -------------------------------------------------------------------

    private fun Boolean?.bit(): Int? = this?.let { if (it) 1 else 0 }

    /** Everything this headset announced that Earshot can set, Home's quick ones first. */
    fun controls(s: SonySnapshot): List<VendorControl> = buildList {
        val nc = s.ncAsm
        if (nc != null && nc.hasLevel) add(
            VendorControl(
                AMBIENT_LEVEL, "Ambient sound level", "How much of your surroundings you hear in Ambient sound",
                value = nc.ambientLevel?.coerceIn(s.levelRange), onHome = true, range = s.levelRange,
            ),
        )
        if (nc != null && nc.hasAmbient) add(
            VendorControl(FOCUS_ON_VOICE, "Focus on voice", "Ambient sound lets voices through and lowers other noise", value = nc.focusOnVoice.bit(), onHome = true),
        )
        if (nc?.autoAmbient != null) {
            add(VendorControl(AUTO_AMBIENT, "Automatic ambient level", "The headphones set the level from how loud it is around you", value = nc.autoAmbient.bit(), onHome = true))
            add(
                VendorControl(
                    AUTO_AMBIENT_SENSITIVITY, "Automatic level sensitivity",
                    value = nc.autoAmbientSensitivity?.let { SENSITIVITY_ORDER.indexOf(it) }?.takeIf { it >= 0 },
                    options = listOf("Low", "Standard", "High"),
                ),
            )
        }
        val eqType = s.eqType
        val cap = s.eqCapability
        if (eqType != null && cap != null && cap.presets.isNotEmpty()) add(
            VendorControl(
                EQ_PRESET, "Equalizer", "The headphones' own EQ presets",
                value = s.eq?.presetId?.let { cap.presets.indexOf(it) }?.takeIf { it >= 0 },
                options = cap.presets.map(SonyEqPresets::label),
            ),
        )
        val eq = s.eq
        val bandCount = cap?.bandCount?.takeIf { it > 0 } ?: eq?.steps?.size ?: 0
        if (eqType != null && (cap?.customizable ?: (eq != null)) && bandCount > 0) {
            val steps = s.eqSteps
            val db = eq?.bandsDb(steps)
            SonyEq.bandNames(bandCount).forEachIndexed { i, name ->
                add(VendorControl("$EQ_BAND_PREFIX$i", name, value = db?.getOrNull(i), range = SonyEq.rangeFor(steps), unit = "dB"))
            }
        }
        if (s.autoOffType != null) {
            val choices = s.autoOffChoices.ifEmpty { listOfNotNull(s.autoOff) }
            if (choices.isNotEmpty()) add(
                VendorControl(
                    AUTO_POWER_OFF, "Auto power off",
                    value = s.autoOff?.let { choices.indexOf(it) }?.takeIf { it >= 0 },
                    options = choices.map { it.label },
                ),
            )
        }
        if (SonyFunction.SMART_TALKING_MODE_TYPE2 in s.t1) add(
            VendorControl(SPEAK_TO_CHAT, "Speak-to-chat", "Talking pauses the music and lets sound in", value = s.speakToChat.bit()),
        )
        if (SonyFunction.PLAYBACK_CONTROL_BY_WEARING in s.t1) add(
            VendorControl(PAUSE_WHEN_TAKEN_OFF, "Pause when taken off", value = s.pauseWhenTakenOff.bit()),
        )
        if (SonyFunction.UPSCALING_AUTO_OFF in s.t1) add(
            VendorControl(DSEE, SonyAudio.upscalingName(s.upscalingType), "Upscales compressed music", value = s.dsee.bit()),
        )
        if (s.connectionModeType != null) add(
            VendorControl(
                CONNECTION_PRIORITY, "Bluetooth connection", value = s.connectionMode?.takeIf { it in 0..1 },
                options = listOf("Sound quality", "Stable connection"),
            ),
        )
        if (s.voiceGuidanceType != null) add(VendorControl(VOICE_GUIDANCE, "Voice guidance", value = s.voiceGuidance.bit()))
        for ((type, setting) in s.generalSettings.toSortedMap()) {
            add(VendorControl("$GENERAL_PREFIX%02x".format(type), setting.title, value = s.generalValues[type].bit()))
        }
    }

    /** `NoiseAdaptiveSensitivity` STANDARD 0, HIGH 1, LOW 2, shown low → high. */
    private val SENSITIVITY_ORDER = listOf(2, 0, 1)

    /**
     * The message that sets [id] to [value], or null for an id this headset doesn't have. [value]
     * is 0/1 for a switch, the option index for a choice, the level for a slider.
     */
    fun write(s: SonySnapshot, id: String, value: Int): SonyMessage? {
        val on = value != 0
        fun t1(p: ByteArray) = SonyMessage(SonyTable.T1, p)
        val nc = s.ncAsm
        return when {
            id == AMBIENT_LEVEL && nc != null && nc.hasLevel -> t1(nc.copy(ambientLevel = value.coerceIn(s.levelRange)).encodeSet())
            id == FOCUS_ON_VOICE && nc != null && nc.hasAmbient -> t1(nc.copy(focusOnVoice = on).encodeSet())
            id == AUTO_AMBIENT && nc?.autoAmbient != null -> t1(nc.copy(autoAmbient = on).encodeSet())
            id == AUTO_AMBIENT_SENSITIVITY && nc?.autoAmbient != null ->
                SENSITIVITY_ORDER.getOrNull(value)?.let { t1(nc.copy(autoAmbientSensitivity = it).encodeSet()) }
            id == EQ_PRESET -> {
                val type = s.eqType ?: return null
                val preset = s.eqCapability?.presets?.getOrNull(value) ?: return null
                t1(SonyEq.setPreset(type, preset))
            }
            id.startsWith(EQ_BAND_PREFIX) -> {
                val type = s.eqType ?: return null
                val eq = s.eq ?: return null
                val band = id.removePrefix(EQ_BAND_PREFIX).toIntOrNull() ?: return null
                val steps = s.eqSteps
                val db = eq.bandsDb(steps).toMutableList()
                if (band !in db.indices) return null
                db[band] = value.coerceIn(SonyEq.rangeFor(steps))
                // Editing bands moves a preset to the Manual slot (custom slots keep their own id), as the app does.
                val preset = when {
                    SonyEqPresets.isUserSlot(eq.presetId) -> eq.presetId
                    s.eqCapability?.presets?.contains(SonyEqPresets.CUSTOM) != false -> SonyEqPresets.CUSTOM
                    else -> eq.presetId
                }
                t1(SonyEq.setBands(type, preset, db, steps))
            }
            id == AUTO_POWER_OFF -> {
                val type = s.autoOffType ?: return null
                val choice = s.autoOffChoices.getOrNull(value) ?: return null
                t1(SonyAutoPowerOff.set(type, choice, s.autoOffLast ?: s.autoOff))
            }
            id == SPEAK_TO_CHAT && SonyFunction.SMART_TALKING_MODE_TYPE2 in s.t1 -> t1(SonySystem.setSpeakToChat(on))
            id == PAUSE_WHEN_TAKEN_OFF && SonyFunction.PLAYBACK_CONTROL_BY_WEARING in s.t1 -> t1(SonySystem.setPlaybackByWearing(on))
            id == DSEE && SonyFunction.UPSCALING_AUTO_OFF in s.t1 -> t1(SonyAudio.setUpscaling(on))
            id == CONNECTION_PRIORITY -> s.connectionModeType?.takeIf { value in 0..1 }?.let { t1(SonyAudio.setConnectionMode(it, value)) }
            id == VOICE_GUIDANCE -> s.voiceGuidanceType?.let { SonyMessage(SonyTable.T2, SonyVoiceGuidance.set(it, on)) }
            id.startsWith(GENERAL_PREFIX) -> {
                val type = id.removePrefix(GENERAL_PREFIX).toIntOrNull(16) ?: return null
                if (type !in s.generalSettings) return null
                t1(SonyGeneralSetting.setBoolean(type, on))
            }
            else -> null
        }
    }

    /** The GET that reads back what [write] changed, so the screen shows what the headset did. */
    fun readBack(s: SonySnapshot, id: String): SonyMessage? {
        fun t1(p: ByteArray) = SonyMessage(SonyTable.T1, p)
        return when {
            id in setOf(AMBIENT_LEVEL, FOCUS_ON_VOICE, AUTO_AMBIENT, AUTO_AMBIENT_SENSITIVITY) -> s.ncAsmType?.let { t1(SonyNcAsm.getParam(it)) }
            id == EQ_PRESET || id.startsWith(EQ_BAND_PREFIX) -> s.eqType?.let { t1(SonyEqSetting.get(it)) }
            id == AUTO_POWER_OFF -> s.autoOffType?.let { t1(SonyAutoPowerOff.getParam(it)) }
            id == SPEAK_TO_CHAT -> t1(SonySystem.getParam(SystemType.SMART_TALKING_MODE_TYPE2))
            id == PAUSE_WHEN_TAKEN_OFF -> t1(SonySystem.getParam(SystemType.PLAYBACK_CONTROL_BY_WEARING))
            id == DSEE -> t1(SonyAudio.getParam(AudioType.UPSCALING))
            id == CONNECTION_PRIORITY -> s.connectionModeType?.let { t1(SonyAudio.getParam(it)) }
            id == VOICE_GUIDANCE -> s.voiceGuidanceType?.let { SonyMessage(SonyTable.T2, SonyVoiceGuidance.getParam(it)) }
            id.startsWith(GENERAL_PREFIX) -> id.removePrefix(GENERAL_PREFIX).toIntOrNull(16)?.let { t1(SonyGeneralSetting.getParam(it)) }
            else -> null
        }
    }
}

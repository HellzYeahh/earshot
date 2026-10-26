package dev.earshot.service

import dev.earshot.core.BatteryDetail
import dev.earshot.core.BatteryLevel
import dev.earshot.core.BluetoothProfile
import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment
import dev.earshot.core.ButtonPress
import dev.earshot.core.DeviceProfiles
import dev.earshot.core.HeadphoneCapabilities
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneState
import dev.earshot.core.LeAudioPreference
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeSetting
import dev.earshot.core.NoiseSettings
import dev.earshot.core.OperationMode
import dev.earshot.core.PairedDevice
import dev.earshot.core.Playback
import dev.earshot.core.PlaybackState
import dev.earshot.core.Sidetone
import dev.earshot.core.SpatialAudio
import dev.earshot.core.TransportControl
import dev.earshot.core.VoicePrompts
import dev.earshot.protocol.BmapException
import dev.earshot.protocol.qcultra2.AudioSettings
import dev.earshot.protocol.qcultra2.AudioStatus
import dev.earshot.protocol.qcultra2.BatteryComponent
import dev.earshot.protocol.qcultra2.BatteryStatus
import dev.earshot.protocol.qcultra2.BoseModel
import dev.earshot.protocol.qcultra2.ModeNames
import dev.earshot.protocol.qcultra2.ModeSlotPlan
import dev.earshot.protocol.qcultra2.ButtonConfig
import dev.earshot.protocol.qcultra2.ClassicProfile
import dev.earshot.protocol.qcultra2.CncState
import dev.earshot.protocol.qcultra2.ModeLayout
import dev.earshot.protocol.qcultra2.DeviceExtendedInfo
import dev.earshot.protocol.qcultra2.EqBand
import dev.earshot.protocol.qcultra2.EqBandId
import dev.earshot.protocol.qcultra2.LatencyLevel
import dev.earshot.protocol.qcultra2.LeAudioCheckResult
import dev.earshot.protocol.qcultra2.ModeConfig
import dev.earshot.protocol.qcultra2.ModeField
import dev.earshot.protocol.qcultra2.OperationModeCode
import dev.earshot.protocol.qcultra2.PairedDevices
import dev.earshot.protocol.qcultra2.PlaybackStatus
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.protocol.qcultra2.SidetoneLevel
import dev.earshot.protocol.qcultra2.SidetoneState
import dev.earshot.protocol.qcultra2.VoicePromptsState
import dev.earshot.protocol.qcultra2.VolumeState
import dev.earshot.protocol.qcultra2.TransportControl as WireTransportControl
import dev.earshot.protocol.qcultra2.ButtonAction as WireButtonAction

/*
 * Pure conversions between the typed wire structures in `:protocol` and the human-unit models
 * in `core`. Nothing in here talks to the headphones, so everything is unit-testable.
 */

// ── Noise settings [31.10] ───────────────────────────────────────────────────────────────

/** Wire → UI. The CNC byte is inverted (0 = most cancelling); [AudioSettings.cancellingLevel] already flips it. */
internal fun AudioSettings.toNoiseSettings(): NoiseSettings = NoiseSettings(
    cancellingLevel = cancellingLevel,
    ancEnabled = ancEnabled,
    windBlock = windBlock,
    spatialAudio = SpatialAudio.fromWire(spatialCode),
)

/** UI → wire. autoCnc is always false: the firmware rejects 1 with Runtime error 8. */
internal fun NoiseSettings.toAudioSettings(): AudioSettings = AudioSettings(
    cncWire = AudioSettings.cancellingLevelToWire(cancellingLevel),
    spatialCode = spatialAudio.wire,
    windBlock = windBlock,
    ancEnabled = ancEnabled,
    autoCnc = false,
)

// ── Model ────────────────────────────────────────────────────────────────────────────────

/**
 * The catalogue entry corrected by what the connection showed, for callers that have no
 * [ConnectionFacts]: [layout] is the `[31.6]` layout of the STATUS frames actually received,
 * [liveNoise] whether `[31.10]` answered, [modes] whether block 31 exists at all. The defaults
 * are the catalogue's own guesses. The protocol itself uses [ConnectionFacts.capabilities].
 */
internal fun BoseModel.toCapabilities(
    layout: ModeLayout = modeLayout,
    liveNoise: Boolean = hasLiveAudioSettings,
    modes: Boolean = true,
): HeadphoneCapabilities = ConnectionFacts(model = this, seenLayout = layout).capabilities(
    path = when {
        isUnsupported -> NoisePath.NONE
        liveNoise && modes -> NoisePath.LIVE
        modes -> NoisePath.SLOTS
        else -> NoisePath.READ_ONLY
    },
    modesAvailable = modes,
)

/**
 * `[1.5]` CNC `[numSteps, currentStep, flags]` as noise settings (the direct path, or read-only
 * where it cannot be written). Step 0 is the *most* cancelling step: the Bose app names the field
 * `worldVolumeStep` and maps it with `abs(cnc − (steps − 1))`, and the wolverine scan read step 10
 * in Aware (docs/bose-models.md §C3). Wind and spatial are not part of `[1.5]`. Null for a
 * malformed reply.
 */
internal fun CncState.toNoiseSettings(): NoiseSettings? {
    val level = cancellingLevel ?: return null
    return NoiseSettings(cancellingLevel = level, ancEnabled = enabled, windBlock = false, spatialAudio = SpatialAudio.OFF)
}

// ── Battery [2.2] ────────────────────────────────────────────────────────────────────────

/**
 * `[2.2]` records by component id (docs/bose-models.md §C1): 0 single → [BatteryDetail.overall],
 * 1 right, 2 left, 3 case, and 4 (bosectl's "combined buds", unknown to the Bose app) as the
 * overall figure when there is no id 0. 0xFFFF minutes are already null. Null without records.
 */
internal fun BatteryStatus.toBatteryDetail(): BatteryDetail? {
    if (readings.isEmpty()) return null
    fun level(component: BatteryComponent) = reading(component)?.let { BatteryLevel(it.percent, it.remainingMinutes) }
    return BatteryDetail(
        overall = level(BatteryComponent.SINGLE) ?: level(BatteryComponent.COMBINED_BUDS),
        left = level(BatteryComponent.LEFT),
        right = level(BatteryComponent.RIGHT),
        case = level(BatteryComponent.CASE),
    )
}

/** Headline percentage ([Battery.aggregate]) and the per-part detail from one `[2.2]` payload. */
internal fun HeadphoneState.withBattery(status: BatteryStatus): HeadphoneState =
    copy(batteryPercent = status.percent ?: batteryPercent, battery = status.toBatteryDetail() ?: battery)

// ── Listening modes [31.6] + [31.8] ──────────────────────────────────────────────────────

/**
 * One `[31.6]` slot as a [ListeningMode]. Presets always count as configured; a custom slot is
 * configured unless its name is blank or the "None" sentinel (bosectl's free-slot rule — the
 * firmware's own `configured` bit is set on first write and never cleared, so it is useless for
 * that; a prompt left behind in a freed slot must not bring it back). An empty slot gets an empty
 * name and no settings, which is what the UI expects. The name is the Bose app's: the prompt's own
 * name, else the UTF-8 field, else "Mode N" ([ModeNames], docs/bose-models.md §C2).
 */
internal fun ModeConfig.toListeningMode(favorite: Boolean, plan: ModeSlotPlan = BoseModel.DEFAULT): ListeningMode {
    val promptName = ModeNames.forPrompt(prompt)
    // A built-in slot the model does not name is only a mode if the firmware named it; otherwise
    // an unused "None" slot would show up in the carousel as a blank built-in mode.
    val preset = index in plan.presetSlots && (index < plan.presetNames.size || promptName != null || !isEmptySlot)
    val configured = preset || !isEmptySlot
    return ListeningMode(
        index = index,
        name = if (configured) ModeNames.displayName(this) else "",
        isPreset = preset,
        // The SET_GET echo has no editable bit; fall back to the slot range. A model whose write
        // layout is unknown never offers editing, whatever the byte says.
        isEditable = plan.canEditModes && (editable ?: (index in plan.editableSlots)),
        isConfigured = configured,
        isFavorite = favorite,
        settings = if (configured) toAudioSettings().toNoiseSettings() else null,
        changeableSettings = changeableFields?.map { it.toModeSetting() }?.toSet(),
    )
}

internal fun ModeField.toModeSetting(): ModeSetting = when (this) {
    ModeField.CNC -> ModeSetting.CANCELLING_LEVEL
    ModeField.AUTO_CNC -> ModeSetting.AUTO_CNC
    ModeField.SPATIAL -> ModeSetting.SPATIAL_AUDIO
    ModeField.WIND -> ModeSetting.WIND_BLOCK
    ModeField.ANC -> ModeSetting.ANC
}

/** What to show for a slot the dump did not report: the previous entry, else a sensible blank. */
internal fun placeholderMode(
    index: Int, previous: ListeningMode?, favorite: Boolean, plan: ModeSlotPlan = BoseModel.DEFAULT,
): ListeningMode {
    if (previous != null) return previous.copy(isFavorite = favorite)
    val preset = index in plan.presetSlots
    val name = plan.presetNames.getOrNull(index)
    // A built-in slot whose name is unknown stays hidden until the dump names it.
    val known = preset && name != null
    return ListeningMode(
        index = index,
        name = name ?: "",
        isPreset = known,
        isEditable = plan.canEditModes && index in plan.editableSlots,
        isConfigured = known,
        isFavorite = favorite,
        settings = null,
    )
}

/**
 * All [ModeSlotPlan.slotCount] slots in order. Slots missing from [configs] keep their entry
 * from [previous] (a partial dump must not wipe the list) or become placeholders. [favorites]
 * null means "not read": keep each slot's previous favourite flag.
 */
internal fun buildModeList(
    configs: Map<Int, ModeConfig>,
    favorites: Set<Int>?,
    previous: List<ListeningMode>,
    plan: ModeSlotPlan = BoseModel.DEFAULT,
    slotCount: Int = plan.slotCount,
): List<ListeningMode> {
    val before = previous.associateBy { it.index }
    // A dump may report more slots than the plan (e.g. an unverified model).
    val count = maxOf(slotCount, (configs.keys.maxOrNull() ?: -1) + 1)
    return (0 until count).map { index ->
        val favorite = favorites?.contains(index) ?: (before[index]?.isFavorite ?: false)
        configs[index]?.toListeningMode(favorite, plan) ?: placeholderMode(index, before[index], favorite, plan)
    }
}

internal fun List<ListeningMode>.withFavorites(favorites: Set<Int>): List<ListeningMode> =
    map { it.copy(isFavorite = it.index in favorites) }

/** Replace (or insert, keeping index order) one slot, preserving its favourite flag. */
internal fun List<ListeningMode>.withSlot(config: ModeConfig, plan: ModeSlotPlan = BoseModel.DEFAULT): List<ListeningMode> {
    val favorite = firstOrNull { it.index == config.index }?.isFavorite ?: false
    val updated = config.toListeningMode(favorite, plan)
    return if (any { it.index == config.index }) map { if (it.index == config.index) updated else it }
    else (this + updated).sortedBy { it.index }
}

// ── Settings block ───────────────────────────────────────────────────────────────────────

/**
 * `[1.7]` band range for [HeadphoneState.eqRange]: the narrowest min..max over bass, mid and treble,
 * limited to the ±10 that [HeadphoneEq] holds. Null when the bands are missing or the range is empty.
 */
internal fun List<EqBand>.eqRange(): IntRange? {
    val main = filter { it.bandCode in EqBandId.BASS.code..EqBandId.TREBLE.code }
    if (main.isEmpty()) return null
    val lo = main.maxOf { it.min }.coerceAtLeast(QcUltra2.EQ_MIN)
    val hi = main.minOf { it.max }.coerceAtMost(QcUltra2.EQ_MAX)
    return if (lo < hi) lo..hi else null
}

/** `[1.7]` reply folded into the state: the values when all three bands are there, and their range. */
internal fun HeadphoneState.withEq(bands: List<EqBand>): HeadphoneState {
    val eq = bands.toHeadphoneEq() ?: return this
    return copy(eq = eq, eqRange = bands.eqRange() ?: eqRange)
}

/** `[1.7]` bands → [HeadphoneEq]; null unless bass, mid and treble are all present. Values clamp to ±10. */
internal fun List<EqBand>.toHeadphoneEq(): HeadphoneEq? {
    fun band(id: EqBandId): Int? = firstOrNull { it.bandCode == id.code }?.value?.coerceIn(QcUltra2.EQ_MIN, QcUltra2.EQ_MAX)
    return HeadphoneEq(
        bass = band(EqBandId.BASS) ?: return null,
        mid = band(EqBandId.MID) ?: return null,
        treble = band(EqBandId.TREBLE) ?: return null,
    )
}

/** `[1.11]` level byte → [Sidetone]; the wire order is 0 off, 1 high, 2 medium, 3 low. Unknown codes read as OFF. */
internal fun SidetoneState.toSidetone(): Sidetone = sidetoneFromLevelCode(levelCode)

/** A `[1.11]` level code as [Sidetone]; a code outside the table reads as OFF (as before the table moved to `:protocol`). */
internal fun sidetoneFromLevelCode(code: Int): Sidetone = when (SidetoneLevel.fromCode(code)) {
    SidetoneLevel.OFF, null -> Sidetone.OFF
    SidetoneLevel.HIGH -> Sidetone.HIGH
    SidetoneLevel.MEDIUM -> Sidetone.MEDIUM
    SidetoneLevel.LOW -> Sidetone.LOW
}

/** [Sidetone] → `[1.11]` level code (1 is high, 3 is low). */
internal fun Sidetone.toLevelCode(): Int = when (this) {
    Sidetone.OFF -> SidetoneLevel.OFF
    Sidetone.LOW -> SidetoneLevel.LOW
    Sidetone.MEDIUM -> SidetoneLevel.MEDIUM
    Sidetone.HIGH -> SidetoneLevel.HIGH
}.code

/**
 * A `[1.9]` action code as [ButtonAction]. Three names differ from Bose's (VPA, SPOTIFY_GO,
 * SPATIAL_AUDIO_MODE); the codes core has no action for (18 volume presets, 22-27 panic, third-party,
 * smart action, Alexa) and any code outside the table read as [ButtonAction.UNKNOWN].
 */
internal fun buttonActionFromCode(code: Int): ButtonAction = when (WireButtonAction.fromCode(code)) {
    WireButtonAction.NOT_CONFIGURED -> ButtonAction.NOT_CONFIGURED
    WireButtonAction.VPA -> ButtonAction.VOICE_ASSISTANT
    WireButtonAction.ANC_CYCLE -> ButtonAction.ANC_CYCLE
    WireButtonAction.BATTERY_LEVEL -> ButtonAction.BATTERY_LEVEL
    WireButtonAction.PLAY_PAUSE -> ButtonAction.PLAY_PAUSE
    WireButtonAction.INCREASE_CNC -> ButtonAction.INCREASE_CNC
    WireButtonAction.DECREASE_CNC -> ButtonAction.DECREASE_CNC
    WireButtonAction.TOGGLE_WAKE_WORD -> ButtonAction.TOGGLE_WAKE_WORD
    WireButtonAction.SWITCH_DEVICE -> ButtonAction.SWITCH_DEVICE
    WireButtonAction.CONVERSATION_MODE -> ButtonAction.CONVERSATION_MODE
    WireButtonAction.TRACK_FORWARD -> ButtonAction.TRACK_FORWARD
    WireButtonAction.TRACK_BACK -> ButtonAction.TRACK_BACK
    WireButtonAction.FETCH_NOTIFICATIONS -> ButtonAction.FETCH_NOTIFICATIONS
    WireButtonAction.WIND_MODE -> ButtonAction.WIND_MODE
    WireButtonAction.DISABLED -> ButtonAction.DISABLED
    WireButtonAction.CLIENT_INTERACTION -> ButtonAction.CLIENT_INTERACTION
    WireButtonAction.SPOTIFY_GO -> ButtonAction.SPOTIFY_TAP
    WireButtonAction.MODES_CAROUSEL -> ButtonAction.MODES_CAROUSEL
    WireButtonAction.SPATIAL_AUDIO_MODE -> ButtonAction.SPATIAL_AUDIO
    WireButtonAction.LINE_IN_SWITCH -> ButtonAction.LINE_IN_SWITCH
    WireButtonAction.LINKING -> ButtonAction.LINKING
    WireButtonAction.VOLUME_PRESET_CAROUSEL, WireButtonAction.PANIC_LOCAL, WireButtonAction.PANIC_REMOTE,
    WireButtonAction.PANIC_BOTH, WireButtonAction.THIRD_PARTY_SERVICE, WireButtonAction.SMART_ACTION,
    WireButtonAction.ALEXA, null,
    -> ButtonAction.UNKNOWN
}

/** [ButtonAction] → `[1.9]` action code. UNKNOWN has none: -1, which is never sent (setShortcutButton refuses UNKNOWN first). */
internal fun ButtonAction.toActionCode(): Int = when (this) {
    ButtonAction.NOT_CONFIGURED -> WireButtonAction.NOT_CONFIGURED
    ButtonAction.VOICE_ASSISTANT -> WireButtonAction.VPA
    ButtonAction.ANC_CYCLE -> WireButtonAction.ANC_CYCLE
    ButtonAction.BATTERY_LEVEL -> WireButtonAction.BATTERY_LEVEL
    ButtonAction.PLAY_PAUSE -> WireButtonAction.PLAY_PAUSE
    ButtonAction.INCREASE_CNC -> WireButtonAction.INCREASE_CNC
    ButtonAction.DECREASE_CNC -> WireButtonAction.DECREASE_CNC
    ButtonAction.TOGGLE_WAKE_WORD -> WireButtonAction.TOGGLE_WAKE_WORD
    ButtonAction.SWITCH_DEVICE -> WireButtonAction.SWITCH_DEVICE
    ButtonAction.CONVERSATION_MODE -> WireButtonAction.CONVERSATION_MODE
    ButtonAction.TRACK_FORWARD -> WireButtonAction.TRACK_FORWARD
    ButtonAction.TRACK_BACK -> WireButtonAction.TRACK_BACK
    ButtonAction.FETCH_NOTIFICATIONS -> WireButtonAction.FETCH_NOTIFICATIONS
    ButtonAction.WIND_MODE -> WireButtonAction.WIND_MODE
    ButtonAction.DISABLED -> WireButtonAction.DISABLED
    ButtonAction.CLIENT_INTERACTION -> WireButtonAction.CLIENT_INTERACTION
    ButtonAction.SPOTIFY_TAP -> WireButtonAction.SPOTIFY_GO
    ButtonAction.MODES_CAROUSEL -> WireButtonAction.MODES_CAROUSEL
    ButtonAction.SPATIAL_AUDIO -> WireButtonAction.SPATIAL_AUDIO_MODE
    ButtonAction.LINE_IN_SWITCH -> WireButtonAction.LINE_IN_SWITCH
    ButtonAction.LINKING -> WireButtonAction.LINKING
    ButtonAction.UNKNOWN -> null
}?.code ?: NO_ACTION_CODE

/** What [toActionCode] gives [ButtonAction.UNKNOWN]: the value core's table carried before it moved here. */
private const val NO_ACTION_CODE = -1

/**
 * `[1.9]` → [ButtonAssignment]; action codes outside the enum map to [ButtonAction.UNKNOWN] and are
 * dropped from the choices. The button and press names are Bose's (`[1.9]` byte 0 and 1 tables),
 * so the UI needs no Bose table to show them; null for a code the tables don't know.
 */
internal fun ButtonConfig.toButtonAssignment(): ButtonAssignment = ButtonAssignment(
    buttonId = buttonCode,
    eventType = eventCode,
    action = buttonActionFromCode(actionCode),
    supportedActions = supportedActionCodes.sorted()
        .map { buttonActionFromCode(it) }
        .filter { it != ButtonAction.UNKNOWN }
        .distinct(),
    buttonLabel = button?.label,
    eventLabel = event?.label,
)

/**
 * Display order of `[1.9]` records: the headphones' Shortcut (0x80), then the right bud (0x03),
 * the left bud (0x04), then anything else by id. The first is "Shortcut Button 1".
 */
private fun buttonRank(buttonId: Int): Int = when (buttonId) {
    0x80 -> 0
    0x03 -> 1
    0x04 -> 2
    else -> 10 + buttonId
}

/** The state with exactly [records] as its `[1.9]` records (a full GET), primary first. */
internal fun HeadphoneState.withButtonRecords(records: List<ButtonAssignment>): HeadphoneState {
    val ordered = records.distinctBy { it.buttonId }.sortedBy { buttonRank(it.buttonId) }
    return copy(shortcutButtons = ordered, shortcutButton = ordered.firstOrNull())
}

/**
 * One `[1.9]` record from a push or an echo, merged by button id so the other buds' records stay.
 * A short frame (3-byte echo, no masks) keeps the supported list the full read gave.
 */
internal fun HeadphoneState.withButtonRecord(record: ButtonAssignment): HeadphoneState {
    val old = allShortcutButtons
    val previous = old.firstOrNull { it.buttonId == record.buttonId }
    val merged = if (record.supportedActions.isEmpty() && previous != null) record.copy(supportedActions = previous.supportedActions) else record
    return withButtonRecords(old.filterNot { it.buttonId == record.buttonId } + merged)
}

internal fun VoicePromptsState.toVoicePrompts(): VoicePrompts = VoicePrompts(enabled = enabled, languageId = languageCode)

// ── Devices ──────────────────────────────────────────────────────────────────────────────

/** Mark the device at [activeMac] (if any) as the streaming source and clear the others. */
internal fun List<PairedDevice>.withActiveSource(activeMac: String?): List<PairedDevice> =
    map { it.copy(isActiveSource = activeMac != null && it.address.equals(activeMac, ignoreCase = true)) }

internal fun ClassicProfile.toBluetoothProfile(): BluetoothProfile = when (this) {
    ClassicProfile.A2DP -> BluetoothProfile.A2DP
    ClassicProfile.HFP -> BluetoothProfile.HFP
    ClassicProfile.AVRCP -> BluetoothProfile.AVRCP
    ClassicProfile.SPP -> BluetoothProfile.SPP
    ClassicProfile.IAP -> BluetoothProfile.IAP
}

/** `[4.6]` → [DeviceProfiles]. */
internal fun DeviceExtendedInfo.toDeviceProfiles(): DeviceProfiles = DeviceProfiles(
    classicPaired = pairedClassic.map { it.toBluetoothProfile() }.toSet(),
    classicConnected = connectedClassic.map { it.toBluetoothProfile() }.toSet(),
    leAudioPaired = leUnicastPaired,
    leAudioConnected = leUnicastConnected,
    leBroadcastPaired = leBroadcastPaired,
    leBroadcastConnected = leBroadcastConnected,
)

internal fun LeAudioCheckResult.toLePreference(): LeAudioPreference = LeAudioPreference(supported, preferred, prohibited)

/** `[4.4]` push: refresh the connected bits of the listed devices, adding unknown ones unnamed (the next refresh names them). */
internal fun List<PairedDevice>.withConnectedFrom(list: PairedDevices): List<PairedDevice> {
    val byMac = associateBy { it.address.uppercase() }
    return list.macs.map { mac ->
        (byMac[mac.uppercase()] ?: PairedDevice(address = mac, name = null)).copy(isConnected = list.isConnected(mac))
    }
}

/** Flag the entry matching [phoneMac] (`[4.9]`) as this phone; a null [phoneMac] leaves the flags alone. */
internal fun List<PairedDevice>.withThisPhone(phoneMac: String?): List<PairedDevice> =
    if (phoneMac == null) this else map { it.copy(isThisPhone = it.address.equals(phoneMac, ignoreCase = true)) }

// ── Errors ───────────────────────────────────────────────────────────────────────────────

/**
 * A device ERROR as a user-facing command failure. Error 5 is "this operator is not implemented
 * for that function" (the Bose app never writes with an operator that answers 5), so it reads as
 * "the firmware doesn't support changing this", never as a login problem.
 */
internal fun BmapException.toCommandException(): HeadphoneCommandException =
    if (operatorNotSupported) {
        HeadphoneCommandException(HeadphoneCommandException.FIRMWARE_UNSUPPORTED_MESSAGE, this).apply { notSupportedByFirmware = true }
    } else {
        HeadphoneCommandException(message ?: "The headphones refused the command", this)
    }

// ── Status / audio management ────────────────────────────────────────────────────────────

internal fun OperationModeCode.toOperationMode(): OperationMode = when (this) {
    OperationModeCode.UNKNOWN -> OperationMode.UNKNOWN
    OperationModeCode.NORMAL -> OperationMode.NORMAL
    OperationModeCode.OTA_ONLY -> OperationMode.FIRMWARE_UPDATE_ONLY
}

internal fun PlaybackStatus.toPlaybackState(): PlaybackState = when (this) {
    PlaybackStatus.STOPPED -> PlaybackState.STOPPED
    PlaybackStatus.PLAYING -> PlaybackState.PLAYING
    PlaybackStatus.PAUSED -> PlaybackState.PAUSED
    PlaybackStatus.FAST_FORWARDING -> PlaybackState.FAST_FORWARDING
    PlaybackStatus.REWINDING -> PlaybackState.REWINDING
}

/** `[5.4]` → [Playback]; null for a status code outside the table. */
internal fun AudioStatus.toPlayback(): Playback? = status?.let { Playback(it.toPlaybackState(), trackPosition) }

/** Core transport command → the `[5.3]` wire value. */
internal fun TransportControl.toWire(): WireTransportControl = when (this) {
    TransportControl.PLAY -> WireTransportControl.PLAY
    TransportControl.PAUSE -> WireTransportControl.PAUSE
    TransportControl.NEXT -> WireTransportControl.TRACK_FORWARD
    TransportControl.PREVIOUS -> WireTransportControl.TRACK_BACK
    TransportControl.STOP -> WireTransportControl.STOP
}

/** `[5.3]` supported bitset → the core commands it covers. */
internal fun transportControlsFrom(codes: Set<Int>): Set<TransportControl> =
    TransportControl.entries.filter { it.toWire().code in codes }.toSet()

/** `[2.12]` push → [ButtonPress], with Bose's names for the button and the press (null when unknown). */
internal fun ButtonConfig.toButtonPress(atMillis: Long): ButtonPress = ButtonPress(
    buttonId = buttonCode, eventType = eventCode, action = buttonActionFromCode(actionCode), atMillis = atMillis,
    buttonLabel = button?.label, eventLabel = event?.label,
)

internal fun HeadphoneState.withLatency(level: LatencyLevel): HeadphoneState =
    copy(latencyMs = level.current, latencyRangeMs = level.range)

internal fun HeadphoneState.withVolume(v: VolumeState): HeadphoneState = copy(volume = v.current, volumeMax = v.max)

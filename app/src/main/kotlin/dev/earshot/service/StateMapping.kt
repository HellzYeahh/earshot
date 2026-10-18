package dev.earshot.service

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
import dev.earshot.protocol.qcultra2.BoseModel
import dev.earshot.protocol.qcultra2.ButtonConfig
import dev.earshot.protocol.qcultra2.ClassicProfile
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
import dev.earshot.protocol.qcultra2.SidetoneState
import dev.earshot.protocol.qcultra2.VoicePromptsState
import dev.earshot.protocol.qcultra2.VolumeState
import dev.earshot.protocol.qcultra2.TransportControl as WireTransportControl

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

internal fun BoseModel.toCapabilities(): HeadphoneCapabilities = HeadphoneCapabilities(
    spatialAudio = hasSpatialAudio,
    ancToggle = hasNoiseCancelling && modeLayout.hasAncToggle,
    liveNoiseControl = hasLiveAudioSettings,
    windExcludesCancelling = windExcludesCnc,
    noiseCancelling = hasNoiseCancelling,
    ancAlwaysOn = hasNoiseCancelling && !modeLayout.hasAncToggle,
    editableModes = canEditModes,
    verified = verified,
)

// ── Listening modes [31.6] + [31.8] ──────────────────────────────────────────────────────

/**
 * One `[31.6]` slot as a [ListeningMode]. Presets always count as configured; a custom slot is
 * configured unless its name is blank or the "None" sentinel (bosectl's free-slot rule — the
 * firmware's own `configured` bit is set on first write and never cleared, so it is useless
 * for that). An empty slot gets an empty name and no settings, which is what the UI expects.
 */
internal fun ModeConfig.toListeningMode(favorite: Boolean, model: BoseModel = BoseModel.DEFAULT): ListeningMode {
    // A built-in slot the model does not name (Ultra Open) is only a mode if the firmware named it;
    // otherwise an unused "None" slot would show up in the carousel as a blank built-in mode.
    val preset = index in model.presetSlots && (index < model.presetNames.size || !isEmptySlot)
    val configured = preset || !isEmptySlot
    return ListeningMode(
        index = index,
        name = if (configured) name.trim() else "",
        isPreset = preset,
        // The SET_GET echo has no editable bit; fall back to the slot range. A model whose write
        // layout is unknown (Ultra Open) never offers editing, whatever the byte says.
        isEditable = model.canEditModes && (editable ?: (index in model.editableSlots)),
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
    index: Int, previous: ListeningMode?, favorite: Boolean, model: BoseModel = BoseModel.DEFAULT,
): ListeningMode {
    if (previous != null) return previous.copy(isFavorite = favorite)
    val preset = index in model.presetSlots
    val name = model.presetNames.getOrNull(index)
    // A built-in slot whose name is unknown (Ultra Open) stays hidden until the dump names it.
    val known = preset && name != null
    return ListeningMode(
        index = index,
        name = name ?: "",
        isPreset = known,
        isEditable = index in model.editableSlots,
        isConfigured = known,
        isFavorite = favorite,
        settings = null,
    )
}

/**
 * All [QcUltra2.MODE_SLOT_COUNT] slots in order. Slots missing from [configs] keep their entry
 * from [previous] (a partial dump must not wipe the list) or become placeholders. [favorites]
 * null means "not read": keep each slot's previous favourite flag.
 */
internal fun buildModeList(
    configs: Map<Int, ModeConfig>,
    favorites: Set<Int>?,
    previous: List<ListeningMode>,
    model: BoseModel = BoseModel.DEFAULT,
    slotCount: Int = model.slotCount,
): List<ListeningMode> {
    val before = previous.associateBy { it.index }
    // A dump may report more slots than the model's default (e.g. an unverified model).
    val count = maxOf(slotCount, (configs.keys.maxOrNull() ?: -1) + 1)
    return (0 until count).map { index ->
        val favorite = favorites?.contains(index) ?: (before[index]?.isFavorite ?: false)
        configs[index]?.toListeningMode(favorite, model) ?: placeholderMode(index, before[index], favorite, model)
    }
}

internal fun List<ListeningMode>.withFavorites(favorites: Set<Int>): List<ListeningMode> =
    map { it.copy(isFavorite = it.index in favorites) }

/** Replace (or insert, keeping index order) one slot, preserving its favourite flag. */
internal fun List<ListeningMode>.withSlot(config: ModeConfig, model: BoseModel = BoseModel.DEFAULT): List<ListeningMode> {
    val favorite = firstOrNull { it.index == config.index }?.isFavorite ?: false
    val updated = config.toListeningMode(favorite, model)
    return if (any { it.index == config.index }) map { if (it.index == config.index) updated else it }
    else (this + updated).sortedBy { it.index }
}

// ── Settings block ───────────────────────────────────────────────────────────────────────

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
internal fun SidetoneState.toSidetone(): Sidetone = Sidetone.fromWire(levelCode)

/** `[1.9]` → [ButtonAssignment]; action codes outside the enum map to [ButtonAction.UNKNOWN] and are dropped from the choices. */
internal fun ButtonConfig.toButtonAssignment(): ButtonAssignment = ButtonAssignment(
    buttonId = buttonCode,
    eventType = eventCode,
    action = ButtonAction.fromWire(actionCode),
    supportedActions = supportedActionCodes.sorted()
        .map { ButtonAction.fromWire(it) }
        .filter { it != ButtonAction.UNKNOWN }
        .distinct(),
)

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

/** `[2.12]` push → [ButtonPress]. */
internal fun ButtonConfig.toButtonPress(atMillis: Long): ButtonPress =
    ButtonPress(buttonId = buttonCode, eventType = eventCode, action = ButtonAction.fromWire(actionCode), atMillis = atMillis)

internal fun HeadphoneState.withLatency(level: LatencyLevel): HeadphoneState =
    copy(latencyMs = level.current, latencyRangeMs = level.range)

internal fun HeadphoneState.withVolume(v: VolumeState): HeadphoneState = copy(volume = v.current, volumeMax = v.max)

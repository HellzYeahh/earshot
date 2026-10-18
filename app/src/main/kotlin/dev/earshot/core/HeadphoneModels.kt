package dev.earshot.core

/**
 * UI-facing model of the headphones. Everything here is in *human* units; the protocol
 * layer converts to wire values (for example the noise-cancelling slider is inverted on
 * the wire: wire 0 = most cancelling, wire 10 = most ambient).
 */

enum class ConnectionStatus { NO_PERMISSION, NO_PAIRED_DEVICE, DISCONNECTED, CONNECTING, CONNECTED }

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    /** Human-readable reason for the last failure, if any. */
    val message: String? = null,
)

enum class SpatialAudio(val wire: Int, val label: String) {
    OFF(0, "Off"),
    ROOM(1, "Still"),   // fixed to room (Bose calls it "Still")
    HEAD(2, "Motion"),  // fixed to head (Bose calls it "Motion")
    ;
    companion object { fun fromWire(v: Int) = entries.firstOrNull { it.wire == v } ?: OFF }
}

enum class Sidetone(val wire: Int, val label: String) {
    OFF(0, "Off"), LOW(3, "Low"), MEDIUM(2, "Medium"), HIGH(1, "High");
    companion object { fun fromWire(v: Int) = entries.firstOrNull { it.wire == v } ?: OFF }
}

/** Live noise-control state, AudioModes [31.10]. */
data class NoiseSettings(
    /** 0..10 where 10 = maximum noise cancelling (UI scale; the wire is inverted). */
    val cancellingLevel: Int = 10,
    val ancEnabled: Boolean = true,
    val windBlock: Boolean = false,
    val spatialAudio: SpatialAudio = SpatialAudio.OFF,
) {
    init { require(cancellingLevel in 0..10) { "cancellingLevel must be 0..10" } }
}

/** One of the 11 listening-mode slots (0-3 presets, 4-10 custom). */
data class ListeningMode(
    val index: Int,
    val name: String,
    val isPreset: Boolean,
    val isEditable: Boolean,
    /** False for an empty custom slot. */
    val isConfigured: Boolean,
    val isFavorite: Boolean = false,
    /** Null until the mode config has been read. */
    val settings: NoiseSettings? = null,
    /**
     * Which settings of this slot the firmware lets the user change (`[31.6]` byte 41). Custom
     * slots report everything but auto-CNC; presets report less. Null when unknown (the slot was
     * only seen through a write echo, or the dump was not read): treat as "all".
     */
    val changeableSettings: Set<ModeSetting>? = null,
) {
    companion object {
        const val SLOT_COUNT = 11
        val PRESET_NAMES = listOf("Quiet", "Aware", "Immersion", "Cinema")
        const val QUIET = 0
        const val AWARE = 1
        const val IMMERSION = 2
        const val CINEMA = 3
        val CUSTOM_SLOTS = 4..10
    }
}

/** A per-mode setting that `[31.6]` can flag as changeable. */
enum class ModeSetting { CANCELLING_LEVEL, AUTO_CNC, SPATIAL_AUDIO, WIND_BLOCK, ANC }

/** The headphones' own 3-band EQ, Settings [1.7]. Each value -10..10. */
data class HeadphoneEq(val bass: Int = 0, val mid: Int = 0, val treble: Int = 0) {
    init {
        require(bass in -10..10 && mid in -10..10 && treble in -10..10) { "EQ bands must be -10..10" }
    }
}

data class PairedDevice(
    val address: String,
    val name: String?,
    /** True when this device is the one currently streaming audio to the headphones. */
    val isActiveSource: Boolean = false,
    /**
     * True for the phone running this app: the address matches `[4.9]` AppAddress (the phone as
     * the headphones see it), or `[4.5]` flagged the entry as the asking device.
     */
    val isThisPhone: Boolean = false,
    /** `[4.4]` connected bit: the device has a live link to the headphones right now. Null when unknown. */
    val isConnected: Boolean? = null,
    /** `[4.5]` flag: the device is itself a Bose product. Null when unknown. */
    val isBoseProduct: Boolean? = null,
    /** `[4.6]` per-profile bits (Classic vs LE Audio). Null until read; untested on hardware. */
    val profiles: DeviceProfiles? = null,
)

/** Bluetooth Classic profiles reported by `[4.6]`. */
enum class BluetoothProfile { A2DP, HFP, AVRCP, SPP, IAP }

/** `[4.6]` ExtendedInfo for one paired device. */
data class DeviceProfiles(
    val classicPaired: Set<BluetoothProfile> = emptySet(),
    val classicConnected: Set<BluetoothProfile> = emptySet(),
    val leAudioPaired: Boolean = false,
    val leAudioConnected: Boolean = false,
    val leBroadcastPaired: Boolean = false,
    val leBroadcastConnected: Boolean = false,
) {
    /** The device is linked over LE Audio (unicast) right now. */
    val usesLeAudio: Boolean get() = leAudioConnected
    /** The device is linked over Bluetooth Classic (any profile) right now. */
    val usesClassic: Boolean get() = classicConnected.isNotEmpty()
}

/** `[4.19]` LE Audio check: what the headphones think of LE Audio for this connection. */
data class LeAudioPreference(val supported: Boolean, val preferred: Boolean, val prohibited: Boolean)

/** `[5.4]` play state as the headphones see it. */
enum class PlaybackState { STOPPED, PLAYING, PAUSED, FAST_FORWARDING, REWINDING }

data class Playback(
    val state: PlaybackState,
    /** Track position from `[5.4]`; null when the headphones report it as unknown (they usually do). Unit not documented, probably seconds. */
    val trackPosition: Int? = null,
)

/** `[5.3]` transport commands. */
enum class TransportControl { PLAY, PAUSE, NEXT, PREVIOUS, STOP }

/** `[2.16]` firmware operation mode. */
enum class OperationMode { UNKNOWN, NORMAL, FIRMWARE_UPDATE_ONLY }

/** One button event pushed by the headphones (`[2.12]`, needs the `[9.2]` subscription). */
data class ButtonPress(
    val buttonId: Int,
    val eventType: Int,
    /** The action the firmware ran (or reports) for the press; [ButtonAction.UNKNOWN] if not in the table. */
    val action: ButtonAction,
    /** When the push arrived, wall clock; distinguishes two identical presses in a row. */
    val atMillis: Long,
)

/** A bonded Bluetooth device the user may pick as "my headphones". */
data class HeadphoneCandidate(
    val address: String,
    val name: String?,
    /** True when the name says Bose / QC / QuietComfort / Ultra. */
    val looksLikeBose: Boolean,
    /** True when the Bluetooth class is a headphone / headset / hands-free. */
    val isAudioDevice: Boolean,
)

data class DeviceInfo(
    val productName: String? = null,
    val customName: String? = null,
    val firmwareVersion: String? = null,
    val serialNumber: String? = null,
    val productId: Int? = null,
    val bluetoothAddress: String? = null,
    /** `[0.1]` BMAP protocol version, "1.2.0" on firmware 10.12. */
    val bmapVersion: String? = null,
    /** Marketing name derived from [productId], e.g. "QuietComfort 45 / QC SE". Null for an unknown id. */
    val modelName: String? = null,
    /** The product line without the generation, e.g. "QuietComfort Ultra Earbuds". Null for an unknown id. */
    val family: String? = null,
    /** "1st Gen" / "2nd Gen" when the product line has generations; null otherwise or when unknown. */
    val generation: String? = null,
)

/** Over-ear headphones, in-ear earbuds or open-ear earbuds: picks the icon, battery layout and wording. */
enum class FormFactor { HEADPHONES, EARBUDS, OPEN_EARBUDS }

/** One battery in the set: a level and, when the firmware estimates it, the listening time left. */
data class BatteryLevel(val percent: Int?, val minutesLeft: Int? = null)

/**
 * The batteries behind [HeadphoneState.batteryPercent], from the `[2.2]` records: headphones
 * report one ([overall]); earbuds report each bud and the case (ids 1 right, 2 left, 3 case,
 * 4 combined). Any part the firmware does not report is null.
 */
data class BatteryDetail(
    val overall: BatteryLevel? = null,
    val left: BatteryLevel? = null,
    val right: BatteryLevel? = null,
    val case: BatteryLevel? = null,
)

/** Whether the headphones need an encrypted session for protected controls, and how that went. */
enum class SecureSessionStatus {
    /** The headphones do not ask for one (every Bose model, and a Crusher that answers in the clear). */
    NOT_REQUIRED,

    /** The session is up; controls are encrypted. */
    SECURE,

    /** The user declined the pairing code. */
    DECLINED,

    /** The session could not be set up; [SecureSession.message] says why. Protected controls may not work. */
    FAILED,
}

data class SecureSession(val status: SecureSessionStatus = SecureSessionStatus.NOT_REQUIRED, val message: String? = null)

/**
 * A one-time pairing waiting for the user: the same [passcode] should be announced by the headphones,
 * and the user confirms they match (or cancels). [passcode] is null when the headphones ask only for a
 * gesture (zero digits).
 */
data class PairingRequest(val passcode: Int?, val digits: Int)

/**
 * The Skullcandy Crusher's haptic bass (the "Crusher" dial and its three feels).
 * [mode] is 0 = high, 1 = medium, 2 = low when [modeCount] is positive; null when the unit has no
 * modes. [intensityReadOnly] means the headphones ignore intensity writes (the dial is physical).
 */
data class CrusherBass(
    val active: Boolean,
    val intensity: Int,
    val maxIntensity: Int,
    val mode: Int? = null,
    val modeCount: Int = 0,
    val intensityReadOnly: Boolean = false,
)

/**
 * What the connected model can do, beyond "the GET answered". Defaults describe the QC Ultra 2,
 * so state built before the model is known (or by the fakes) behaves as before.
 */
data class HeadphoneCapabilities(
    /** Immersive / spatial audio (Still, Motion). Absent on the QC45, QC SE, QC Headphones and QC SC. */
    val spatialAudio: Boolean = true,
    /** Noise cancelling can be switched fully off. The older models only have a level. */
    val ancToggle: Boolean = true,
    /**
     * Noise settings can be changed live (`[31.10]`). False on the older models: there a change
     * rewrites the active custom mode, so it only works while one is selected.
     */
    val liveNoiseControl: Boolean = true,
    /** Wind block and a cancelling level cannot be combined (wind on stores level 0). */
    val windExcludesCancelling: Boolean = false,
    /** Any noise cancelling at all; false on open-ear earbuds (Ultra Open). */
    val noiseCancelling: Boolean = true,
    /**
     * Noise cancelling cannot be switched off from the noise panel because it is always on there
     * (QC45 family). False where "off" is a listening mode of its own (Skullcandy Crusher: Off /
     * Quiet / Aware), so the level slider is greyed while that mode is active.
     */
    val ancAlwaysOn: Boolean = false,
    /** A Wind Block switch exists. The Crusher's modes have no separate wind setting. */
    val windBlock: Boolean = true,
    /** Custom modes can be saved / renamed / reset. False where the write layout is unknown. */
    val editableModes: Boolean = true,
    /** Modes can be starred as favourites (the long-press on a mode chip). False on the Crusher. */
    val favoriteModes: Boolean = true,
    /**
     * The headphones expose listening modes at all. False on firmware that refuses the whole
     * AudioModes block (bosectl #44 saw FblockNotSupp on every `[31.x]` request): the UI hides the
     * mode carousel and editor, and the noise level (if readable) is shown read-only.
     */
    val listeningModes: Boolean = true,
    /** False while the model's layout is a best guess rather than confirmed on hardware. */
    val verified: Boolean = true,
    /** What kind of product this is (icon, battery layout, wording). */
    val formFactor: FormFactor = FormFactor.HEADPHONES,
    /**
     * User-visible note for products Earshot can only partly drive, shown on Home: a "basic"
     * model (NC 700, QC Earbuds 2020: battery, EQ, settings and noise level, no modes) or an
     * unsupported one (QuietComfort 35 / 35 II, the 2024 QC Earbuds). Null for fully supported models.
     */
    val supportNote: String? = null,
)

enum class ButtonAction(val wire: Int, val label: String) {
    NOT_CONFIGURED(0, "Not configured"),
    VOICE_ASSISTANT(1, "Voice assistant"),
    ANC_CYCLE(2, "Cycle noise control"),
    BATTERY_LEVEL(3, "Battery level"),
    PLAY_PAUSE(4, "Play / pause"),
    INCREASE_CNC(5, "Increase noise cancelling"),
    DECREASE_CNC(6, "Decrease noise cancelling"),
    TOGGLE_WAKE_WORD(7, "Toggle wake word"),
    SWITCH_DEVICE(8, "Switch device"),
    CONVERSATION_MODE(9, "Conversation mode"),
    TRACK_FORWARD(10, "Next track"),
    TRACK_BACK(11, "Previous track"),
    FETCH_NOTIFICATIONS(12, "Read notifications"),
    WIND_MODE(13, "Wind block"),
    DISABLED(14, "Disabled"),
    CLIENT_INTERACTION(15, "App shortcut"),
    SPOTIFY_TAP(16, "Spotify Tap"),
    MODES_CAROUSEL(17, "Cycle modes"),
    SPATIAL_AUDIO(19, "Immersive audio"),
    LINE_IN_SWITCH(20, "Line-in switch"),
    LINKING(21, "Linking"),
    UNKNOWN(-1, "Unknown"),
    ;
    companion object { fun fromWire(v: Int) = entries.firstOrNull { it.wire == v } ?: UNKNOWN }
}

/** Settings [1.9]. On the QC Ultra 2 the only remappable control is the Shortcut button (0x80). */
data class ButtonAssignment(
    val buttonId: Int,
    val eventType: Int,
    val action: ButtonAction,
    val supportedActions: List<ButtonAction>,
)

data class VoicePrompts(val enabled: Boolean, val languageId: Int)

data class HeadphoneState(
    val connection: ConnectionState = ConnectionState(),
    val batteryPercent: Int? = null,
    val isCharging: Boolean? = null,
    val currentModeIndex: Int? = null,
    val modes: List<ListeningMode> = emptyList(),
    val noise: NoiseSettings? = null,
    val eq: HeadphoneEq? = null,
    val sidetone: Sidetone? = null,
    val sidetonePersistent: Boolean? = null,
    val autoPlayPause: Boolean? = null,
    val autoAnswer: Boolean? = null,
    val multipointEnabled: Boolean? = null,
    val voicePrompts: VoicePrompts? = null,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val info: DeviceInfo? = null,
    val shortcutButton: ButtonAssignment? = null,
    /** True while a command is in flight, for disabling controls. */
    val isBusy: Boolean = false,
    val lastUpdatedMillis: Long = 0L,

    // ---- Added 2026-10-17 from the Bose app 13.3.4 tables (all null = not read / not supported) ----

    /** `[2.21]` MicMute, read-only. `true` on fw 10.12; whether it means "muted now" is unverified. */
    val micMuted: Boolean? = null,
    /** `[2.16]` NORMAL in everyday use; FIRMWARE_UPDATE_ONLY while an update is pending. */
    val operationMode: OperationMode? = null,
    /** `[0.23]` the firmware can do LE Audio (true on 10.12, false on 8.2.20). */
    val leAudioSupported: Boolean? = null,
    /** `[1.37]` LE Audio switched on in the headphones. See [HeadphoneRepository.setLeAudioEnabled]. */
    val leAudioEnabled: Boolean? = null,
    /** `[4.19]` LE Audio check, read once per connection. Untested on hardware. */
    val lePreference: LeAudioPreference? = null,
    /** `[5.7]` current latency, milliseconds (unit inferred). Possibly a live measurement rather than a set-point. */
    val latencyMs: Int? = null,
    /** `[5.7]` min..max the firmware reports (0..520 on fw 10.12). */
    val latencyRangeMs: IntRange? = null,
    /** `[1.34]` touch controls on (the wire flag is "disable captouch", inverted here). fw 10.12 reads false. */
    val touchControlsEnabled: Boolean? = null,
    /** `[5.5]` absolute headphone volume, 0..[volumeMax]. */
    val volume: Int? = null,
    /** `[5.5]` maximum volume step (31 on fw 10.12). */
    val volumeMax: Int? = null,
    /** `[5.4]` play state. */
    val playback: Playback? = null,
    /** `[5.3]` commands the headphones accept (fw 10.12: all five). */
    val supportedTransportControls: Set<TransportControl> = emptySet(),
    /** `[4.18]` the headphones can take another source connection (inferred meaning). */
    val availableToConnect: Boolean? = null,
    /** `[4.9]` AppAddress: this phone's Bluetooth address as the headphones see it. */
    val thisPhoneAddress: String? = null,
    /**
     * True once `[9.2]` notifications are on for this connection: battery, mode, volume, play
     * state, button events and so on then arrive by themselves. False = Earshot falls back to a
     * slow battery poll and the rest updates only on refresh.
     */
    val livePushEnabled: Boolean = false,
    /** Last `[2.12]` button event pushed by the headphones (needs [livePushEnabled]). */
    val lastButtonPress: ButtonPress? = null,
    /** What the connected model supports; QC Ultra 2 defaults until `[0.3]` is read. */
    val capabilities: HeadphoneCapabilities = HeadphoneCapabilities(),
    /** Haptic bass of the Skullcandy Crusher headphones; null on every other model. */
    val crusher: CrusherBass? = null,
    /** Secure session with headphones that require one (Crusher); [SecureSessionStatus.NOT_REQUIRED] otherwise. */
    val secureSession: SecureSession = SecureSession(),
    /** A pairing waiting for the user's answer, or null. See [HeadphoneRepository.answerPairing]. */
    val pairing: PairingRequest? = null,

    // ---- Added for 0.4.0 (model-aware UI) ----

    /** Per-part batteries and time left; null until read. [batteryPercent] stays the headline figure. */
    val battery: BatteryDetail? = null,
    /**
     * `[1.4]` auto-off (standby) timer in minutes, 0 = never; null when the headphones do not
     * report one. See [HeadphoneRepository.setAutoOff].
     */
    val autoOffMinutes: Int? = null,
    /** `[1.7]` band range the headphones report (min..max per band); null = the usual -10..10. */
    val eqRange: IntRange? = null,
) {
    val isConnected: Boolean get() = connection.status == ConnectionStatus.CONNECTED
    val currentMode: ListeningMode? get() = currentModeIndex?.let { i -> modes.firstOrNull { it.index == i } }
    val favoriteModes: List<ListeningMode> get() = modes.filter { it.isFavorite }
}

/** One-shot notifications from the repository (snackbars, logs). */
sealed interface HeadphoneEvent {
    data class Error(val message: String, val cause: Throwable? = null) : HeadphoneEvent
    data class Info(val message: String) : HeadphoneEvent
    data object Connected : HeadphoneEvent
    data object Disconnected : HeadphoneEvent
}

/** Thrown by repository commands that fail; the message is safe to show to the user. */
class HeadphoneCommandException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * True when the headphones answered BMAP error 5: the setting exists but the firmware does
     * not implement changing it (not a login gate — the Bose app 13.3.4 has no such gate). The
     * message then reads [FIRMWARE_UNSUPPORTED_MESSAGE].
     */
    var notSupportedByFirmware: Boolean = false

    @Deprecated("Error 5 means the firmware doesn't support the change, not a Bose login", ReplaceWith("notSupportedByFirmware"))
    var needsBoseAuthentication: Boolean
        get() = notSupportedByFirmware
        set(value) { notSupportedByFirmware = value }

    companion object {
        const val FIRMWARE_UNSUPPORTED_MESSAGE = "The headphones' firmware doesn't support changing this"
    }
}

/** A raw frame, for the Lab explorer. */
data class RawFrame(
    val block: Int,
    val function: Int,
    val operatorName: String,
    val payloadHex: String,
    val isError: Boolean,
    val errorName: String? = null,
)

/** One read-only observation from the Lab scanner. */
data class LabReading(
    val block: Int,
    val function: Int,
    val knownName: String?,
    val frames: List<RawFrame>,
)

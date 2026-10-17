package dev.earshot.core.fake

import dev.earshot.core.BluetoothProfile
import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.DeviceInfo
import dev.earshot.core.DeviceProfiles
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneCandidate
import dev.earshot.core.HeadphoneState
import dev.earshot.core.LabReading
import dev.earshot.core.LeAudioPreference
import dev.earshot.core.ListeningMode
import dev.earshot.core.NoiseSettings
import dev.earshot.core.OperationMode
import dev.earshot.core.PairedDevice
import dev.earshot.core.Playback
import dev.earshot.core.PlaybackState
import dev.earshot.core.RawFrame
import dev.earshot.core.Sidetone
import dev.earshot.core.SpatialAudio
import dev.earshot.core.TransportControl
import dev.earshot.core.VoicePrompts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * In-memory stand-in for the real headphones. Used by Compose previews, by the UI while the
 * Bluetooth layer is being built, and by unit tests. Behaves plausibly: connecting takes a
 * moment, commands take a little time, state updates after each command.
 */
class FakeHeadphoneRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val commandDelayMillis: Long = 150,
    startConnected: Boolean = false,
) : HeadphoneRepository {

    private val _state = MutableStateFlow(if (startConnected) connectedState() else HeadphoneState())
    override val state: StateFlow<HeadphoneState> = _state

    private val _events = MutableSharedFlow<HeadphoneEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<HeadphoneEvent> = _events

    private var connectJob: Job? = null

    override fun connect() {
        if (_state.value.isConnected || connectJob?.isActive == true) return
        connectJob = scope.launch {
            _state.update { it.copy(connection = ConnectionState(ConnectionStatus.CONNECTING, DEVICE_NAME, DEVICE_ADDRESS)) }
            delay(800)
            _state.value = connectedState()
            _events.tryEmit(HeadphoneEvent.Connected)
        }
    }

    override fun disconnect() {
        connectJob?.cancel()
        _state.update { it.copy(connection = ConnectionState(ConnectionStatus.DISCONNECTED, DEVICE_NAME, DEVICE_ADDRESS)) }
        _events.tryEmit(HeadphoneEvent.Disconnected)
    }

    override suspend fun deviceCandidates(): List<HeadphoneCandidate> = listOf(
        HeadphoneCandidate(DEVICE_ADDRESS, DEVICE_NAME, looksLikeBose = true, isAudioDevice = true),
        HeadphoneCandidate("AA:BB:CC:DD:EE:03", "Car stereo", looksLikeBose = false, isAudioDevice = true),
    )

    override suspend fun chooseDevice(address: String, name: String?) = connect()

    override suspend fun refresh() {
        requireConnected()
        command { it.copy(lastUpdatedMillis = System.currentTimeMillis()) }
    }

    override suspend fun setMode(index: Int) {
        requireConnected()
        val mode = _state.value.modes.firstOrNull { it.index == index }
            ?: throw HeadphoneCommandException("No mode in slot $index")
        if (!mode.isConfigured) throw HeadphoneCommandException("Slot $index is empty")
        command { s -> s.copy(currentModeIndex = index, noise = mode.settings ?: s.noise) }
    }

    override suspend fun saveCustomMode(index: Int, name: String, settings: NoiseSettings) {
        requireConnected()
        if (index !in ListeningMode.CUSTOM_SLOTS) throw HeadphoneCommandException("Presets can't be edited")
        command { s ->
            s.copy(modes = s.modes.map { m ->
                if (m.index == index) m.copy(name = name, isConfigured = true, settings = settings) else m
            })
        }
    }

    override suspend fun setFavorites(indices: Set<Int>) {
        requireConnected()
        command { s -> s.copy(modes = s.modes.map { it.copy(isFavorite = it.index in indices) }) }
    }

    override suspend fun setNoise(settings: NoiseSettings) {
        requireConnected()
        command { it.copy(noise = settings) }
    }

    override suspend fun setEq(eq: HeadphoneEq) { requireConnected(); command { it.copy(eq = eq) } }

    override suspend fun setSidetone(level: Sidetone, persistent: Boolean) {
        requireConnected(); command { it.copy(sidetone = level, sidetonePersistent = persistent) }
    }

    override suspend fun setAutoPlayPause(enabled: Boolean) { requireConnected(); command { it.copy(autoPlayPause = enabled) } }
    override suspend fun setAutoAnswer(enabled: Boolean) { requireConnected(); command { it.copy(autoAnswer = enabled) } }
    override suspend fun setMultipoint(enabled: Boolean) { requireConnected(); command { it.copy(multipointEnabled = enabled) } }
    override suspend fun setVoicePrompts(prompts: VoicePrompts) { requireConnected(); command { it.copy(voicePrompts = prompts) } }

    override suspend fun setShortcutButton(action: ButtonAction) {
        requireConnected()
        command { s -> s.copy(shortcutButton = s.shortcutButton?.copy(action = action)) }
    }

    override suspend fun setDeviceName(name: String) {
        requireConnected()
        // Mirrors the virtual unit: [1.2] SET_GET answers error 5 (not implemented by the firmware).
        throw HeadphoneCommandException(HeadphoneCommandException.FIRMWARE_UNSUPPORTED_MESSAGE).apply { notSupportedByFirmware = true }
    }

    override suspend fun switchToDevice(address: String) {
        requireConnected()
        command { s -> s.copy(pairedDevices = s.pairedDevices.map { it.copy(isActiveSource = it.address == address) }) }
    }

    override suspend fun resetCustomMode(index: Int) {
        requireConnected()
        if (index !in ListeningMode.CUSTOM_SLOTS) throw HeadphoneCommandException("Only custom slots can be reset")
        command { s -> s.copy(modes = s.modes.map { if (it.index == index) it.copy(name = "", isConfigured = false, settings = null, isFavorite = false) else it }) }
    }

    override suspend fun setLeAudioEnabled(enabled: Boolean) {
        requireConnected()
        command { it.copy(leAudioEnabled = enabled) }
        // The real headphones restart: drop and come back like the manager's reconnect would.
        _state.update { it.copy(connection = it.connection.copy(status = ConnectionStatus.CONNECTING)) }
        scope.launch {
            delay(1_500)
            _state.update { it.copy(connection = it.connection.copy(status = ConnectionStatus.CONNECTED)) }
            _events.tryEmit(HeadphoneEvent.Connected)
        }
    }

    override suspend fun setLatencyMs(ms: Int) {
        requireConnected()
        val range = _state.value.latencyRangeMs ?: 0..520
        if (ms !in range) throw HeadphoneCommandException("Latency must be ${range.first} to ${range.last} ms")
        command { it.copy(latencyMs = ms) }
    }

    override suspend fun setTouchControls(enabled: Boolean) { requireConnected(); command { it.copy(touchControlsEnabled = enabled) } }

    override suspend fun setVolume(level: Int) {
        requireConnected()
        val max = _state.value.volumeMax ?: 31
        if (level !in 0..max) throw HeadphoneCommandException("Volume must be 0 to $max")
        command { it.copy(volume = level) }
    }

    override suspend fun transport(control: TransportControl) {
        requireConnected()
        command { s ->
            when (control) {
                TransportControl.PLAY -> s.copy(playback = Playback(PlaybackState.PLAYING))
                TransportControl.PAUSE -> s.copy(playback = Playback(PlaybackState.PAUSED))
                TransportControl.STOP -> s.copy(playback = Playback(PlaybackState.STOPPED))
                TransportControl.NEXT, TransportControl.PREVIOUS -> s
            }
        }
    }

    override suspend fun printConsole(command: String): String {
        requireConnected()
        delay(commandDelayMillis / 3)
        val verb = command.trim().substringBefore(' ').lowercase()
        if (verb !in setOf("bind", "unbind", "verbosity", "output")) {
            throw HeadphoneCommandException("Unknown console command. Use bind, unbind, verbosity or output.")
        }
        return "[22.3] ERROR OP_NOT_SUPPORTED (the fake headphones have no debug console)"
    }

    override suspend fun enterPairingMode() { requireConnected(); command { it }; _events.tryEmit(HeadphoneEvent.Info("Pairing mode on")) }

    override suspend fun powerOff() {
        requireConnected()
        delay(commandDelayMillis)
        disconnect()
    }

    override suspend fun readRaw(block: Int, function: Int): List<RawFrame> {
        requireConnected()
        delay(commandDelayMillis / 3)
        return listOf(
            if ((block + function) % 5 == 0) RawFrame(block, function, "ERROR", "04", true, "FUNC_NOT_SUPPORTED")
            else RawFrame(block, function, "STATUS", "%02x%02x".format(block, function), false),
        )
    }

    override fun scanFunctions(blocks: List<Int>, maxFunction: Int): Flow<LabReading> = flow {
        for (b in blocks) for (f in 0..maxFunction) {
            emit(LabReading(b, f, knownName = KNOWN_NAMES[b to f], frames = readRaw(b, f)))
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun requireConnected() {
        if (!_state.value.isConnected) throw HeadphoneCommandException("Headphones not connected")
    }

    private suspend fun command(transform: (HeadphoneState) -> HeadphoneState) {
        _state.update { it.copy(isBusy = true) }
        delay(commandDelayMillis)
        _state.update { transform(it).copy(isBusy = false, lastUpdatedMillis = System.currentTimeMillis()) }
    }

    companion object {
        const val DEVICE_NAME = "Bose QC Ultra Headphones"
        const val DEVICE_ADDRESS = "68:F2:1F:12:34:56"

        val KNOWN_NAMES = mapOf(
            (0 to 5) to "Firmware version", (1 to 7) to "EQ", (2 to 2) to "Battery",
            (31 to 3) to "Current mode", (31 to 10) to "Noise settings",
        )

        fun connectedState(): HeadphoneState {
            val quiet = NoiseSettings(10, true, false, SpatialAudio.OFF)
            val aware = NoiseSettings(0, true, false, SpatialAudio.OFF)
            val modes = listOf(
                ListeningMode(0, "Quiet", isPreset = true, isEditable = false, isConfigured = true, isFavorite = true, settings = quiet),
                ListeningMode(1, "Aware", isPreset = true, isEditable = false, isConfigured = true, isFavorite = true, settings = aware),
                ListeningMode(2, "Immersion", isPreset = true, isEditable = false, isConfigured = true, isFavorite = true, settings = quiet.copy(spatialAudio = SpatialAudio.HEAD)),
                ListeningMode(3, "Cinema", isPreset = true, isEditable = false, isConfigured = true, settings = quiet.copy(spatialAudio = SpatialAudio.ROOM)),
                ListeningMode(4, "Home", isPreset = false, isEditable = true, isConfigured = true, settings = NoiseSettings(4, true, false, SpatialAudio.OFF)),
            ) + (5..10).map { ListeningMode(it, "", isPreset = false, isEditable = true, isConfigured = false) }
            return HeadphoneState(
                connection = ConnectionState(ConnectionStatus.CONNECTED, DEVICE_NAME, DEVICE_ADDRESS),
                batteryPercent = 72,
                isCharging = false,
                currentModeIndex = 0,
                modes = modes,
                noise = quiet,
                eq = HeadphoneEq(2, 0, -1),
                sidetone = Sidetone.MEDIUM,
                sidetonePersistent = true,
                autoPlayPause = true,
                autoAnswer = false,
                multipointEnabled = true,
                voicePrompts = VoicePrompts(true, 1),
                pairedDevices = listOf(
                    PairedDevice("AA:BB:CC:DD:EE:01", "Galaxy S24", isActiveSource = true, isThisPhone = true, isConnected = true,
                        isBoseProduct = false, profiles = DeviceProfiles(
                            classicPaired = setOf(BluetoothProfile.A2DP, BluetoothProfile.HFP, BluetoothProfile.AVRCP),
                            classicConnected = setOf(BluetoothProfile.A2DP, BluetoothProfile.HFP, BluetoothProfile.AVRCP),
                            leAudioPaired = true,
                        )),
                    PairedDevice("AA:BB:CC:DD:EE:02", "Work laptop", isConnected = false, isBoseProduct = false),
                ),
                info = DeviceInfo(
                    productName = "Bose QC Ultra 2 HP", customName = "Fargo",
                    firmwareVersion = "8.2.20", serialNumber = "085958T00000000", productId = 0x4082,
                    bluetoothAddress = DEVICE_ADDRESS,
                ),
                shortcutButton = ButtonAssignment(
                    // Real firmware 10.12: the shortcut button offers these four (Bose app menu).
                    buttonId = 0x80, eventType = 9, action = ButtonAction.SPATIAL_AUDIO,
                    supportedActions = listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.DISABLED, ButtonAction.SPOTIFY_TAP, ButtonAction.SPATIAL_AUDIO),
                ),
                lastUpdatedMillis = System.currentTimeMillis(),
                // Values from the fw 10.12 deep scan (2026-10-17).
                micMuted = true,
                operationMode = OperationMode.NORMAL,
                leAudioSupported = true,
                leAudioEnabled = true,
                lePreference = LeAudioPreference(supported = true, preferred = false, prohibited = false),
                latencyMs = 210,
                latencyRangeMs = 0..520,
                touchControlsEnabled = false,
                volume = 22,
                volumeMax = 31,
                playback = Playback(PlaybackState.PLAYING),
                supportedTransportControls = TransportControl.entries.toSet(),
                availableToConnect = true,
                thisPhoneAddress = "AA:BB:CC:DD:EE:01",
                livePushEnabled = true,
            )
        }
    }
}

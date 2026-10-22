package dev.earshot.service

import android.content.Context
import android.util.Log
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.ConnectionManager
import dev.earshot.bt.DeviceCandidate
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
import dev.earshot.bt.SoundcoreConnection
import dev.earshot.bt.SoundcoreLinkFactory
import dev.earshot.core.ButtonAction
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCandidate
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.LabReading
import dev.earshot.core.NoiseSettings
import dev.earshot.core.RawFrame
import dev.earshot.core.Sidetone
import dev.earshot.core.VoicePrompts
import dev.earshot.protocol.soundcore.D1301State
import dev.earshot.protocol.soundcore.SoundcoreCommands
import dev.earshot.protocol.soundcore.SoundcorePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [HeadphoneRepository] for Soundcore earbuds; the Sleep A30 (D1301) for now. The same
 * [ConnectionManager] policy as Bose and the Crusher (reconnects, Bluetooth on/off, "close the
 * soundcore app"), over [SoundcoreConnection]:
 *  - connect: read the state (`01 01`), map it ([SoundcoreStateMapping]);
 *  - pushes: full state `01 01` and battery `01 03` arrive by themselves; the state is also re-read
 *    once a minute in case a model doesn't push;
 *  - commands: noise cancelling on/off (`06 87`, through the two listening modes), tap controls
 *    (`10 94`). Each re-reads the state afterwards, so the screen shows what the earbuds did.
 * Everything else throws a readable [HeadphoneCommandException]: Tools let anyone try a setting.
 */
class SoundcoreHeadphoneRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    demoTransport: () -> (() -> ByteTransport)? = { null },
    autoConnect: () -> Boolean = { true },
) : HeadphoneRepository {

    private val appContext = context.applicationContext

    val devicePrefs = DevicePrefs(appContext)
    val deviceFinder = DeviceFinder(appContext, devicePrefs)
    val connectionManager = ConnectionManager(
        appContext, deviceFinder, devicePrefs, scope, SoundcoreLinkFactory(),
        autoConnect = autoConnect,
        demoTransport = demoTransport,
        demoName = { DEMO_NAME },
    )

    private val _state = MutableStateFlow(HeadphoneState())
    override val state: StateFlow<HeadphoneState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HeadphoneEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<HeadphoneEvent> = _events.asSharedFlow()

    private val commands = Mutex()

    init {
        scope.launch {
            var wasConnected = false
            connectionManager.state.collect { cs ->
                val connected = cs.status == ConnectionStatus.CONNECTED
                _state.update { s -> if (connected || !wasConnected) s.copy(connection = cs) else HeadphoneState(connection = cs) }
                if (wasConnected && !connected) _events.tryEmit(HeadphoneEvent.Disconnected)
                wasConnected = connected
            }
        }
        scope.launch {
            connectionManager.connection.collectLatest { conn -> if (conn != null) runSession(conn) }
        }
    }

    // ---- Connection ------------------------------------------------------------------------

    override fun connect() {
        if (deviceFinder.hasConnectPermission()) HeadphoneService.start(appContext)
        connectionManager.connect()
    }

    override fun disconnect() {
        connectionManager.disconnect()
        HeadphoneService.stop(appContext)
    }

    /** Drop the connection without stopping the foreground service: another repository is taking over. */
    fun releaseConnection() = connectionManager.disconnect()

    override suspend fun deviceCandidates(): List<HeadphoneCandidate> = deviceFinder.candidates().map { it.toCandidate() }

    private fun DeviceCandidate.toCandidate() =
        HeadphoneCandidate(address = address, name = name, looksLikeBose = looksLikeBose, isAudioDevice = isAudioDevice)

    override suspend fun chooseDevice(address: String, name: String?) {
        HeadphoneService.start(appContext)
        connectionManager.chooseDevice(address, name)
    }

    private suspend fun runSession(conn: SoundcoreConnection) = coroutineScope {
        launch {
            conn.unsolicited.collect { packet -> applyPush(packet) }
        }
        try {
            readState(conn)
        } catch (e: HeadphoneCommandException) {
            Log.w(TAG, "initial read failed: ${e.message}")
            _events.tryEmit(HeadphoneEvent.Error("Connected, but reading the earbuds failed: ${e.message}", e))
        }
        _events.tryEmit(HeadphoneEvent.Connected)
        launch {
            while (conn.isOpen) {
                delay(STATE_POLL_MS)
                if (!conn.isOpen) break
                runCatching { commands.withLock { readState(conn) } }.onFailure { Log.d(TAG, "state poll failed: ${it.message}") }
            }
        }
    }

    private suspend fun readState(conn: SoundcoreConnection) {
        val reply = conn.request(SoundcoreCommands.REQUEST_STATE)
        applyState(reply.body)
    }

    private fun applyState(body: ByteArray) {
        val parsed = D1301State.parse(body)
        if (parsed == null) {
            Log.w(TAG, "state body of ${body.size} bytes is not the Sleep A30 layout")
            return
        }
        // No serial here: on the Sleep A30 it embeds the earbuds' address, already in the log anyway.
        Log.d(TAG, "state: anc=${parsed.ancOn} battery=${parsed.leftPercent}/${parsed.rightPercent} fw=${parsed.firmware} tapsOff=${parsed.tapControlsDisabled}")
        _state.update { SoundcoreStateMapping.apply(it, parsed) }
    }

    private fun applyPush(packet: SoundcorePacket) {
        when (packet.command) {
            SoundcoreCommands.REQUEST_STATE -> applyState(packet.body)
            SoundcoreCommands.PUSH_BATTERY -> D1301State.parseBattery(packet.body)?.let { (l, r) ->
                _state.update { SoundcoreStateMapping.withBattery(it, l, r) }
            }
            else -> Log.d(TAG, "push ${SoundcorePacket.commandName(packet.command)} ignored")
        }
    }

    /** One command at a time; afterwards the state is re-read so the UI shows what the earbuds did. */
    private suspend fun write(name: String, command: Int, value: Int) = write(name, command, byteArrayOf(value.toByte()))

    private suspend fun write(name: String, command: Int, body: ByteArray) = commands.withLock {
        val conn = connectionManager.connection.value ?: throw HeadphoneCommandException("Earbuds not connected")
        _state.update { it.copy(isBusy = true) }
        try {
            Log.i(TAG, "$name → ${SoundcorePacket.commandName(command)} ${body.joinToString("") { "%02x".format(it) }}")
            conn.request(command, body)
            readState(conn)
        } finally {
            _state.update { it.copy(isBusy = false) }
        }
    }

    // ---- What the Sleep A30 has ------------------------------------------------------------

    override suspend fun refresh() {
        val conn = connectionManager.connection.value ?: throw HeadphoneCommandException("Earbuds not connected")
        commands.withLock { readState(conn) }
    }

    override suspend fun setMode(index: Int) = when (index) {
        SoundcoreStateMapping.MODE_ANC -> write("noise cancelling", SoundcoreCommands.SET_ANC_D1301, 1)
        SoundcoreStateMapping.MODE_OFF -> write("noise cancelling", SoundcoreCommands.SET_ANC_D1301, 0)
        else -> throw HeadphoneCommandException("The Sleep A30 has two modes: noise cancelling and off")
    }

    override suspend fun setNoise(settings: NoiseSettings) =
        write("noise cancelling", SoundcoreCommands.SET_ANC_D1301, if (settings.ancEnabled) 1 else 0)

    override suspend fun setTouchControls(enabled: Boolean) =
        write("tap controls", SoundcoreCommands.SET_TAP_DISABLED, if (enabled) 0 else 1)

    override suspend fun setVendorControl(id: String, value: Int) {
        val (command, body) = SoundcoreStateMapping.write(id, value)
            ?: throw HeadphoneCommandException("The Sleep A30 has no setting \"$id\"")
        write(id, command, body)
    }

    // ---- What it doesn't have (or Earshot doesn't drive yet) --------------------------------

    override suspend fun saveCustomMode(index: Int, name: String, settings: NoiseSettings) = unsupported()
    override suspend fun setFavorites(indices: Set<Int>) = unsupported()
    override suspend fun setEq(eq: HeadphoneEq) = unsupported()
    override suspend fun setSidetone(level: Sidetone, persistent: Boolean) = unsupported()
    override suspend fun setAutoPlayPause(enabled: Boolean) = unsupported()
    override suspend fun setAutoAnswer(enabled: Boolean) = unsupported()
    override suspend fun setMultipoint(enabled: Boolean) = unsupported()
    override suspend fun setVoicePrompts(prompts: VoicePrompts) = unsupported()
    override suspend fun setShortcutButton(action: ButtonAction) = unsupported()
    override suspend fun setDeviceName(name: String) = unsupported()
    override suspend fun switchToDevice(address: String) = unsupported()
    override suspend fun enterPairingMode() = unsupported()
    override suspend fun powerOff() = unsupported()
    override suspend fun readRaw(block: Int, function: Int): List<RawFrame> = unsupported()
    override fun scanFunctions(blocks: List<Int>, maxFunction: Int): Flow<LabReading> = emptyFlow()

    private fun unsupported(): Nothing = throw HeadphoneCommandException(UNSUPPORTED)

    companion object {
        private const val TAG = "Earshot/Soundcore"
        const val DEMO_NAME = "Virtual Sleep A30 (demo)"
        const val UNSUPPORTED = "Earshot's Soundcore driver doesn't support that setting on these earbuds yet"
        private const val STATE_POLL_MS = 60_000L
    }
}

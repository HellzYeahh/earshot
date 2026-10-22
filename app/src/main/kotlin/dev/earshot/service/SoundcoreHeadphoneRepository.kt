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
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.NoiseSettings
import dev.earshot.core.Sidetone
import dev.earshot.core.VoicePrompts
import dev.earshot.protocol.soundcore.AmbientMode
import dev.earshot.protocol.soundcore.D1301State
import dev.earshot.protocol.soundcore.SoundModes
import dev.earshot.protocol.soundcore.SoundcoreCommands
import dev.earshot.protocol.soundcore.SoundcoreModel
import dev.earshot.protocol.soundcore.SoundcorePacket
import dev.earshot.protocol.soundcore.SoundcoreState
import dev.earshot.protocol.soundcore.SoundcoreStateReader
import dev.earshot.protocol.soundcore.SoundcoreWriteRefused
import dev.earshot.protocol.soundcore.SoundcoreWrites
import dev.earshot.service.lab.SoundcoreLab
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [HeadphoneRepository] for Soundcore earbuds and headphones. The same [ConnectionManager] policy
 * as Bose and the Crusher (reconnects, Bluetooth on/off, "close the soundcore app"), over
 * [SoundcoreConnection]. Two paths share it:
 *  - the **Sleep A30** (D1301, tested on a real pair), unchanged: noise cancelling on/off
 *    (`06 87`, through two listening modes), tap controls (`10 94`) and the sleep options;
 *  - **every other model on the vendor service** (the table in [SoundcoreModel]): the state
 *    (`01 01`) is read through [SoundcoreStateReader] for battery, charging, firmware and mode;
 *    models whose layout the notes document and whose state matches it (size, length byte,
 *    serial) can also change their ambient mode, noise-cancelling type and level, wind and
 *    settings ([SoundcoreWrites]). Anything else is read-only and says why.
 * Pushes (`01 01`, battery `01 03`, charging `01 04`, modes `06 01`) arrive by themselves; the
 * state is also re-read once a minute. Every write re-reads the state, so the screen shows what the
 * earbuds did; a model that acknowledges and ignores a write is reported as such.
 * Everything unsupported throws a readable [HeadphoneCommandException]: Tools let anyone try a setting.
 */
class SoundcoreHeadphoneRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    demoTransport: () -> (() -> ByteTransport)? = { null },
    autoConnect: () -> Boolean = { true },
    demoName: () -> String = { DEMO_NAME },
) : HeadphoneRepository {

    private val appContext = context.applicationContext

    val devicePrefs = DevicePrefs(appContext)
    val deviceFinder = DeviceFinder(appContext, devicePrefs)
    val connectionManager = ConnectionManager(
        appContext, deviceFinder, devicePrefs, scope, SoundcoreLinkFactory(),
        autoConnect = autoConnect,
        demoTransport = demoTransport,
        demoName = demoName,
    )

    private val _state = MutableStateFlow(HeadphoneState())
    override val state: StateFlow<HeadphoneState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HeadphoneEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<HeadphoneEvent> = _events.asSharedFlow()

    private val commands = Mutex()

    /** The model of the session in progress: the Sleep A30 unless the connection says otherwise. */
    @Volatile
    private var model: SoundcoreModel = SoundcoreModel.SLEEP_A30

    /** Newer models: the last state that parsed, which the writes are built from. */
    @Volatile
    private var latest: SoundcoreState? = null

    private val sleepA30: Boolean get() = model == SoundcoreModel.SLEEP_A30

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
        model = conn.model ?: SoundcoreModel.SLEEP_A30
        latest = null
        launch {
            conn.unsolicited.collect { packet -> applyPush(packet) }
        }
        try {
            readState(conn)
            if (!sleepA30) sessionInit(conn)
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
        var reply = conn.request(SoundcoreCommands.REQUEST_STATE)
        // Some models answer a first request with a bare ack (soundcorebridge saw it on the Space 2): ask again for the body.
        if (!sleepA30 && reply.body.isEmpty()) reply = conn.request(SoundcoreCommands.REQUEST_STATE)
        if (sleepA30) applyState(reply.body) else applyModelState(conn, reply.body)
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

    /**
     * Every other model: read the state through its table. The connection may write only if the
     * state matched the layout Earshot knows (size, length byte, serial); otherwise it stays
     * read-only, and the note says why. The serial and the raw bytes are never logged.
     */
    private fun applyModelState(conn: SoundcoreConnection, body: ByteArray) {
        val parsed = SoundcoreStateReader.read(model, body)
        if (parsed == null) {
            Log.w(TAG, "${model.code}: state of ${body.size} bytes matches no layout")
            latest = null
            conn.unlockWrites(false)
            _state.update { SoundcoreModelMapping.unreadable(it, model, body.size) }
            return
        }
        conn.unlockWrites(parsed.writable)
        latest = parsed
        Log.d(TAG, "${model.code}: ${body.size} bytes, battery=${parsed.leftPercent}/${parsed.rightPercent}/${parsed.overallPercent}, mode=${parsed.ambient}, writable=${parsed.writable}")
        _state.update { SoundcoreModelMapping.apply(it, parsed) }
    }

    /** `05 01` then `05 81` once, for the models whose table asks for it (the app's own session start). */
    private suspend fun sessionInit(conn: SoundcoreConnection) {
        if (model.layout?.sessionInit != true || !conn.writesUnlocked) return
        commands.withLock {
            try {
                conn.request(SoundcoreCommands.SESSION_INFO, timeoutsMs = listOf(1_500L))
                delay(SESSION_INIT_GAP_MS)
                conn.request(SoundcoreCommands.SESSION_INIT, timeoutsMs = listOf(1_000L))
            } catch (e: HeadphoneCommandException) {
                Log.d(TAG, "session init not answered: ${e.message}")
            }
        }
    }

    private fun applyPush(packet: SoundcorePacket) {
        if (sleepA30) {
            when (packet.command) {
                SoundcoreCommands.REQUEST_STATE -> applyState(packet.body)
                SoundcoreCommands.PUSH_BATTERY -> D1301State.parseBattery(packet.body)?.let { (l, r) ->
                    _state.update { SoundcoreStateMapping.withBattery(it, l, r) }
                }
                else -> Log.d(TAG, "push ${SoundcorePacket.commandName(packet.command)} ignored")
            }
            return
        }
        val conn = connectionManager.connection.value
        val current = latest
        when (packet.command) {
            SoundcoreCommands.REQUEST_STATE -> if (conn != null && packet.body.isNotEmpty()) applyModelState(conn, packet.body)
            SoundcoreCommands.PUSH_BATTERY -> if (current != null) SoundcoreStateReader.batteryPush(model, packet.body)?.let { p ->
                publish(current.withBattery(p.left, p.right, p.case, p.overall))
            }
            SoundcoreCommands.PUSH_CHARGING -> if (current != null) publish(current.withCharging(SoundcoreStateReader.chargingPush(packet.body)))
            SoundcoreCommands.PUSH_SOUND_MODES -> {
                val codec = current?.modes?.codec
                if (current != null && codec != null && packet.body.size == codec.size) publish(current.withModeBlock(SoundModes(codec, packet.body)))
            }
            else -> Log.d(TAG, "push ${SoundcorePacket.commandName(packet.command)} ignored")
        }
    }

    private fun publish(s: SoundcoreState) {
        latest = s
        _state.update { SoundcoreModelMapping.apply(it, s) }
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

    /**
     * A change to a newer model, built from the last state that parsed ([SoundcoreWrites] refuses
     * with a sentence when the model is read-only or the transition isn't one the app makes).
     * After the write the state is re-read, and a change that did not show up is reported: the
     * earbuds may acknowledge a write and discard it.
     */
    private suspend fun writeModel(name: String, build: (SoundcoreState) -> SoundcorePacket) = commands.withLock {
        val conn = connectionManager.connection.value ?: throw HeadphoneCommandException("Earbuds not connected")
        val before = latest ?: throw HeadphoneCommandException("The earbuds haven't reported their state yet")
        val packet = try {
            build(before)
        } catch (e: SoundcoreWriteRefused) {
            throw HeadphoneCommandException(e.message ?: UNSUPPORTED, e)
        }
        _state.update { it.copy(isBusy = true) }
        try {
            // Command and size only: the body is the earbuds' own settings and nothing needs logging.
            Log.i(TAG, "$name → ${SoundcorePacket.commandName(packet.command)} (${packet.body.size} bytes)")
            conn.request(packet.command, packet.body)
            readState(conn)
            if (!took(before, packet)) {
                delay(VERIFY_DELAY_MS)
                readState(conn)
                if (!took(before, packet)) throw HeadphoneCommandException(NOT_APPLIED)
            }
        } finally {
            _state.update { it.copy(isBusy = false) }
        }
    }

    private fun took(before: SoundcoreState, packet: SoundcorePacket): Boolean =
        latest?.let { SoundcoreWrites.confirms(before, it, packet) } == true

    // ---- What the earbuds have --------------------------------------------------------------

    override suspend fun refresh() {
        val conn = connectionManager.connection.value ?: throw HeadphoneCommandException("Earbuds not connected")
        commands.withLock { readState(conn) }
    }

    override suspend fun setMode(index: Int) {
        if (sleepA30) return when (index) {
            SoundcoreStateMapping.MODE_ANC -> write("noise cancelling", SoundcoreCommands.SET_ANC_D1301, 1)
            SoundcoreStateMapping.MODE_OFF -> write("noise cancelling", SoundcoreCommands.SET_ANC_D1301, 0)
            else -> throw HeadphoneCommandException("The Sleep A30 has two modes: noise cancelling and off")
        }
        val mode = SoundcoreModelMapping.ambientFor(index)
            ?: throw HeadphoneCommandException("These earbuds have three modes: noise cancelling, transparency and normal")
        writeModel("mode ${mode.name.lowercase()}") { SoundcoreWrites.setAmbient(it, mode) }
    }

    override suspend fun setNoise(settings: NoiseSettings) {
        if (sleepA30) return write("noise cancelling", SoundcoreCommands.SET_ANC_D1301, if (settings.ancEnabled) 1 else 0)
        val mode = if (settings.ancEnabled) AmbientMode.NOISE_CANCELLING else AmbientMode.NORMAL
        writeModel("noise cancelling") { SoundcoreWrites.setAmbient(it, mode) }
    }

    override suspend fun setTouchControls(enabled: Boolean) {
        if (!sleepA30) unsupported()
        write("tap controls", SoundcoreCommands.SET_TAP_DISABLED, if (enabled) 0 else 1)
    }

    override suspend fun setVendorControl(id: String, value: Int) {
        if (!sleepA30) return writeModel(id) { SoundcoreWrites.control(it, id, value) }
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

    // ---- Lab (the documented reads only, see SoundcoreLab) ---------------------------------

    override fun labProfile(): LabProfile? =
        connectionManager.connection.value?.takeIf { it.isOpen }?.let { SoundcoreLab.profile(it.model) }

    override fun scanLab(groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        val conn = connectionManager.connection.value?.takeIf { it.isOpen } ?: throw HeadphoneCommandException("Earbuds not connected")
        emitAll(SoundcoreLab.scan(conn, groupIds, deep))
    }

    private fun unsupported(): Nothing = throw HeadphoneCommandException(UNSUPPORTED)

    companion object {
        private const val TAG = "Earshot/Soundcore"
        const val DEMO_NAME = "Virtual Sleep A30 (demo)"
        const val UNSUPPORTED = "Earshot's Soundcore driver doesn't support that setting on these earbuds yet"
        const val NOT_APPLIED = "The earbuds acknowledged that but didn't change. Their firmware may differ from the layout Earshot knows."
        private const val STATE_POLL_MS = 60_000L
        private const val SESSION_INIT_GAP_MS = 300L
        private const val VERIFY_DELAY_MS = 500L
    }
}

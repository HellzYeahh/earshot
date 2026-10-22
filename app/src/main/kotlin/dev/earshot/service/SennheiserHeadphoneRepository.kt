package dev.earshot.service

import android.content.Context
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.ConnectionManager
import dev.earshot.bt.DeviceCandidate
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
import dev.earshot.bt.SennheiserConnection
import dev.earshot.bt.SennheiserLinkFactory
import dev.earshot.bt.SennheiserLog
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
 * [HeadphoneRepository] for Sennheiser headsets on GAIA v3 (Experimental). The same
 * [ConnectionManager] policy as the other stacks (reconnects, Bluetooth on/off, "close Smart
 * Control"), over [SennheiserConnection]; the protocol work is [SennheiserSession] and the mapping
 * [SennheiserStateMapping], both Android-free and tested against a virtual Momentum 4.
 *
 * Pushes keep the state live after the registration; the battery is also re-read every
 * [BATTERY_POLL_MS] in case a push is missed. Every command re-reads what it touched before it
 * returns. Anything Sennheiser headsets don't have (or Earshot doesn't drive) throws a readable
 * [HeadphoneCommandException]: Tools let anyone try a setting.
 */
class SennheiserHeadphoneRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    demoTransport: () -> (() -> ByteTransport)? = { null },
    autoConnect: () -> Boolean = { true },
) : HeadphoneRepository {

    private val appContext = context.applicationContext

    val devicePrefs = DevicePrefs(appContext)
    val deviceFinder = DeviceFinder(appContext, devicePrefs)
    val connectionManager = ConnectionManager(
        appContext, deviceFinder, devicePrefs, scope, SennheiserLinkFactory(),
        autoConnect = autoConnect,
        demoTransport = demoTransport,
        demoName = { DEMO_NAME },
    )

    private val _state = MutableStateFlow(HeadphoneState())
    override val state: StateFlow<HeadphoneState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HeadphoneEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<HeadphoneEvent> = _events.asSharedFlow()

    private val commands = Mutex()

    @Volatile
    private var session: SennheiserSession? = null

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

    private suspend fun runSession(conn: SennheiserConnection) = coroutineScope {
        val s = SennheiserSession(conn, connectionManager.state.value.deviceName)
        session = s
        try {
            // Collect pushes before the registration: each one is answered with a state dump.
            launch { conn.pushes.collect { frame -> s.onPush(frame) } }
            launch { s.status.collect { publish(s) } }
            try {
                commands.withLock { s.start() }
            } catch (e: HeadphoneCommandException) {
                SennheiserLog.w("initial read failed: ${e.message}")
                _events.tryEmit(HeadphoneEvent.Error("Connected, but reading the headphones failed: ${e.message}", e))
            }
            _events.tryEmit(HeadphoneEvent.Connected)
            launch {
                while (conn.isOpen) {
                    delay(BATTERY_POLL_MS)
                    if (!conn.isOpen) break
                    runCatching { commands.withLock { s.readBattery() } }.onFailure { SennheiserLog.d("battery poll failed: ${it.message}") }
                }
            }
        } finally {
            if (session === s) session = null
        }
    }

    private fun publish(s: SennheiserSession) {
        _state.update { SennheiserStateMapping.apply(it, s.status.value, s.model) }
    }

    /** One command at a time, with the busy flag up; the session re-reads what the command touched. */
    private suspend fun <T> command(block: suspend (SennheiserSession) -> T): T = commands.withLock {
        val s = session?.takeIf { it.conn.isOpen } ?: throw HeadphoneCommandException("Headphones not connected")
        _state.update { it.copy(isBusy = true) }
        try {
            block(s)
        } finally {
            publish(s)
            _state.update { it.copy(isBusy = false) }
        }
    }

    // ---- What Sennheiser headsets have -----------------------------------------------------

    override suspend fun refresh() = command { it.readAll() }

    override suspend fun setMode(index: Int) = command { it.setMode(index) }

    override suspend fun setNoise(settings: NoiseSettings) = command { it.setNoise(settings) }

    override suspend fun setEq(eq: HeadphoneEq) = command { it.setEq(_state.value.eq, eq) }

    override suspend fun setVendorControl(id: String, value: Int) = command { it.setVendorControl(id, value) }

    // ---- What they don't have (or Earshot doesn't drive yet) -------------------------------

    override suspend fun saveCustomMode(index: Int, name: String, settings: NoiseSettings) = unsupported()
    override suspend fun setFavorites(indices: Set<Int>) = unsupported()

    /** Sidetone, auto-off, auto-answer and smart pause are vendor controls here (their scales differ from Bose's). */
    override suspend fun setSidetone(level: Sidetone, persistent: Boolean) = unsupported()
    override suspend fun setAutoPlayPause(enabled: Boolean) = unsupported()
    override suspend fun setAutoAnswer(enabled: Boolean) = unsupported()
    override suspend fun setMultipoint(enabled: Boolean) = unsupported()
    override suspend fun setVoicePrompts(prompts: VoicePrompts) = unsupported()
    override suspend fun setShortcutButton(action: ButtonAction) = unsupported()

    /** Rename (`0x2801`) is on the write guard's block-list: Smart Control 4.9.3 keeps it disabled too. */
    override suspend fun setDeviceName(name: String) = unsupported()

    /** Connecting a paired device (`0x1402`) is safe but not wired yet; disconnect and delete are never sent. */
    override suspend fun switchToDevice(address: String) = unsupported()
    override suspend fun enterPairingMode() = unsupported()
    override suspend fun powerOff() = unsupported()
    override suspend fun readRaw(block: Int, function: Int): List<RawFrame> = unsupported()
    override fun scanFunctions(blocks: List<Int>, maxFunction: Int): Flow<LabReading> = emptyFlow()

    private fun unsupported(): Nothing = throw HeadphoneCommandException(UNSUPPORTED)

    companion object {
        const val DEMO_NAME = "Virtual MOMENTUM 4 (demo)"
        const val UNSUPPORTED = "Earshot's Sennheiser driver (Experimental) doesn't support that setting yet"
        private const val BATTERY_POLL_MS = 10 * 60_000L
    }
}

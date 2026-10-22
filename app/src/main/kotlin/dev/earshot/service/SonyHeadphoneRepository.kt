package dev.earshot.service

import android.content.Context
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.ConnectionManager
import dev.earshot.bt.DeviceCandidate
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
import dev.earshot.bt.SonyConnection
import dev.earshot.bt.SonyLinkFactory
import dev.earshot.bt.SonyLog
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
 * [HeadphoneRepository] for Sony headphones on MDR v2 (Experimental). The same [ConnectionManager]
 * policy as the other stacks (reconnects, Bluetooth on/off, "close Sony Sound Connect"), over
 * [SonyConnection] and a [SonySession] per connection:
 *  - connect: the init sequence, then everything the headset announced is mapped by
 *    [SonyStateMapping];
 *  - pushes: the headset sends NTFY frames for every change (also from its own buttons); battery
 *    is also re-read every [BATTERY_POLL_MS] in case a model doesn't push it;
 *  - commands: the three noise modes, the noise settings automations send, every vendor control,
 *    power off. Each reads its setting back, so the screen shows what the headphones did.
 * Everything else throws a readable [HeadphoneCommandException]: Tools let anyone try a setting.
 */
class SonyHeadphoneRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    demoTransport: () -> (() -> ByteTransport)? = { null },
    autoConnect: () -> Boolean = { true },
) : HeadphoneRepository {

    private val appContext = context.applicationContext

    val devicePrefs = DevicePrefs(appContext)
    val deviceFinder = DeviceFinder(appContext, devicePrefs)
    val connectionManager = ConnectionManager(
        appContext, deviceFinder, devicePrefs, scope, SonyLinkFactory(),
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
    private var session: SonySession? = null

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

    private suspend fun runSession(conn: SonyConnection) = coroutineScope {
        val s = SonySession(conn)
        session = s
        try {
            launch {
                conn.unsolicited.collect { m ->
                    s.onPush(m)?.let { _events.tryEmit(HeadphoneEvent.Info(it)) }
                    publish(s.snapshot)
                }
            }
            try {
                commands.withLock { publish(s.initialize()) }
            } catch (e: HeadphoneCommandException) {
                SonyLog.w("init failed: ${e.message}")
                _events.tryEmit(HeadphoneEvent.Error("Connected, but reading the headphones failed: ${e.message}", e))
            }
            _events.tryEmit(HeadphoneEvent.Connected)
            launch {
                while (conn.isOpen) {
                    delay(BATTERY_POLL_MS)
                    if (!conn.isOpen) break
                    runCatching { commands.withLock { publish(s.readBattery()) } }.onFailure { SonyLog.d("battery poll failed: ${it.message}") }
                }
            }
        } finally {
            if (session === s) session = null
        }
    }

    private fun publish(snapshot: SonySnapshot) {
        _state.update { SonyStateMapping.apply(it, snapshot) }
    }

    private fun requireSession(): SonySession = session?.takeIf { connectionManager.connection.value?.isOpen == true }
        ?: throw HeadphoneCommandException("Headphones not connected")

    /** One command at a time; the session reads the setting back before [publish]. */
    private suspend fun command(what: String, block: suspend (SonySession) -> SonySnapshot) = commands.withLock {
        val s = requireSession()
        _state.update { it.copy(isBusy = true) }
        try {
            SonyLog.i("set $what")
            publish(block(s))
        } finally {
            _state.update { it.copy(isBusy = false) }
        }
    }

    // ---- What Sony headphones have -----------------------------------------------------------

    override suspend fun refresh() = commands.withLock { publish(requireSession().readValues()) }

    override suspend fun setMode(index: Int) = command("mode $index") { it.setMode(index) }

    override suspend fun setNoise(settings: NoiseSettings) = command("noise") { it.setNoise(settings) }

    override suspend fun setVendorControl(id: String, value: Int) = command(id) { it.setVendorControl(id, value) }

    override suspend fun powerOff() = commands.withLock { requireSession().powerOff() }

    // ---- What it doesn't have (or Earshot doesn't drive yet) --------------------------------

    override suspend fun saveCustomMode(index: Int, name: String, settings: NoiseSettings) = unsupported()
    override suspend fun setFavorites(indices: Set<Int>) = unsupported()

    /** The Sony EQ has its own bands and presets: they are vendor controls, not the 3-band `HeadphoneEq`. */
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
    override suspend fun readRaw(block: Int, function: Int): List<RawFrame> = unsupported()
    override fun scanFunctions(blocks: List<Int>, maxFunction: Int): Flow<LabReading> = emptyFlow()

    private fun unsupported(): Nothing = throw HeadphoneCommandException(UNSUPPORTED)

    companion object {
        const val DEMO_NAME = "Virtual Sony WH-1000XM5 (demo)"
        const val UNSUPPORTED = "Earshot's Sony driver doesn't support that setting yet"
        private const val BATTERY_POLL_MS = 120_000L
    }
}

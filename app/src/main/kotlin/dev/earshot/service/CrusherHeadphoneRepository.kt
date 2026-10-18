package dev.earshot.service

import android.content.Context
import android.util.Log
import dev.earshot.bt.AndroidBragiTrustStore
import dev.earshot.bt.BragiConnection
import dev.earshot.bt.BragiLinkFactory
import dev.earshot.bt.BragiSession
import dev.earshot.bt.BragiTrustStore
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.ConnectionManager
import dev.earshot.bt.DeviceCandidate
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
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
import dev.earshot.core.PairingRequest
import dev.earshot.core.RawFrame
import dev.earshot.core.Sidetone
import dev.earshot.core.VoicePrompts
import dev.earshot.protocol.bragi.BosRpcPacket
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * [HeadphoneRepository] for the Skullcandy Crusher 1080 ANC. The Bose counterpart is
 * [ServiceHeadphoneRepository]; the two share a contract, not a wire format, so everything above
 * (Home, widget, tile, notification, automations) works unchanged against either.
 *
 * It owns a [ConnectionManager] of [BragiConnection]s, so reconnects, Bluetooth on/off handling and
 * the busy-connection message are the same policy the Bose path uses, and runs the typed
 * [CrusherProtocol] over it: connect, subscribe, refresh, commands one at a time, pushes merged on
 * top. Battery has no push in the headphones' protocol, so it is polled once a minute.
 *
 * Features the Crusher does not have (custom modes, device EQ and the Bose-only toggles) throw
 * [HeadphoneCommandException] with a readable message, which the UI already shows.
 */
class CrusherHeadphoneRepository(
    context: Context,
    private val protocol: CrusherProtocol = CrusherProtocol(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    demoTransport: () -> (() -> ByteTransport)? = { null },
    autoConnect: () -> Boolean = { true },
    /** Keys for the secure session some Crusher firmware asks for; see [BragiSession]. */
    private val trust: BragiTrustStore = AndroidBragiTrustStore(context),
) : HeadphoneRepository {

    private val appContext = context.applicationContext

    val devicePrefs = DevicePrefs(appContext)
    val deviceFinder = DeviceFinder(appContext, devicePrefs)
    val connectionManager = ConnectionManager(
        appContext, deviceFinder, devicePrefs, scope, BragiLinkFactory(),
        autoConnect = autoConnect,
        demoTransport = demoTransport,
        demoName = DEMO_NAME,
    )

    private val _state = MutableStateFlow(HeadphoneState())
    override val state: StateFlow<HeadphoneState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HeadphoneEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<HeadphoneEvent> = _events.asSharedFlow()

    /** Serialises typed commands so a multi-call command never interleaves with another. */
    private val commands = Mutex()

    /** Pushes that arrived while a command held [commands]; merged on top of its result. */
    private val deferredPushes = ConcurrentLinkedQueue<BosRpcPacket>()

    /** The pairing prompt waiting for the user's answer (see [answerPairing]). */
    private val pendingPairing = AtomicReference<CompletableDeferred<Boolean>?>(null)

    private val session = BragiSession(trust, prompt = { passcode, digits -> askUser(passcode, digits) })

    init {
        scope.launch {
            var wasConnected = false
            connectionManager.state.collect { cs ->
                val connected = cs.status == ConnectionStatus.CONNECTED
                _state.update { s ->
                    // Drop stale headphone data when the link goes.
                    if (connected || !wasConnected) s.copy(connection = cs) else HeadphoneState(connection = cs)
                }
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

    override suspend fun deviceCandidates(): List<HeadphoneCandidate> =
        deviceFinder.candidates().map { it.toCandidate() }

    private fun DeviceCandidate.toCandidate() =
        HeadphoneCandidate(address = address, name = name, looksLikeBose = looksLikeBose, isAudioDevice = isAudioDevice)

    override suspend fun chooseDevice(address: String, name: String?) {
        HeadphoneService.start(appContext)
        connectionManager.chooseDevice(address, name)
    }

    /** True when [address] is the saved (or, before any choice, a Skullcandy-looking) device. */
    suspend fun isOurDevice(address: String): Boolean = connectionManager.isOurDevice(address)

    private suspend fun runSession(conn: BragiConnection) = coroutineScope {
        deferredPushes.clear()
        launch {
            conn.unsolicited.collect { packet ->
                if (commands.isLocked) {
                    deferredPushes += packet
                    if (!commands.isLocked) drainPushes()
                } else {
                    applyPush(packet)
                }
            }
        }
        val address = connectionManager.state.value.deviceAddress ?: DEMO_ADDRESS
        try {
            command("onConnected") { c, s ->
                protocol.onConnected(c, s, secure = { session.establish(c, address) { cipher -> c.installCipher(cipher) } })
            }
        } catch (e: HeadphoneCommandException) {
            Log.w(TAG, "initial read failed: ${e.message}")
            if (e.message == BragiConnection.MESSAGE_STOPPED_ANSWERING || !conn.isOpen) {
                conn.closeUnresponsive()
                return@coroutineScope
            }
            _events.tryEmit(HeadphoneEvent.Error("Connected, but reading settings failed: ${e.message}", e))
        }
        _events.tryEmit(HeadphoneEvent.Connected)
        // No battery push in the headphones' protocol: poll.
        launch {
            while (conn.isOpen) {
                delay(BATTERY_POLL_MS)
                if (!conn.isOpen) break
                try {
                    command("pollBattery", showBusy = false) { c, s -> protocol.pollBattery(c, s) }
                } catch (e: HeadphoneCommandException) {
                    Log.d(TAG, "battery poll failed: ${e.message}")
                }
            }
        }
    }

    // ---- Pairing ---------------------------------------------------------------------------

    /** Show the passcode and wait for the user (or give up after [PAIRING_TIMEOUT_MS]). */
    private suspend fun askUser(passcode: Int?, digits: Int): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingPairing.set(answer)
        _state.update { it.copy(pairing = PairingRequest(passcode, digits)) }
        return try {
            withTimeoutOrNull(PAIRING_TIMEOUT_MS) { answer.await() } ?: false
        } finally {
            pendingPairing.set(null)
            _state.update { it.copy(pairing = null) }
        }
    }

    override suspend fun answerPairing(accept: Boolean) {
        pendingPairing.get()?.complete(accept)
    }

    override suspend fun forgetPairing() {
        val address = devicePrefs.savedDevice()?.address ?: connectionManager.state.value.deviceAddress
            ?: throw HeadphoneCommandException("No headphones are saved yet")
        trust.forget(address)
    }

    // ---- Commands --------------------------------------------------------------------------

    override suspend fun refresh() = command("refresh") { c, s -> protocol.refresh(c, s) }

    override suspend fun setMode(index: Int) = command("setMode") { c, s -> protocol.setMode(c, s, index) }

    override suspend fun saveCustomMode(index: Int, name: String, settings: NoiseSettings) = unsupported("Custom modes")

    override suspend fun setFavorites(indices: Set<Int>) = unsupported("Favourite modes")

    override suspend fun setNoise(settings: NoiseSettings) = command("setNoise") { c, s -> protocol.setNoise(c, s, settings) }

    override suspend fun setEq(eq: HeadphoneEq) = unsupported("The headphones' own equaliser")

    override suspend fun setSidetone(level: Sidetone, persistent: Boolean) =
        command("setSidetone") { c, s -> protocol.setSidetone(c, s, level) }

    override suspend fun setCrusherBass(active: Boolean?, intensity: Int?, mode: Int?) =
        command("setCrusherBass") { c, s -> protocol.setCrusherBass(c, s, active, intensity, mode) }

    override suspend fun setAutoPlayPause(enabled: Boolean) = unsupported("Auto play/pause")
    override suspend fun setAutoAnswer(enabled: Boolean) = unsupported("Auto answer")
    override suspend fun setMultipoint(enabled: Boolean) = unsupported("Multipoint")
    override suspend fun setVoicePrompts(prompts: VoicePrompts) = unsupported("Voice prompts")
    override suspend fun setShortcutButton(action: ButtonAction) = unsupported("The shortcut button")
    override suspend fun setDeviceName(name: String) = unsupported("Renaming")
    override suspend fun switchToDevice(address: String) = unsupported("Switching devices")
    override suspend fun enterPairingMode() = unsupported("Pairing mode")
    override suspend fun powerOff() = unsupported("Power off")

    // ---- Lab (read-only) -------------------------------------------------------------------

    /**
     * The Lab's `block.function` is a BOS-RPC `service.method` here. Only reads from
     * [CrusherProtocol.LAB_READS] go out; anything else is refused, so the Lab can never write.
     */
    override suspend fun readRaw(block: Int, function: Int): List<RawFrame> {
        val address = BragiServices.Address(block, function)
        val read = CrusherProtocol.LAB_READS.firstOrNull { it.address == address }
            ?: throw HeadphoneCommandException("Only read-only calls are available in the Lab")
        return listOf(readOne(requireConnection(), read))
    }

    override fun scanFunctions(blocks: List<Int>, maxFunction: Int): Flow<LabReading> = flow {
        val conn = requireConnection()
        // The Lab's block chips are Bose function blocks; the Crusher's whole read-only list is small, so it is always all of it.
        for (read in CrusherProtocol.LAB_READS) {
            emit(LabReading(read.address.service, read.address.method, read.name, listOf(readOne(conn, read))))
        }
    }

    private suspend fun readOne(conn: BragiConnection, read: CrusherProtocol.LabRead): RawFrame {
        val a = read.address
        return try {
            val reply = conn.callUnwatched(a, read.body, timeoutMs = LAB_TIMEOUT_MS, streaming = read.streaming)
            RawFrame(a.service, a.method, "RESPONSE", reply.payload.toHex(), isError = false)
        } catch (e: HeadphoneCommandException) {
            if (!conn.isOpen) throw e
            RawFrame(a.service, a.method, "ERROR", "", isError = true, errorName = e.message)
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun unsupported(what: String): Nothing =
        throw HeadphoneCommandException("$what isn't available on these headphones")

    private fun requireConnection(): BragiConnection =
        connectionManager.connection.value?.takeIf { it.isOpen }
            ?: throw HeadphoneCommandException("Headphones not connected")

    /** [showBusy] false for background reads (the battery poll) so they do not grey out the controls every minute. */
    private suspend fun command(name: String, showBusy: Boolean = true, block: suspend (BragiConnection, HeadphoneState) -> HeadphoneState) {
        commands.withLock {
            val conn = requireConnection()
            if (showBusy) _state.update { it.copy(isBusy = true) }
            try {
                apply(block(conn, _state.value))
            } catch (e: HeadphoneCommandException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$name failed", e)
                throw HeadphoneCommandException(e.message ?: "Command failed", e)
            } finally {
                drainPushes()
                if (showBusy) _state.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun apply(next: HeadphoneState) {
        _state.update { cur -> next.copy(connection = cur.connection, isBusy = cur.isBusy, lastUpdatedMillis = System.currentTimeMillis()) }
    }

    private fun applyPush(packet: BosRpcPacket) {
        val next = try {
            protocol.onUnsolicited(packet, _state.value)
        } catch (e: Exception) {
            Log.w(TAG, "onUnsolicited failed for $packet: ${e.message}")
            null
        }
        if (next != null) apply(next)
    }

    private fun drainPushes() {
        while (true) applyPush(deferredPushes.poll() ?: break)
    }

    companion object {
        private const val TAG = "Earshot/Crusher"
        const val DEMO_NAME = "Virtual Crusher 1080 (demo)"
        private const val DEMO_ADDRESS = ConnectionManager.DEMO_ADDRESS
        /** A little under [BragiSession.USER_TIMEOUT_MS] so the dialog closes before the headphones give up. */
        private const val PAIRING_TIMEOUT_MS = 115_000L
        private const val BATTERY_POLL_MS = 60_000L
        private const val LAB_TIMEOUT_MS = 2_000L
    }
}

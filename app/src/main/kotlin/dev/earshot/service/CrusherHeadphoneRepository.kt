package dev.earshot.service

import android.content.Context
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
import dev.earshot.bt.LinkSource
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.NoiseSettings
import dev.earshot.core.PairingRequest
import dev.earshot.core.RawFrame
import dev.earshot.core.Sidetone
import dev.earshot.protocol.bragi.BosRpcPacket
import dev.earshot.protocol.bragi.BragiReads
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.service.lab.BragiLab
import dev.earshot.service.session.AndroidForegroundHost
import dev.earshot.service.session.AndroidSessionLog
import dev.earshot.service.session.ForegroundHost
import dev.earshot.service.session.SessionLog
import dev.earshot.service.session.SessionRepository
import dev.earshot.service.session.StateCommands
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * [HeadphoneRepository] for the Skullcandy Crusher 1080 ANC. The Bose counterpart is
 * [ServiceHeadphoneRepository]; the two share a contract, not a wire format, so everything above
 * (Home, widget, tile, notification, automations) works unchanged against either.
 *
 * It runs on a [ConnectionManager] of [BragiConnection]s, so reconnects, Bluetooth on/off handling
 * and the busy-connection message are the same policy the Bose path uses, and the typed
 * [CrusherProtocol] over it: connect, subscribe, refresh, commands one at a time, pushes merged on
 * top ([StateCommands]). Battery has no push in the headphones' protocol, so it is polled once a
 * minute without the busy flag. The shared part is [SessionRepository].
 *
 * Features the Crusher does not have (custom modes, device EQ and the Bose-only toggles) throw
 * [HeadphoneCommandException] with a readable message, which the UI already shows.
 */
class CrusherHeadphoneRepository internal constructor(
    private val protocol: CrusherProtocol,
    link: LinkSource<BragiConnection>,
    candidates: suspend () -> List<DeviceCandidate>,
    foreground: ForegroundHost,
    scope: CoroutineScope,
    /** Keys for the secure session some Crusher firmware asks for; see [BragiSession]. */
    private val trust: BragiTrustStore,
    /** The saved device's address (`DevicePrefs.savedDevice`), for [forgetPairing]. */
    private val savedAddress: suspend () -> String?,
    log: SessionLog = AndroidSessionLog(TAG),
) : SessionRepository<BragiConnection>(link, candidates, foreground, scope, log) {

    constructor(
        context: Context,
        protocol: CrusherProtocol = CrusherProtocol(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        demoTransport: () -> (() -> ByteTransport)? = { null },
        autoConnect: () -> Boolean = { true },
        trust: BragiTrustStore = AndroidBragiTrustStore(context),
        /** The name shown for the demo device ("Virtual Crusher 1080 (demo)" unless the demo is another Bragi product). */
        demoName: () -> String = { DEMO_NAME },
        /** Shared with the other stacks (`AppContainer`); one store per process either way. */
        prefs: DevicePrefs = DevicePrefs(context),
        finder: DeviceFinder = DeviceFinder(context.applicationContext, prefs),
    ) : this(
        protocol = protocol,
        link = ConnectionManager(
            context.applicationContext, finder, prefs, scope, BragiLinkFactory(),
            autoConnect = autoConnect,
            demoTransport = demoTransport,
            demoName = demoName,
        ),
        candidates = finder::candidates,
        foreground = AndroidForegroundHost(context, finder),
        scope = scope,
        trust = trust,
        savedAddress = { prefs.savedDevice()?.address },
    )

    /** Typed commands one at a time; pushes that arrive under one are merged on top of its result. */
    private val pushes = StateCommands<BragiConnection, BosRpcPacket>(
        mutableState, commands, { requireConnection() }, log,
        onPush = { packet, s -> protocol.onUnsolicited(packet, s) },
    )

    /** The pairing prompt waiting for the user's answer (see [answerPairing]). */
    private val pendingPairing = AtomicReference<CompletableDeferred<Boolean>?>(null)

    private val session = BragiSession(trust, prompt = { passcode, digits -> askUser(passcode, digits) })

    init {
        start() // last: runSession reads the fields above
    }

    override suspend fun CoroutineScope.runSession(conn: BragiConnection) {
        pushes.reset()
        launch {
            conn.unsolicited.collect { packet -> pushes.push(packet) }
        }
        val address = link.state.value.deviceAddress ?: DEMO_ADDRESS
        try {
            pushes.run("onConnected") { c, s ->
                protocol.onConnected(c, s, secure = { session.establish(c, address) { cipher -> c.installCipher(cipher) } })
            }
        } catch (e: HeadphoneCommandException) {
            log.w("initial read failed: ${e.message}")
            if (e.message == BragiConnection.MESSAGE_STOPPED_ANSWERING || !conn.isOpen) {
                conn.closeUnresponsive()
                return
            }
            mutableEvents.tryEmit(HeadphoneEvent.Error("Connected, but reading settings failed: ${e.message}", e))
        }
        mutableEvents.tryEmit(HeadphoneEvent.Connected)
        // No battery push in the headphones' protocol: poll, without greying out the controls.
        pollWhileOpen(conn, BATTERY_POLL_MS) {
            pushes.run("pollBattery", showBusy = false) { c, s -> protocol.pollBattery(c, s) }
        }
    }

    // ---- Pairing ---------------------------------------------------------------------------

    /** Show the passcode and wait for the user (or give up after [PAIRING_TIMEOUT_MS]). */
    private suspend fun askUser(passcode: Int?, digits: Int): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingPairing.set(answer)
        mutableState.update { it.copy(pairing = PairingRequest(passcode, digits)) }
        return try {
            withTimeoutOrNull(PAIRING_TIMEOUT_MS) { answer.await() } ?: false
        } finally {
            pendingPairing.set(null)
            mutableState.update { it.copy(pairing = null) }
        }
    }

    override suspend fun answerPairing(accept: Boolean) {
        pendingPairing.get()?.complete(accept)
    }

    override suspend fun forgetPairing() {
        val address = savedAddress() ?: link.state.value.deviceAddress
            ?: throw HeadphoneCommandException("No headphones are saved yet")
        trust.forget(address)
    }

    // ---- Commands --------------------------------------------------------------------------

    override suspend fun refresh() = pushes.run("refresh") { c, s -> protocol.refresh(c, s) }

    override suspend fun setMode(index: Int) = pushes.run("setMode") { c, s -> protocol.setMode(c, s, index) }

    override suspend fun setNoise(settings: NoiseSettings) = pushes.run("setNoise") { c, s -> protocol.setNoise(c, s, settings) }

    override suspend fun setSidetone(level: Sidetone, persistent: Boolean) =
        pushes.run("setSidetone") { c, s -> protocol.setSidetone(c, s, level) }

    override suspend fun setCrusherBass(active: Boolean?, intensity: Int?, mode: Int?) =
        pushes.run("setCrusherBass") { c, s -> protocol.setCrusherBass(c, s, active, intensity, mode) }

    // Custom modes, favourites, the headphones' own equaliser, the Bose toggles, renaming,
    // switching devices, pairing mode and power off: the base says "<feature> isn't available on
    // these headphones", the Crusher's wording.

    // ---- Lab (read-only, see BragiLab) -----------------------------------------------------

    override fun labProfile(): LabProfile? =
        if (openConnection() != null) BragiLab.profile(protocol.currentModel) else null

    override fun scanLab(groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        emitAll(BragiLab.scan(requireConnection(), protocol.currentModel, groupIds, deep))
    }

    /**
     * The Lab's `block.function` is a BOS-RPC `service.method` here. Only the read-only calls in
     * [BragiReads] go out; anything else is refused, so the Lab can never write.
     */
    override suspend fun readRaw(block: Int, function: Int): List<RawFrame> {
        val read = BragiReads.find(BragiServices.Address(block, function))
            ?: throw HeadphoneCommandException("Only read-only calls are available in the Lab")
        return listOf(BragiLab.readOne(requireConnection(), read))
    }

    companion object {
        private const val TAG = "Earshot/Crusher"
        const val DEMO_NAME = "Virtual Crusher 1080 (demo)"
        private const val DEMO_ADDRESS = ConnectionManager.DEMO_ADDRESS
        /** A little under [BragiSession.USER_TIMEOUT_MS] so the dialog closes before the headphones give up. */
        private const val PAIRING_TIMEOUT_MS = 115_000L
        private const val BATTERY_POLL_MS = 60_000L
    }
}

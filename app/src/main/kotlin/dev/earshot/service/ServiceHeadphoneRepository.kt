package dev.earshot.service

import android.content.Context
import dev.earshot.bt.BmapConnection
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.BoseLinkFactory
import dev.earshot.bt.ConnectionManager
import dev.earshot.bt.DeviceCandidate
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
import dev.earshot.bt.LinkSource
import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.NoiseSettings
import dev.earshot.core.RawFrame
import dev.earshot.core.Sidetone
import dev.earshot.core.TransportControl
import dev.earshot.core.VoicePrompts
import dev.earshot.protocol.BmapException
import dev.earshot.protocol.BmapPacket
import dev.earshot.service.lab.BmapLab
import dev.earshot.service.session.AndroidForegroundHost
import dev.earshot.service.session.AndroidSessionLog
import dev.earshot.service.session.ForegroundHost
import dev.earshot.service.session.SessionLog
import dev.earshot.service.session.SessionRepository
import dev.earshot.service.session.StateCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * The Bose [HeadphoneRepository]: one per process, created by `AppContainer` as
 * `ServiceHeadphoneRepository(context, protocol, …)`.
 *
 * It runs on the Bluetooth stack ([ConnectionManager] → [BmapConnection]) and routes every typed
 * command through [HeadphoneProtocol]. [HeadphoneService] is only a lifecycle shell that keeps
 * the process alive and shows the notification; it reads this repository like everyone else. The
 * parts every stack shares (state and events, connect, one session per link, the guards) are
 * [SessionRepository]; commands and the pushes merged under them are [StateCommands].
 *
 * Connect sequence: `connect()` → foreground service up → manager opens the socket → this
 * class subscribes to unsolicited frames, runs `protocol.refresh`, emits
 * [HeadphoneEvent.Connected]. A failure in `refresh` is reported as an event but keeps the
 * connection (with the stub protocol, refresh is a no-op), except "the headphones stopped
 * answering": a silent-but-open socket is another app holding channel 2, so the session is
 * closed and the manager shows the busy-channel message and retries. Mid-session, the same
 * silence is caught by [BmapConnection]'s watchdog, which closes the link by itself.
 */
class ServiceHeadphoneRepository internal constructor(
    private val protocol: HeadphoneProtocol,
    /** The connection policy ([ConnectionManager] of [BmapConnection]s); a fake in JVM tests. */
    link: LinkSource<BmapConnection>,
    /** Bonded devices that might be the headphones (`DeviceFinder.candidates`). */
    candidates: suspend () -> List<DeviceCandidate>,
    foreground: ForegroundHost,
    scope: CoroutineScope,
    log: SessionLog = AndroidSessionLog(TAG),
) : SessionRepository<BmapConnection>(link, candidates, foreground, scope, log) {

    constructor(
        context: Context,
        protocol: HeadphoneProtocol,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        /** Demo mode hook, see [ConnectionManager]. Null = real Bluetooth. */
        demoTransport: () -> (() -> ByteTransport)? = { null },
        /** The demo connection's name, so each virtual model is called by its own name. */
        demoName: () -> String = { ConnectionManager.DEMO_NAME },
        /** Read on every Bluetooth event; `AppContainer` passes `autoConnectOnBluetooth`, as for the other stacks. */
        autoConnect: () -> Boolean = { true },
        /** Shared with the other stacks (`AppContainer`); one store per process either way. */
        prefs: DevicePrefs = DevicePrefs(context),
        finder: DeviceFinder = DeviceFinder(context.applicationContext, prefs),
    ) : this(
        protocol = protocol,
        link = ConnectionManager(
            context.applicationContext, finder, prefs, scope, BoseLinkFactory(context.applicationContext, finder),
            autoConnect = autoConnect,
            demoTransport = demoTransport,
            demoName = demoName,
        ),
        candidates = finder::candidates,
        foreground = AndroidForegroundHost(context, finder),
        scope = scope,
    )

    /**
     * Typed commands one at a time. Each returns a whole new state computed from a snapshot, so a
     * push that arrives under one is parked and merged on top of its result. A device ERROR
     * becomes the user-facing [HeadphoneCommandException] ([toCommandException]).
     */
    private val pushes = StateCommands<BmapConnection, BmapPacket>(
        mutableState, commands, { requireConnection() }, log,
        onPush = { frame, s -> protocol.onUnsolicited(frame, s) },
        mapError = { e -> (e as? BmapException)?.toCommandException() },
    )

    init {
        start() // last: runSession reads the fields above
    }

    override suspend fun CoroutineScope.runSession(conn: BmapConnection) {
        pushes.reset() // anything left from the previous session describes a dead link
        // Subscribe before refresh so a push that races the first reads is not lost.
        launch {
            conn.unsolicited.collect { frame -> pushes.push(frame) }
        }
        // Subscribe to the headphones' own pushes ([9.2]) before the first refresh, as the Bose app
        // does; never fatal. Without it the state only changes on refresh, so poll the battery.
        try {
            command("onConnected") { c, s -> protocol.onConnected(c, s) }
        } catch (e: HeadphoneCommandException) {
            log.w("push subscription failed: ${e.message}")
            if (e.message == BmapConnection.MESSAGE_STOPPED_ANSWERING || !conn.isOpen) {
                conn.closeUnresponsive() // same as a silent refresh below: another app holds channel 2
                return
            }
        }
        try {
            command("refresh") { c, s -> protocol.refresh(c, s) }
        } catch (e: HeadphoneCommandException) {
            log.w("initial refresh failed: ${e.message}")
            if (e.message == BmapConnection.MESSAGE_STOPPED_ANSWERING || !conn.isOpen) {
                // Open socket, nobody answering: another app holds channel 2 (or the link died).
                // Staying CONNECTED with an empty state helped nobody; close it and let the
                // manager report the busy channel and retry on its backoff. The connection's own
                // watchdog normally got here first; this covers a protocol that gave up sooner.
                conn.closeUnresponsive()
                return
            }
            mutableEvents.tryEmit(HeadphoneEvent.Error("Connected, but reading settings failed: ${e.message}", e))
        }
        mutableEvents.tryEmit(HeadphoneEvent.Connected)
        if (!mutableState.value.livePushEnabled) {
            // Fallback only: with [9.2] on, battery changes arrive as pushes and nothing is polled.
            // Through command(), so the busy flag shows during the read, as it always did.
            pollWhileOpen(conn, QcUltra2Protocol.BATTERY_POLL_MS, stopWhen = { mutableState.value.livePushEnabled }) {
                command("pollBattery") { c, s -> protocol.pollBattery(c, s) }
            }
        }
    }

    // ---- Commands --------------------------------------------------------------------------

    override suspend fun refresh() = command("refresh") { c, s -> protocol.refresh(c, s) }

    override suspend fun setMode(index: Int) = command("setMode") { c, s -> protocol.setMode(c, s, index) }

    override suspend fun saveCustomMode(index: Int, name: String, settings: NoiseSettings) =
        command("saveCustomMode") { c, s -> protocol.saveCustomMode(c, s, index, name, settings) }

    override suspend fun setFavorites(indices: Set<Int>) = command("setFavorites") { c, s -> protocol.setFavorites(c, s, indices) }

    override suspend fun setNoise(settings: NoiseSettings) = command("setNoise") { c, s -> protocol.setNoise(c, s, settings) }

    override suspend fun setEq(eq: HeadphoneEq) = command("setEq") { c, s -> protocol.setEq(c, s, eq) }

    override suspend fun setSidetone(level: Sidetone, persistent: Boolean) =
        command("setSidetone") { c, s -> protocol.setSidetone(c, s, level, persistent) }

    override suspend fun setAutoPlayPause(enabled: Boolean) = command("setAutoPlayPause") { c, s -> protocol.setAutoPlayPause(c, s, enabled) }
    override suspend fun setAutoAnswer(enabled: Boolean) = command("setAutoAnswer") { c, s -> protocol.setAutoAnswer(c, s, enabled) }
    override suspend fun setMultipoint(enabled: Boolean) = command("setMultipoint") { c, s -> protocol.setMultipoint(c, s, enabled) }
    override suspend fun setVoicePrompts(prompts: VoicePrompts) = command("setVoicePrompts") { c, s -> protocol.setVoicePrompts(c, s, prompts) }
    override suspend fun setShortcutButton(action: ButtonAction) = command("setShortcutButton") { c, s -> protocol.setShortcutButton(c, s, action) }
    override suspend fun setShortcutButton(buttonId: Int, eventType: Int, action: ButtonAction) =
        command("setShortcutButton") { c, s -> protocol.setShortcutButton(c, s, buttonId, eventType, action) }
    override suspend fun setDeviceName(name: String) = command("setDeviceName") { c, s -> protocol.setDeviceName(c, s, name) }

    override suspend fun switchToDevice(address: String) = command("switchToDevice") { c, s -> protocol.switchToDevice(c, s, address) }

    override suspend fun resetCustomMode(index: Int) = command("resetCustomMode") { c, s -> protocol.resetCustomMode(c, s, index) }
    override suspend fun setLeAudioEnabled(enabled: Boolean) = command("setLeAudioEnabled") { c, s -> protocol.setLeAudioEnabled(c, s, enabled) }
    override suspend fun setLatencyMs(ms: Int) = command("setLatencyMs") { c, s -> protocol.setLatencyMs(c, s, ms) }
    override suspend fun setTouchControls(enabled: Boolean) = command("setTouchControls") { c, s -> protocol.setTouchControls(c, s, enabled) }
    override suspend fun setAutoOff(minutes: Int) = command("setAutoOff") { c, s -> protocol.setAutoOff(c, s, minutes) }
    override suspend fun setVolume(level: Int) = command("setVolume") { c, s -> protocol.setVolume(c, s, level) }
    override suspend fun transport(control: TransportControl) = command("transport") { c, s -> protocol.transport(c, s, control) }

    /** Serialised with the other commands but never touches [state]. */
    override suspend fun printConsole(command: String): String = try {
        commands.withLock {
            val conn = requireConnection()
            try {
                protocol.printConsole(conn, command)
            } catch (e: BmapException) {
                throw e.toCommandException()
            } finally {
                // Pushes parked while the console held the lock (a volume swipe, say).
                pushes.drain()
            }
        }
    } finally {
        // A push that landed after the drain above but before the unlock found the lock taken and
        // was parked; without this it would wait for the next push or command (audit R27). Same
        // hand-off as at the end of StateCommands.run.
        pushes.drainIfFree()
    }

    override suspend fun enterPairingMode() {
        command("enterPairingMode") { c, s -> protocol.enterPairingMode(c, s) }
        mutableEvents.tryEmit(HeadphoneEvent.Info("Pairing mode on"))
    }

    override suspend fun powerOff() {
        command("powerOff") { c, s -> protocol.powerOff(c, s) }
        // The headphones are going away; do not fight it with reconnect attempts. The next
        // ACL_CONNECTED (power on) re-arms auto-connect. The virtual unit has no ACL event:
        // its transport drops the link and the demo loop "finds it switched back on" by itself.
        if (!link.isDemoSession) disconnect()
    }

    // ---- Lab (GET only, see BmapLab) ---------------------------------------------------------

    override fun labProfile(): LabProfile? =
        if (openConnection() != null) BmapLab.profile() else null

    override fun scanLab(groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        emitAll(BmapLab.scan(requireConnection(), groupIds, deep))
    }

    // MULTI so a function that answers with several frames (battery components, paired list) is
    // shown whole; costs one quiet gap per read, fine for a single tap. Unwatched: poking silent
    // functions on purpose must not trip the half-dead watchdog.
    override suspend fun readRaw(block: Int, function: Int): List<RawFrame> = BmapLab.readRaw(requireConnection(), block, function)

    // ---- helpers ---------------------------------------------------------------------------

    private suspend fun command(name: String, block: suspend (BmapConnection, HeadphoneState) -> HeadphoneState) =
        pushes.run(name, block = block)

    private companion object {
        const val TAG = "Earshot/Repo"
    }
}

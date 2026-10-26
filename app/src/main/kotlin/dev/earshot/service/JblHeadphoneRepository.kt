package dev.earshot.service

import android.content.Context
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.ConnectionManager
import dev.earshot.bt.DeviceCandidate
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
import dev.earshot.bt.JblConnection
import dev.earshot.bt.JblLinkFactory
import dev.earshot.bt.JblLog
import dev.earshot.bt.LinkSource
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.NoiseSettings
import dev.earshot.service.lab.JblLab
import dev.earshot.service.session.AndroidForegroundHost
import dev.earshot.service.session.ForegroundHost
import dev.earshot.service.session.SessionLog
import dev.earshot.service.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * [HeadphoneRepository] for JBL headphones and earbuds on the Harman "AA" protocol (Experimental,
 * virtual only). The same [ConnectionManager] policy as the other stacks (reconnects, Bluetooth
 * on/off, "close the JBL Headphones app"), over [JblConnection]; the protocol work is [JblSession]
 * and the mapping [JblStateMapping], both Android-free and tested against scripted frames. The shared
 * part (state, events, connect, the session lifetime) is [SessionRepository].
 *
 * The headset pushes its changes once the session registered for them, and each push is folded into
 * the session's status as it arrives. The battery is also re-read every [BATTERY_POLL_MS] in case a
 * model doesn't push it. Every command reads back what it touched before it returns. Anything JBL
 * headsets don't have (or Earshot doesn't drive) throws a readable [HeadphoneCommandException]:
 * Tools let anyone try a setting.
 */
class JblHeadphoneRepository internal constructor(
    link: LinkSource<JblConnection>,
    candidates: suspend () -> List<DeviceCandidate>,
    foreground: ForegroundHost,
    scope: CoroutineScope,
) : SessionRepository<JblConnection>(link, candidates, foreground, scope, JblSessionLog) {

    constructor(
        context: Context,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        demoTransport: () -> (() -> ByteTransport)? = { null },
        autoConnect: () -> Boolean = { true },
        /** Shared with the other stacks (`AppContainer`); one store per process either way. */
        prefs: DevicePrefs = DevicePrefs(context),
        finder: DeviceFinder = DeviceFinder(context.applicationContext, prefs),
        demoName: () -> String = { DEMO_NAME },
    ) : this(
        link = ConnectionManager(
            context.applicationContext, finder, prefs, scope, JblLinkFactory(context.applicationContext),
            autoConnect = autoConnect,
            demoTransport = demoTransport,
            demoName = demoName,
        ),
        candidates = finder::candidates,
        foreground = AndroidForegroundHost(context, finder),
        scope = scope,
    )

    @Volatile
    private var session: JblSession? = null

    init {
        start() // last: runSession reads the fields above
    }

    override suspend fun CoroutineScope.runSession(conn: JblConnection) {
        val s = JblSession(conn, link.state.value.deviceName)
        session = s
        // Frames are folded into the status by the session itself; each change is published from here.
        launch { s.status.collect { publish(s) } }
        try {
            commands.withLock { s.start() }
        } catch (e: HeadphoneCommandException) {
            log.w("initial read failed: ${e.message}")
            mutableEvents.tryEmit(HeadphoneEvent.Error("Connected, but reading the headphones failed: ${e.message}", e))
        }
        // Which probes answered can change without a status change: publish once more with the final set.
        publish(s)
        mutableEvents.tryEmit(HeadphoneEvent.Connected)
        pollWhileOpen(conn, BATTERY_POLL_MS) { commands.withLock { s.readBattery() } }
    }

    /** The base runs this once the session and every child above ended (the 0.75 bug cleared it right after launch). */
    override fun onSessionEnd(conn: JblConnection) {
        session = null
    }

    private fun publish(s: JblSession) {
        mutableState.update { JblStateMapping.apply(it, s.status.value, s.model, s.answered) }
    }

    /** One command at a time, with the busy flag up; the session reads back what the command touched. */
    private suspend fun <T> command(block: suspend (JblSession) -> T): T = commands.withLock {
        val s = session?.takeIf { it.conn.isOpen } ?: throw HeadphoneCommandException(NOT_CONNECTED)
        mutableState.update { it.copy(isBusy = true) }
        try {
            block(s)
        } finally {
            publish(s)
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    // ---- What JBL headsets have --------------------------------------------------------------

    override suspend fun refresh() = command { it.readAll() }

    override suspend fun setMode(index: Int) = command { it.setMode(index) }

    override suspend fun setVendorControl(id: String, value: Int) = command { it.setVendorControl(id, value) }

    // ---- What they don't have (or Earshot doesn't drive yet) --------------------------------
    // Answered by the base with [UNSUPPORTED]: custom modes, favourites, the Bose toggles, the
    // three-band EQ (the headset's own ten bands are the "EQ preset" control), renaming and power
    // off (`97` is on the write guard's never list). The ANC level, EQ preset and voice prompts are
    // vendor controls; the live noise card has nothing behind it on this protocol.

    override suspend fun setNoise(settings: NoiseSettings): Unit = unsupported("Noise settings")

    override fun unsupportedMessage(feature: String): String = UNSUPPORTED

    // ---- Lab (the documented reads, see JblLab) ------------------------------------------------

    override fun labProfile(): LabProfile? = session?.conn?.takeIf { it.isOpen }?.let { JblLab.profile() }

    override fun scanLab(groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        val conn = session?.conn?.takeIf { it.isOpen } ?: throw HeadphoneCommandException(NOT_CONNECTED)
        emitAll(JblLab.scan(conn, groupIds, deep))
    }

    /** Through [JblLog], so the repository's lines stay in the Lab's connection report. */
    private object JblSessionLog : SessionLog {
        override fun d(msg: String) = JblLog.d(msg)
        override fun i(msg: String) = JblLog.i(msg)
        override fun w(msg: String, t: Throwable?) = JblLog.w(msg, t)
    }

    companion object {
        const val DEMO_NAME = "Virtual JBL Wave Buds 2 (demo)"
        const val UNSUPPORTED = "Earshot's JBL driver (Experimental) doesn't support that setting yet"
        private const val BATTERY_POLL_MS = 5 * 60_000L
    }
}

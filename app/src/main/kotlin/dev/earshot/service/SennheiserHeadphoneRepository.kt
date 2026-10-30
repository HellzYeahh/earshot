package dev.earshot.service

import android.content.Context
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.ConnectionManager
import dev.earshot.bt.DeviceCandidate
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
import dev.earshot.bt.LinkSource
import dev.earshot.bt.SennheiserConnection
import dev.earshot.bt.SennheiserLinkFactory
import dev.earshot.bt.SennheiserLog
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.NoiseSettings
import dev.earshot.service.lab.SennheiserLab
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
 * [HeadphoneRepository] for Sennheiser headsets on GAIA v3 (Experimental). The same
 * [ConnectionManager] policy as the other stacks (reconnects, Bluetooth on/off, "close Smart
 * Control"), over [SennheiserConnection]; the protocol work is [SennheiserSession] and the mapping
 * [SennheiserStateMapping], both Android-free and tested against a virtual Momentum 4. The shared
 * part (state, events, connect, the session lifetime) is [SessionRepository].
 *
 * Pushes keep the state live after the registration; the battery is also re-read every
 * [BATTERY_POLL_MS] in case a push is missed. Every command re-reads what it touched before it
 * returns. Anything Sennheiser headsets don't have (or Earshot doesn't drive) throws a readable
 * [HeadphoneCommandException]: Tools let anyone try a setting.
 */
class SennheiserHeadphoneRepository internal constructor(
    link: LinkSource<SennheiserConnection>,
    candidates: suspend () -> List<DeviceCandidate>,
    foreground: ForegroundHost,
    scope: CoroutineScope,
) : SessionRepository<SennheiserConnection>(link, candidates, foreground, scope, SennheiserSessionLog) {

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
            context.applicationContext, finder, prefs, scope, SennheiserLinkFactory(),
            autoConnect = autoConnect,
            demoTransport = demoTransport,
            demoName = demoName,
        ),
        candidates = finder::candidates,
        foreground = AndroidForegroundHost(context, finder),
        scope = scope,
    )

    @Volatile
    private var session: SennheiserSession? = null

    init {
        start() // last: runSession reads the fields above
    }

    override suspend fun CoroutineScope.runSession(conn: SennheiserConnection) {
        val s = SennheiserSession(conn, link.state.value.deviceName)
        session = s
        // Collect pushes before the registration: each one is answered with a state dump.
        launch { conn.pushes.collect { frame -> s.onPush(frame) } }
        launch { s.status.collect { publish(s) } }
        try {
            commands.withLock { s.start() }
        } catch (e: HeadphoneCommandException) {
            log.w("initial read failed: ${e.message}")
            mutableEvents.tryEmit(HeadphoneEvent.Error("Connected, but reading the headphones failed: ${e.message}", e))
        }
        // The status collector normally published a (maybe nameless) identity already; this is the guard if it hasn't run.
        val modelName = s.model?.let { "Sennheiser ${it.displayName}" }
        identifyIfUnread(conn, modelName ?: link.state.value.deviceName, modelName)
        mutableEvents.tryEmit(HeadphoneEvent.Connected)
        pollWhileOpen(conn, BATTERY_POLL_MS) { commands.withLock { s.readBattery() } }
    }

    /** The base runs this once the session and every child above ended (the 0.75 bug cleared it right after launch). */
    override fun onSessionEnd(conn: SennheiserConnection) {
        session = null
    }

    private fun publish(s: SennheiserSession) {
        mutableState.update { SennheiserStateMapping.apply(it, s.status.value, s.model) }
    }

    /** One command at a time, with the busy flag up; the session re-reads what the command touched. */
    private suspend fun <T> command(block: suspend (SennheiserSession) -> T): T = commands.withLock {
        val s = session?.takeIf { it.conn.isOpen } ?: throw HeadphoneCommandException(NOT_CONNECTED)
        mutableState.update { it.copy(isBusy = true) }
        try {
            block(s)
        } finally {
            publish(s)
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    // ---- What Sennheiser headsets have -----------------------------------------------------

    override suspend fun refresh() = command { it.readAll() }

    override suspend fun setMode(index: Int) = command { it.setMode(index) }

    override suspend fun setNoise(settings: NoiseSettings) = command { it.setNoise(settings) }

    override suspend fun setEq(eq: HeadphoneEq) = command { it.setEq(mutableState.value.eq, eq) }

    override suspend fun setVendorControl(id: String, value: Int) = command { it.setVendorControl(id, value) }

    // ---- What they don't have (or Earshot doesn't drive yet) -------------------------------
    // Answered by the base with [UNSUPPORTED]: custom modes, favourites, the Bose toggles and
    // power off. Sidetone, auto-off, auto-answer and smart pause are vendor controls here (their
    // scales differ from Bose's). Rename (`0x2801`) is on the write guard's block-list: Smart
    // Control 4.9.3 keeps it disabled too. Connecting a paired device (`0x1402`) is safe but not
    // wired yet; disconnect and delete are never sent.

    override fun unsupportedMessage(feature: String): String = UNSUPPORTED

    // ---- Lab (discovery and the documented GETs, see SennheiserLab) ------------------------

    override fun labProfile(): LabProfile? = session?.conn?.takeIf { it.isOpen }?.let { SennheiserLab.profile(it.features) }

    override fun scanLab(groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        val conn = session?.conn?.takeIf { it.isOpen } ?: throw HeadphoneCommandException(NOT_CONNECTED)
        emitAll(SennheiserLab.scan(conn, groupIds, deep))
    }

    /** Through [SennheiserLog], so the repository's lines stay in the Lab's connection report. */
    private object SennheiserSessionLog : SessionLog {
        override fun d(msg: String) = SennheiserLog.d(msg)
        override fun i(msg: String) = SennheiserLog.i(msg)
        override fun w(msg: String, t: Throwable?) = SennheiserLog.w(msg, t)
    }

    companion object {
        const val DEMO_NAME = "Virtual MOMENTUM 4 (demo)"
        const val UNSUPPORTED = "Earshot's Sennheiser driver (Experimental) doesn't support that setting yet"
        private const val BATTERY_POLL_MS = 10 * 60_000L
    }
}

package dev.earshot.service

import android.content.Context
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.ConnectionManager
import dev.earshot.bt.DeviceCandidate
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
import dev.earshot.bt.LinkSource
import dev.earshot.bt.SonyConnection
import dev.earshot.bt.SonyLinkFactory
import dev.earshot.bt.SonyLog
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.NoiseSettings
import dev.earshot.service.lab.SonyLab
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
 * [HeadphoneRepository] for Sony headphones on MDR v2 (Experimental). The same [ConnectionManager]
 * policy as the other stacks (reconnects, Bluetooth on/off, "close Sony Sound Connect"), over
 * [SonyConnection] and a [SonySession] per connection; the shared part is [SessionRepository]:
 *  - connect: the init sequence, then everything the headset announced is mapped by
 *    [SonyStateMapping];
 *  - pushes: the headset sends NTFY frames for every change (also from its own buttons); battery
 *    is also re-read every [BATTERY_POLL_MS] in case a model doesn't push it;
 *  - commands: the three noise modes, the noise settings automations send, every vendor control,
 *    power off. Each reads its setting back, so the screen shows what the headphones did.
 * Everything else throws a readable [HeadphoneCommandException]: Tools let anyone try a setting.
 */
class SonyHeadphoneRepository internal constructor(
    link: LinkSource<SonyConnection>,
    candidates: suspend () -> List<DeviceCandidate>,
    foreground: ForegroundHost,
    scope: CoroutineScope,
    /** The session's clock for the confirmation-prompt window; tests pass the virtual one. */
    private val clock: () -> Long = System::currentTimeMillis,
) : SessionRepository<SonyConnection>(link, candidates, foreground, scope, SonySessionLog) {

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
            context.applicationContext, finder, prefs, scope, SonyLinkFactory(),
            autoConnect = autoConnect,
            demoTransport = demoTransport,
            demoName = demoName,
        ),
        candidates = finder::candidates,
        foreground = AndroidForegroundHost(context, finder),
        scope = scope,
    )

    @Volatile
    private var session: SonySession? = null

    init {
        start() // last: runSession reads the fields above
    }

    override suspend fun CoroutineScope.runSession(conn: SonyConnection) {
        val s = SonySession(conn, clock)
        session = s
        launch {
            conn.unsolicited.collect { m ->
                s.onPush(m)?.let { mutableEvents.tryEmit(HeadphoneEvent.Info(it)) }
                publish(s.snapshot)
            }
        }
        try {
            commands.withLock { publish(s.initialize()) }
        } catch (e: HeadphoneCommandException) {
            log.w("init failed: ${e.message}")
            mutableEvents.tryEmit(HeadphoneEvent.Error("Connected, but reading the headphones failed: ${e.message}", e))
        }
        mutableEvents.tryEmit(HeadphoneEvent.Connected)
        pollWhileOpen(conn, BATTERY_POLL_MS) { commands.withLock { publish(s.readBattery()) } }
    }

    /** The base runs this once the session and every child above ended (the 0.75 bug cleared it right after launch). */
    override fun onSessionEnd(conn: SonyConnection) {
        session = null
    }

    private fun publish(snapshot: SonySnapshot) {
        mutableState.update { SonyStateMapping.apply(it, snapshot) }
    }

    private fun requireSession(): SonySession = session?.takeIf { openConnection() != null }
        ?: throw HeadphoneCommandException(NOT_CONNECTED)

    /** One command at a time; the session reads the setting back before [publish]. */
    private suspend fun command(what: String, block: suspend (SonySession) -> SonySnapshot) = commands.withLock {
        val s = requireSession()
        mutableState.update { it.copy(isBusy = true) }
        try {
            log.i("set $what")
            publish(block(s))
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    // ---- What Sony headphones have -----------------------------------------------------------

    override suspend fun refresh() = commands.withLock { publish(requireSession().readValues()) }

    override suspend fun setMode(index: Int) = command("mode $index") { it.setMode(index) }

    override suspend fun setNoise(settings: NoiseSettings) = command("noise") { it.setNoise(settings) }

    override suspend fun setVendorControl(id: String, value: Int) = command(id) { it.setVendorControl(id, value) }

    override suspend fun powerOff() = commands.withLock { requireSession().powerOff() }

    // ---- What it doesn't have (or Earshot doesn't drive yet) --------------------------------
    // Custom modes, favourites, the 3-band EQ (Sony's EQ has its own bands and presets: they are
    // vendor controls), sidetone, the Bose toggles, renaming, switching, pairing mode: the base
    // answers them with [UNSUPPORTED].

    override fun unsupportedMessage(feature: String): String = UNSUPPORTED

    // ---- Lab (GET inquiries for the announced functions, see SonyLab) ----------------------

    override fun labProfile(): LabProfile? {
        val s = session ?: return null
        if (openConnection() == null) return null
        return SonyLab.profile(s.snapshot)
    }

    override fun scanLab(groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        val s = requireSession()
        emitAll(SonyLab.scan(requireConnection(), s.snapshot, groupIds, deep))
    }

    /** Through [SonyLog], so the repository's lines stay in the Lab's connection report. */
    private object SonySessionLog : SessionLog {
        override fun d(msg: String) = SonyLog.d(msg)
        override fun i(msg: String) = SonyLog.i(msg)
        override fun w(msg: String, t: Throwable?) = SonyLog.w(msg, t)
    }

    companion object {
        const val DEMO_NAME = "Virtual Sony WH-1000XM5 (demo)"
        const val UNSUPPORTED = "Earshot's Sony driver doesn't support that setting yet"
        private const val BATTERY_POLL_MS = 120_000L
    }
}

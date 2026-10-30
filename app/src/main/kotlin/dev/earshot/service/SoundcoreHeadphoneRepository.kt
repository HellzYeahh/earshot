package dev.earshot.service

import android.content.Context
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.ConnectionManager
import dev.earshot.bt.DeviceCandidate
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
import dev.earshot.bt.LinkSource
import dev.earshot.bt.SoundcoreConnection
import dev.earshot.bt.SoundcoreLinkFactory
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.NoiseSettings
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
import dev.earshot.service.session.AndroidForegroundHost
import dev.earshot.service.session.AndroidSessionLog
import dev.earshot.service.session.ForegroundHost
import dev.earshot.service.session.SessionLog
import dev.earshot.service.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * [HeadphoneRepository] for Soundcore earbuds and headphones. The same [ConnectionManager] policy
 * as Bose and the Crusher (reconnects, Bluetooth on/off, "close the soundcore app"), over
 * [SoundcoreConnection]; the shared part is [SessionRepository]. Two paths share it:
 *  - the **Sleep A30** (D1301, tested on a real pair), unchanged: noise cancelling on/off
 *    (`06 87`, through two listening modes), tap controls (`10 94`) and the sleep options;
 *  - **every other model on the vendor service** (the table in [SoundcoreModel]): the state
 *    (`01 01`) is read through [SoundcoreStateReader] for battery, charging, firmware and mode;
 *    models whose layout the notes document and whose state matches it (size, length byte,
 *    serial) can also change their ambient mode, noise-cancelling type and level, wind and
 *    settings ([SoundcoreWrites]). Anything else is read-only and says why.
 * Pushes (`01 01`, battery `01 03`, charging `01 04`, modes `06 01`) arrive by themselves and apply
 * at once, also during a write: the mappings update the state field by field. The state is also
 * re-read once a minute. Every write re-reads the state, so the screen shows what the earbuds did;
 * a model that acknowledges and ignores a write is reported as such.
 * Everything unsupported throws a readable [HeadphoneCommandException]: Tools let anyone try a setting.
 */
class SoundcoreHeadphoneRepository internal constructor(
    link: LinkSource<SoundcoreConnection>,
    candidates: suspend () -> List<DeviceCandidate>,
    foreground: ForegroundHost,
    scope: CoroutineScope,
    log: SessionLog = AndroidSessionLog(TAG),
) : SessionRepository<SoundcoreConnection>(link, candidates, foreground, scope, log, notConnectedMessage = NOT_CONNECTED_EARBUDS) {

    constructor(
        context: Context,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        demoTransport: () -> (() -> ByteTransport)? = { null },
        autoConnect: () -> Boolean = { true },
        demoName: () -> String = { DEMO_NAME },
        /** Shared with the other stacks (`AppContainer`); one store per process either way. */
        prefs: DevicePrefs = DevicePrefs(context),
        finder: DeviceFinder = DeviceFinder(context.applicationContext, prefs),
    ) : this(
        link = ConnectionManager(
            context.applicationContext, finder, prefs, scope, SoundcoreLinkFactory(),
            autoConnect = autoConnect,
            demoTransport = demoTransport,
            demoName = demoName,
        ),
        candidates = finder::candidates,
        foreground = AndroidForegroundHost(context, finder),
        scope = scope,
    )

    /** The model of the session in progress: the Sleep A30 unless the connection says otherwise. */
    @Volatile
    private var model: SoundcoreModel = SoundcoreModel.SLEEP_A30

    /** Newer models: the last state that parsed, which the writes are built from. */
    @Volatile
    private var latest: SoundcoreState? = null

    private val sleepA30: Boolean get() = model == SoundcoreModel.SLEEP_A30

    init {
        start() // last: runSession reads the fields above
    }

    override suspend fun CoroutineScope.runSession(conn: SoundcoreConnection) {
        model = conn.model ?: SoundcoreModel.SLEEP_A30
        latest = null
        launch {
            conn.unsolicited.collect { packet -> applyPush(packet) }
        }
        try {
            readState(conn)
            if (!sleepA30) sessionInit(conn)
        } catch (e: HeadphoneCommandException) {
            log.w("initial read failed: ${e.message}")
            mutableEvents.tryEmit(HeadphoneEvent.Error("Connected, but reading the earbuds failed: ${e.message}", e))
        }
        // A failed read, or a Sleep A30 body that didn't parse, published nothing. The Sleep A30's
        // capabilities don't depend on a read (Tested on our own pair); a newer model's do, so it
        // keeps the defaults, which say Experimental.
        identifyIfUnread(
            conn, model.displayName, model.displayName,
            capabilities = if (sleepA30) SoundcoreStateMapping.capabilities() else null,
        )
        mutableEvents.tryEmit(HeadphoneEvent.Connected)
        pollWhileOpen(conn, STATE_POLL_MS, what = "state poll") { commands.withLock { readState(conn) } }
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
            log.w("state body of ${body.size} bytes is not the Sleep A30 layout")
            return
        }
        // No serial here: on the Sleep A30 it embeds the earbuds' address, already in the log anyway.
        log.d("state: anc=${parsed.ancOn} battery=${parsed.leftPercent}/${parsed.rightPercent} fw=${parsed.firmware} tapsOff=${parsed.tapControlsDisabled}")
        mutableState.update { SoundcoreStateMapping.apply(it, parsed) }
    }

    /**
     * Every other model: read the state through its table. The connection may write only if the
     * state matched the layout Earshot knows (size, length byte, serial); otherwise it stays
     * read-only, and the note says why. The serial and the raw bytes are never logged.
     */
    private fun applyModelState(conn: SoundcoreConnection, body: ByteArray) {
        val parsed = SoundcoreStateReader.read(model, body)
        if (parsed == null) {
            log.w("${model.code}: state of ${body.size} bytes matches no layout")
            latest = null
            conn.unlockWrites(false)
            mutableState.update { SoundcoreModelMapping.unreadable(it, model, body.size) }
            return
        }
        conn.unlockWrites(parsed.writable)
        latest = parsed
        log.d("${model.code}: ${body.size} bytes, battery=${parsed.leftPercent}/${parsed.rightPercent}/${parsed.overallPercent}, mode=${parsed.ambient}, writable=${parsed.writable}")
        mutableState.update { SoundcoreModelMapping.apply(it, parsed) }
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
                log.d("session init not answered: ${e.message}")
            }
        }
    }

    private fun applyPush(packet: SoundcorePacket) {
        if (sleepA30) {
            when (packet.command) {
                SoundcoreCommands.REQUEST_STATE -> applyState(packet.body)
                SoundcoreCommands.PUSH_BATTERY -> D1301State.parseBattery(packet.body)?.let { (l, r) ->
                    mutableState.update { SoundcoreStateMapping.withBattery(it, l, r) }
                }
                else -> log.d("push ${SoundcorePacket.commandName(packet.command)} ignored")
            }
            return
        }
        val conn = link.connection.value
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
            else -> log.d("push ${SoundcorePacket.commandName(packet.command)} ignored")
        }
    }

    private fun publish(s: SoundcoreState) {
        latest = s
        mutableState.update { SoundcoreModelMapping.apply(it, s) }
    }

    /** One command at a time; afterwards the state is re-read so the UI shows what the earbuds did. */
    private suspend fun write(name: String, command: Int, value: Int) = write(name, command, byteArrayOf(value.toByte()))

    // checkOpen = false throughout: these never checked isOpen (a closed link fails in request itself).
    private suspend fun write(name: String, command: Int, body: ByteArray) = commands.withLock {
        val conn = requireConnection(checkOpen = false)
        mutableState.update { it.copy(isBusy = true) }
        try {
            log.i("$name → ${SoundcorePacket.commandName(command)} ${body.joinToString("") { "%02x".format(it) }}")
            conn.request(command, body)
            readState(conn)
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    /**
     * A change to a newer model, built from the last state that parsed ([SoundcoreWrites] refuses
     * with a sentence when the model is read-only or the transition isn't one the app makes).
     * After the write the state is re-read, and a change that did not show up is reported: the
     * earbuds may acknowledge a write and discard it.
     */
    private suspend fun writeModel(name: String, build: (SoundcoreState) -> SoundcorePacket) = commands.withLock {
        val conn = requireConnection(checkOpen = false)
        val before = latest ?: throw HeadphoneCommandException("The earbuds haven't reported their state yet")
        val packet = try {
            build(before)
        } catch (e: SoundcoreWriteRefused) {
            throw HeadphoneCommandException(e.message ?: UNSUPPORTED, e)
        }
        mutableState.update { it.copy(isBusy = true) }
        try {
            // Command and size only: the body is the earbuds' own settings and nothing needs logging.
            log.i("$name → ${SoundcorePacket.commandName(packet.command)} (${packet.body.size} bytes)")
            conn.request(packet.command, packet.body)
            readState(conn)
            if (!took(before, packet)) {
                delay(VERIFY_DELAY_MS)
                readState(conn)
                if (!took(before, packet)) throw HeadphoneCommandException(NOT_APPLIED)
            }
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    private fun took(before: SoundcoreState, packet: SoundcorePacket): Boolean =
        latest?.let { SoundcoreWrites.confirms(before, it, packet) } == true

    // ---- What the earbuds have --------------------------------------------------------------

    override suspend fun refresh() {
        val conn = requireConnection(checkOpen = false)
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
        if (!sleepA30) unsupported("Touch controls")
        write("tap controls", SoundcoreCommands.SET_TAP_DISABLED, if (enabled) 0 else 1)
    }

    override suspend fun setVendorControl(id: String, value: Int) {
        if (!sleepA30) return writeModel(id) { SoundcoreWrites.control(it, id, value) }
        val (command, body) = SoundcoreStateMapping.write(id, value)
            ?: throw HeadphoneCommandException("The Sleep A30 has no setting \"$id\"")
        write(id, command, body)
    }

    // ---- What it doesn't have (or Earshot doesn't drive yet): the base, with [UNSUPPORTED] ----

    override fun unsupportedMessage(feature: String): String = UNSUPPORTED

    // ---- Lab (the documented reads only, see SoundcoreLab) ---------------------------------

    override fun labProfile(): LabProfile? = openConnection()?.let { SoundcoreLab.profile(it.model) }

    override fun scanLab(groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        emitAll(SoundcoreLab.scan(requireConnection(), groupIds, deep))
    }

    companion object {
        private const val TAG = "Earshot/Soundcore"
        const val DEMO_NAME = "Virtual Sleep A30 (demo)"
        const val UNSUPPORTED = "Earshot's Soundcore driver doesn't support that setting on these earbuds yet"
        const val NOT_APPLIED = "The earbuds acknowledged that but didn't change. Their firmware may differ from the layout Earshot knows."
        const val NOT_CONNECTED_EARBUDS = "Earbuds not connected"
        private const val STATE_POLL_MS = 60_000L
        private const val SESSION_INIT_GAP_MS = 300L
        private const val VERIFY_DELAY_MS = 500L
    }
}

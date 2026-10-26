package dev.earshot.service.session

import dev.earshot.bt.DeviceCandidate
import dev.earshot.bt.LinkSource
import dev.earshot.bt.ManagedLink
import dev.earshot.core.ButtonAction
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCandidate
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.core.Sidetone
import dev.earshot.core.VoicePrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * What every vendor repository over a [LinkSource] (a `ConnectionManager` of [C]) has in common, so
 * a new driver writes only its session: the state and events, the command mutex, connection state
 * → [HeadphoneState], one session per open link, connect / disconnect / device choice, the
 * not-connected guard and the "not available" answers for what a headset lacks.
 *
 * What stays per driver, on purpose (it is observable and differs today): the [runSession] body
 * (init steps, what a silent init does, how pushes merge, poll period and busy flag), whether and
 * how command errors are wrapped, the Error texts, and the [notConnectedMessage] wording.
 *
 * **Init order:** a subclass calls [start] as the last statement of its own initialisation, after
 * every property [runSession] touches. The base must not launch anything from its own `init`: that
 * runs before the subclass fields exist, and on `Dispatchers.Default` (or a test dispatcher) the
 * first session could run against a half-built object.
 */
abstract class SessionRepository<C : ManagedLink>(
    protected val link: LinkSource<C>,
    /** Bonded devices that might be the headset (`DeviceFinder.candidates`), for the picker. */
    private val candidates: suspend () -> List<DeviceCandidate>,
    private val foreground: ForegroundHost,
    protected val scope: CoroutineScope,
    protected val log: SessionLog,
    /** What a command says without an open link ("Headphones not connected", "Earbuds not connected"). */
    private val notConnectedMessage: String = NOT_CONNECTED,
    initialState: HeadphoneState = HeadphoneState(),
) : HeadphoneRepository {

    protected val mutableState = MutableStateFlow(initialState)
    final override val state: StateFlow<HeadphoneState> = mutableState.asStateFlow()

    protected val mutableEvents = MutableSharedFlow<HeadphoneEvent>(extraBufferCapacity = 16)
    final override val events: SharedFlow<HeadphoneEvent> = mutableEvents.asSharedFlow()

    /** Serialises commands so a multi-request command never interleaves with another. */
    protected val commands = Mutex()

    @Volatile
    private var started = false

    /** Follow the link: call once, at the end of the subclass's initialisation (see the class comment). */
    protected fun start() {
        check(!started) { "start() runs once" }
        started = true
        scope.launch {
            var wasConnected = false
            link.state.collect { cs ->
                val connected = cs.status == ConnectionStatus.CONNECTED
                mutableState.update { s ->
                    // Drop stale headphone data when the link goes; the notification and UI
                    // should not show yesterday's battery.
                    if (connected || !wasConnected) s.copy(connection = cs) else HeadphoneState(connection = cs)
                }
                if (wasConnected && !connected) mutableEvents.tryEmit(HeadphoneEvent.Disconnected)
                wasConnected = connected
            }
        }
        scope.launch {
            // collectLatest cancels the previous session (and waits for it) when the link changes.
            // The coroutineScope keeps the session alive until every child it launched has ended,
            // so onSessionEnd runs when the session really ends; 0.75's Sony and Sennheiser cleared
            // their session as soon as the poll was launched, which broke every later command.
            link.connection.collectLatest { conn ->
                if (conn != null) {
                    try {
                        coroutineScope { runSession(conn) }
                    } finally {
                        onSessionEnd(conn)
                    }
                }
            }
        }
    }

    /**
     * One session on the open link [conn]: subscribe to pushes, read the headset, emit
     * [HeadphoneEvent.Connected], start any poll. Children launched here live until the link
     * changes. Returning early (a silent link closed on purpose) ends the session.
     */
    protected abstract suspend fun CoroutineScope.runSession(conn: C)

    /** After [runSession] for [conn] and all its children ended, before the next session starts. */
    protected open fun onSessionEnd(conn: C) {}

    // ---- Connection ------------------------------------------------------------------------

    final override fun connect() {
        // The foreground service keeps the process alive; skip it without the connect permission
        // (the manager reports NO_PERMISSION and the UI asks for it).
        if (foreground.canStart()) foreground.start()
        link.connect()
    }

    final override fun disconnect() {
        link.disconnect()
        foreground.stop()
    }

    /** Drop the connection without stopping the foreground service: another repository is taking over. */
    fun releaseConnection() = link.disconnect()

    /** Bonded devices that might be the headset, for a picker when auto-detection is ambiguous. */
    final override suspend fun deviceCandidates(): List<HeadphoneCandidate> = candidates().map { it.toHeadphoneCandidate() }

    /** Remember [address] as the headset and connect to it. The user picked it: the service starts regardless. */
    final override suspend fun chooseDevice(address: String, name: String?) {
        foreground.start()
        link.chooseDevice(address, name)
    }

    // ---- Guards and helpers ------------------------------------------------------------------

    /** The link, when it is open. */
    protected fun openConnection(): C? = link.connection.value?.takeIf { it.isOpen }

    /**
     * The link or [HeadphoneCommandException] with [notConnectedMessage]. [checkOpen] false keeps
     * a driver that never checked `isOpen` (Soundcore) exactly as it was: the request then fails
     * on the closed link itself.
     */
    protected fun requireConnection(checkOpen: Boolean = true): C =
        (if (checkOpen) openConnection() else link.connection.value)
            ?: throw HeadphoneCommandException(notConnectedMessage)

    /**
     * The shared poll: every [periodMs] while [conn] is open, until [stopWhen] says so, run [read].
     * A failed read is logged at debug level as "[what] failed" and the poll goes on; cancellation
     * (the session ending) is passed through.
     */
    protected fun CoroutineScope.pollWhileOpen(
        conn: C,
        periodMs: Long,
        what: String = "battery poll",
        stopWhen: () -> Boolean = { false },
        read: suspend () -> Unit,
    ): Job = launch {
        while (conn.isOpen) {
            delay(periodMs)
            if (!conn.isOpen || stopWhen()) break
            try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d("$what failed: ${e.message}")
            }
        }
    }

    // ---- What a headset may not have ----------------------------------------------------------

    /**
     * The sentence for a [feature] this driver doesn't drive ("Custom modes", "Renaming" …). The
     * default is the Crusher's per-feature wording; Soundcore, Sennheiser and Sony return their
     * one driver sentence. Members with a default in [HeadphoneRepository] keep that default.
     */
    protected open fun unsupportedMessage(feature: String): String = "$feature isn't available on these headphones"

    protected fun unsupported(feature: String): Nothing = throw HeadphoneCommandException(unsupportedMessage(feature))

    override suspend fun saveCustomMode(index: Int, name: String, settings: NoiseSettings) = unsupported("Custom modes")
    override suspend fun setFavorites(indices: Set<Int>) = unsupported("Favourite modes")
    override suspend fun setEq(eq: HeadphoneEq) = unsupported("The headphones' own equaliser")
    override suspend fun setSidetone(level: Sidetone, persistent: Boolean) = unsupported("Sidetone")
    override suspend fun setAutoPlayPause(enabled: Boolean) = unsupported("Auto play/pause")
    override suspend fun setAutoAnswer(enabled: Boolean) = unsupported("Auto answer")
    override suspend fun setMultipoint(enabled: Boolean) = unsupported("Multipoint")
    override suspend fun setVoicePrompts(prompts: VoicePrompts) = unsupported("Voice prompts")
    override suspend fun setShortcutButton(action: ButtonAction) = unsupported("The shortcut button")
    override suspend fun setDeviceName(name: String) = unsupported("Renaming")
    override suspend fun switchToDevice(address: String) = unsupported("Switching devices")
    override suspend fun enterPairingMode() = unsupported("Pairing mode")
    override suspend fun powerOff() = unsupported("Power off")

    companion object {
        const val NOT_CONNECTED = "Headphones not connected"
    }
}

/** A bonded device as the UI's picker sees it; the one mapping every repository uses. */
fun DeviceCandidate.toHeadphoneCandidate(): HeadphoneCandidate =
    HeadphoneCandidate(address = address, name = name, looksLikeBose = looksLikeBose, isAudioDevice = isAudioDevice)

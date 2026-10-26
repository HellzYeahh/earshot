package dev.earshot.conformance

import dev.earshot.bt.ConnectionManager
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.LinkFactory
import dev.earshot.bt.ManagedLink
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.virtual.DemoDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/** The driver contract [DriverConformanceTest] checks on every virtual device (roadmap F2). */
enum class Check { CONNECT, REFRESH, MODES, BATTERY_PUSH, GUARD, LINK_LOSS, RECONNECT, IDENTITY }

/** One frame the phone wrote, decoded with the stack's own frame buffer: what it is, and whether it only reads. */
data class Sent(val label: String, val isRead: Boolean)

/**
 * One driver's virtual device, driven the way its repository drives the session (F2 phase 1:
 * session level). Every repository builds its `ConnectionManager`, `DevicePrefs` and
 * `DeviceFinder` from an Android `Context` and logs through `android.util.Log`, so none can be built
 * in a JVM test; each rig repeats the repository's steps instead (connect sequence, command
 * wrapper, push handling) on top of the stack's real connection class and its virtual transport,
 * wrapped in a [SpyTransport]. Repository-level checks wait for the A6 seams.
 *
 * A rig is built outside `runTest` (so [notApplicable] can skip a check first) and does nothing
 * until [connect], which runs inside the test.
 */
interface ConformanceRig {
    val demo: DemoDevice
    val family: DeviceFamily
    val factory: LinkFactory<*>

    /** A Bluetooth name this product really advertises; the router must put it in [family]. */
    val bluetoothName: String

    /** The spy on the current connection's transport (a new one per [connect]). */
    val spy: SpyTransport

    /** The current connection. */
    val link: ManagedLink

    /** What the repository would publish now. */
    val state: HeadphoneState

    /** A fresh virtual transport to the same unit, adopted, then the stack's connect sequence. */
    suspend fun connect(): HeadphoneState

    suspend fun refresh(): HeadphoneState

    /** The repository's setMode; Bose ERROR frames come out as [HeadphoneCommandException], as in the repository. */
    suspend fun setMode(index: Int): HeadphoneState

    /** The mode the virtual unit itself plays, as a slot index; null when it has none. */
    fun deviceModeIndex(): Int?

    /** Make the unit push this battery level by itself. False: the stack has no battery push. */
    suspend fun pushBattery(percent: Int): Boolean

    /** Let the unit's battery be [percent], then run the repository's battery read. */
    suspend fun pollBattery(percent: Int): HeadphoneState

    /** Let pending work run and apply what the unit pushed, as the repository does. */
    suspend fun settle(): HeadphoneState

    /** Writes the stack's guard must refuse, each sent through the real connection's write path. */
    val forbiddenWrites: List<Pair<String, suspend () -> Unit>>

    /** The phone's bytes as the stack frames them, each classified by the stack's own read rule. */
    fun decode(bytes: ByteArray): List<Sent>

    /** The far end goes away: the virtual transport closes underneath the connection. */
    fun dropLink()

    /**
     * Right after [connect]: the stack renewed what makes the unit push on this connection (Bose
     * `[9.2]`, Bragi streaming reads, Sennheiser registrations, Sony prompts). Null: the stack has
     * nothing to renew (pushes come by themselves).
     */
    fun subscribed(): Boolean?

    /** Checks this stack cannot have, with the reason the test reports when it skips them. */
    val notApplicable: Map<Check, String> get() = emptyMap()

    /**
     * The repository's "not connected" check before every command. C6 turns it off to see the
     * stack itself fail on a closed link instead of hanging or pretending it worked.
     */
    var repositoryGuard: Boolean
}

/**
 * What every rig shares with its repository: the published [state], a connection that counts as
 * gone once it closed (the manager clears `connection` then, so the repository's commands throw
 * "not connected"), and pushes applied as they arrive or, for the stacks whose repository parks
 * them while a command holds its mutex (Bose, Bragi), merged right after the command.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class RigBase<P>(protected val test: TestScope, final override val bluetoothName: String) : ConformanceRig {

    private var currentSpy: SpyTransport? = null
    override val spy: SpyTransport get() = checkNotNull(currentSpy) { "connect() first" }

    final override var state: HeadphoneState = initialState()
        protected set

    /** The connection the manager would hand over, or null before the first [connect]. */
    protected abstract val connection: ManagedLink?

    override val link: ManagedLink get() = checkNotNull(connection) { "connect() first" }

    /** The repository parks pushes while a command runs and merges them afterwards (Bose, Bragi). */
    protected open val parksPushesDuringCommands: Boolean = false

    private val parked = ArrayDeque<P>()
    private var inCommand = false

    /** Fold one push into the state the way the repository does; null = no change. */
    protected abstract suspend fun applyPush(push: P, current: HeadphoneState): HeadphoneState?

    /** The state right after the manager reports CONNECTED: empty but for the connection. */
    protected fun initialState() = HeadphoneState(
        connection = ConnectionState(ConnectionStatus.CONNECTED, deviceName = bluetoothName, deviceAddress = ADDRESS),
    )

    /** A new connection: a new spy, the state wiped (the repositories reset it on disconnect). */
    protected fun beginSession(spy: SpyTransport) {
        currentSpy = spy
        state = initialState()
        synchronized(parked) { parked.clear() }
    }

    protected fun collectPushes(flow: Flow<P>) {
        test.backgroundScope.launch {
            flow.collect { push ->
                if (inCommand && parksPushesDuringCommands) synchronized(parked) { parked += push } else apply(push)
            }
        }
    }

    private suspend fun apply(push: P) {
        applyPush(push, state)?.let { state = it }
    }

    private suspend fun drainParked() {
        while (true) apply(synchronized(parked) { parked.removeFirstOrNull() } ?: break)
    }

    final override var repositoryGuard: Boolean = true

    /** `connectionManager.connection.value?.takeIf { it.isOpen } ?: throw` in every repository. */
    protected fun requireOpen() {
        if (repositoryGuard && connection?.isOpen != true) throw HeadphoneCommandException("Headphones not connected")
    }

    /** One command: the repository's guard, then [block]'s state is published and parked pushes merged on top. */
    protected suspend fun command(block: suspend (HeadphoneState) -> HeadphoneState): HeadphoneState {
        requireOpen()
        inCommand = true
        try {
            state = block(state).copy(connection = state.connection, isBusy = false, lastUpdatedMillis = 0L)
        } finally {
            inCommand = false
            drainParked()
        }
        return state
    }

    /** For the session-based stacks, whose repositories publish a mapping of the session's own status. */
    protected fun publish(next: HeadphoneState) {
        state = next.copy(connection = state.connection, isBusy = false, lastUpdatedMillis = 0L)
    }

    protected fun runCurrent() = test.testScheduler.runCurrent()

    /** One entry of [forbiddenWrites]: a label for the failure message and the attempt itself. */
    protected fun refused(label: String, attempt: suspend () -> Any?): Pair<String, suspend () -> Unit> = label to { attempt() }

    override suspend fun settle(): HeadphoneState {
        runCurrent()
        drainParked()
        return state
    }

    companion object {
        /** The address the connection manager gives every demo session. */
        const val ADDRESS = ConnectionManager.DEMO_ADDRESS
    }
}

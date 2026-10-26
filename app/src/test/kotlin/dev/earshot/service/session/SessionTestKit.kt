package dev.earshot.service.session

import dev.earshot.bt.ByteTransport
import dev.earshot.bt.LinkSource
import dev.earshot.bt.ManagedLink
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.IOException

/**
 * What `ConnectionManager` looks like to a repository, minus Android: the tests publish a link the
 * way the manager does (the connection first, then CONNECTED; on loss null, then DISCONNECTED) and
 * read back what the repository asked for.
 */
class FakeLinkSource<C : ManagedLink>(
    private val deviceName: String = "Test headset",
    private val deviceAddress: String = "00:11:22:33:44:55",
) : LinkSource<C> {
    override val state = MutableStateFlow(ConnectionState())
    override val connection = MutableStateFlow<C?>(null)
    override var isDemoSession: Boolean = false

    var connects = 0
        private set
    var disconnects = 0
        private set
    val chosen = mutableListOf<Pair<String, String?>>()

    override fun connect() { connects++ }
    override fun disconnect() { disconnects++ }
    override suspend fun chooseDevice(address: String, name: String?) { chosen += address to name }

    /** A session opened, as the manager publishes it. */
    fun open(conn: C) {
        connection.value = conn
        state.value = ConnectionState(ConnectionStatus.CONNECTED, deviceName, deviceAddress)
    }

    /** The session ended. */
    fun drop(message: String? = "Connection lost") {
        connection.value = null
        state.value = state.value.copy(status = ConnectionStatus.DISCONNECTED, message = message)
    }

    fun status(status: ConnectionStatus) {
        state.value = state.value.copy(status = status, deviceName = deviceName, deviceAddress = deviceAddress)
    }
}

/** Counts what a repository asked of the foreground service. */
class FakeForegroundHost(var allowed: Boolean = true) : ForegroundHost {
    var starts = 0
        private set
    var stops = 0
        private set

    override fun canStart(): Boolean = allowed
    override fun start() { starts++ }
    override fun stop() { stops++ }
}

/** Keeps the lines, prints nothing. */
class RecordingLog : SessionLog {
    val lines = mutableListOf<String>()
    override fun d(msg: String) { lines += "D $msg" }
    override fun i(msg: String) { lines += "I $msg" }
    override fun w(msg: String, t: Throwable?) { lines += "W $msg" }
}

/** A link that is open until closed. */
class FakeLink : ManagedLink {
    private val closed = CompletableDeferred<Throwable?>()
    override val isOpen: Boolean get() = !closed.isCompleted
    override suspend fun awaitClosed(): Throwable? = closed.await()
    override fun close() { closed.complete(null) }
}

/** A socket that opens and never says anything: another app holds the channel, or the far end is mute. */
class SilentTransport : ByteTransport {
    private val closed = CompletableDeferred<Unit>()
    val written = mutableListOf<ByteArray>()
    override val isConnected: Boolean get() = !closed.isCompleted
    /** Nothing ever arrives; completes normally on [close], as the contract asks. */
    override val incoming: Flow<ByteArray> = flow { closed.await() }
    override suspend fun write(bytes: ByteArray) {
        if (closed.isCompleted) throw IOException("closed")
        written += bytes
    }
    override fun close() { closed.complete(Unit) }
}

/** Every event [repo] emits from now on (subscribed eagerly, so none is dropped). */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.recordEvents(repo: HeadphoneRepository): List<HeadphoneEvent> {
    val events = mutableListOf<HeadphoneEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.events.collect { events += it } }
    return events
}

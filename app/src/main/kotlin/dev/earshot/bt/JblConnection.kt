package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.jbl.JblCommands
import dev.earshot.protocol.jbl.JblDeviceInfo
import dev.earshot.protocol.jbl.JblFrame
import dev.earshot.protocol.jbl.JblFrameBuffer
import dev.earshot.protocol.jbl.JblWriteGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.ContinuationInterceptor

/**
 * One H-AA session with a JBL headset over its Harman GATT service (or the virtual one in demo
 * mode). H-AA has no sequence numbers and no request ids: a reply is just the next frame of the
 * right kind, and a setting is often answered with nothing at all. So every frame the headset
 * sends goes to [attach]'s callback in arrival order (the session folds replies and pushes the
 * same way), and [request] only waits for the first frame its predicate accepts.
 *
 * Writes are paced [paceMs] apart (jbl-control: "frames written back to back get dropped by the
 * firmware") and each one passes [JblWriteGuard] first; a refused frame never reaches the
 * transport. The headset's heartbeats (`B1`) are echoed as the app does, once its device info has
 * arrived. Three requests in a row without a single frame back means the link is open but nobody
 * answers (JBL's app holds the control link: one client at a time): the connection closes with
 * [UnresponsiveLinkException], which the manager reports as the busy channel.
 */
class JblConnection(
    private val transport: ByteTransport,
    parentScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val paceMs: Long = PACE_MS,
) : ManagedLink {
    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default),
    )
    private val frames = JblFrameBuffer(onJunk = { n -> JblLog.w("dropped $n bytes outside a frame") })
    private val closed = CompletableDeferred<Throwable?>()
    private val writeLock = Mutex()
    private val requestLock = Mutex()
    private val waiters = CopyOnWriteArrayList<Waiter>()
    private val attachLock = Any()
    private val early = ArrayList<JblFrame>()
    private var onFrame: ((JblFrame) -> Unit)? = null
    private var lastWriteAt: Long? = null

    private class Waiter(val accepts: (JblFrame) -> Boolean) {
        val frame = CompletableDeferred<JblFrame>()
    }

    @Volatile
    override var isOpen: Boolean = true
        private set

    /** The latest `AA 12` device info; the headset sends one by itself right after the link opens. */
    @Volatile
    var deviceInfo: JblDeviceInfo? = null
        private set

    /** Frames received so far; [request] counts a timeout as silent only when none came in meanwhile. */
    @Volatile
    var framesReceived: Long = 0
        private set

    @Volatile
    private var silentRequests = 0

    init {
        scope.launch {
            try {
                transport.incoming.collect { chunk -> frames.feed(chunk).forEach(::dispatch) }
                frames.flush().forEach(::dispatch)
                finish(null)
            } catch (e: Throwable) {
                finish(e)
                if (e is CancellationException) throw e
            }
        }
    }

    /**
     * Hand every frame to [callback], in arrival order, from now on. Frames that came before (the
     * device info the headset sends on its own, a push) are replayed first.
     */
    fun attach(callback: (JblFrame) -> Unit) = synchronized(attachLock) {
        early.forEach(callback)
        early.clear()
        onFrame = callback
    }

    private fun dispatch(frame: JblFrame) {
        framesReceived++
        silentRequests = 0
        JblLog.d("<- $frame")
        if (frame.command == JblCommands.DEVICE_INFO) JblDeviceInfo.parse(frame)?.let { deviceInfo = it }
        synchronized(attachLock) {
            val callback = onFrame
            if (callback != null) callback(frame) else if (early.size < EARLY_CAP) early += frame
        }
        if (frame.command == JblCommands.HEARTBEAT && deviceInfo != null) {
            scope.launch { runCatching { send(JblCommands.heartbeatEcho()) } }
        }
        waiters.forEach { if (it.accepts(frame)) it.frame.complete(frame) }
    }

    /**
     * Send [frame] (paced, guarded). Throws [HeadphoneCommandException] when the guard refuses it or
     * the link is closed or lost.
     */
    suspend fun send(frame: JblFrame) {
        if (!isOpen) throw HeadphoneCommandException(NOT_CONNECTED)
        if (!JblWriteGuard.isAllowed(frame)) {
            JblLog.w("refused $frame")
            throw HeadphoneCommandException("Earshot doesn't send that command to JBL headphones (${JblCommands.name(frame.command)})")
        }
        writeLock.withLock {
            lastWriteAt?.let { last -> (last + paceMs - clock()).takeIf { it > 0 }?.let { delay(it) } }
            if (!isOpen) throw HeadphoneCommandException(NOT_CONNECTED)
            JblLog.d("-> $frame")
            try {
                transport.write(frame.encode())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(e)
                throw HeadphoneCommandException("Connection to the headphones was lost", e)
            }
            lastWriteAt = clock()
        }
    }

    /**
     * Send [frame] and return the first frame [answers] accepts, or null when none came within
     * [timeoutMs] after any of [attempts] sends (many H-AA reads simply go unanswered on models
     * without the feature). One request at a time. Throws like [send].
     *
     * [countsSilence] false for reads a model may lack (the level, EQ, prompts, the battery poll,
     * the Lab): their silence says nothing about the link, so it never counts toward closing it.
     */
    suspend fun request(
        frame: JblFrame,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
        attempts: Int = 1,
        countsSilence: Boolean = true,
        answers: (JblFrame) -> Boolean,
    ): JblFrame? = requestLock.withLock {
        repeat(attempts) {
            val waiter = Waiter(answers)
            val before = framesReceived
            waiters += waiter
            try {
                send(frame)
                val reply = withTimeoutOrNull(timeoutMs) { waiter.frame.await() }
                if (reply != null) return@withLock reply
            } finally {
                waiters -= waiter
            }
            if (countsSilence && framesReceived == before && ++silentRequests >= SILENT_REQUESTS_BEFORE_DEAD) {
                JblLog.w("$silentRequests requests without a frame back; closing the link")
                finish(UnresponsiveLinkException("the JBL control service is open but nothing answers"))
                throw HeadphoneCommandException("The headphones didn't answer (${JblCommands.name(frame.command)})")
            }
        }
        null
    }

    /** The device info, waiting up to [timeoutMs] for the one the headset sends by itself. */
    suspend fun awaitDeviceInfo(timeoutMs: Long): JblDeviceInfo? {
        deviceInfo?.let { return it }
        val waiter = Waiter { it.command == JblCommands.DEVICE_INFO }
        waiters += waiter
        try {
            deviceInfo?.let { return it }
            withTimeoutOrNull(timeoutMs) { waiter.frame.await() }
            return deviceInfo
        } finally {
            waiters -= waiter
        }
    }

    override suspend fun awaitClosed(): Throwable? = closed.await()

    override fun close() = finish(null)

    private fun finish(cause: Throwable?) {
        if (!isOpen) return
        isOpen = false
        runCatching { transport.close() }
        // Not cancel(): a waiting caller is still alive and must hear "not connected", not a bare
        // CancellationException (ConnectionManager would treat that as its own cancellation and
        // end the connect attempt without a message or a retry).
        waiters.forEach { it.frame.completeExceptionally(HeadphoneCommandException(NOT_CONNECTED)) }
        closed.complete(cause)
        scope.cancel()
    }

    companion object {
        const val NOT_CONNECTED = "Headphones not connected"

        /** jbl-control waits 1.6 s for a probe answer; so does Earshot. */
        const val REPLY_TIMEOUT_MS = 1_600L

        /** jbl-control's pause between writes. */
        const val PACE_MS = 450L

        const val SILENT_REQUESTS_BEFORE_DEAD = 3

        private const val EARLY_CAP = 32
    }
}

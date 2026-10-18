package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.BmapFrameBuffer
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor

/** How [BmapConnection.request] decides when the reply is complete. */
enum class ReplyMode {
    /**
     * One frame: the first frame for the same `[block.function]` that is not PROCESSING.
     * STATUS, RESULT and ERROR all count; the caller decides what to do with an ERROR.
     */
    SINGLE,

    /**
     * A dump: frames keep coming until the channel is quiet for [BmapConnection.quietGapMs].
     * Matching is block-wide, not per function, because `[31.1]` GetAll answers PROCESSING
     * on `[31.1]` and then streams STATUS frames for *other* functions of block 31, and
     * `[31.6]` returns one 48-byte STATUS per mode slot across several reads.
     */
    MULTI,
}

/**
 * One live BMAP session on top of a [ByteTransport].
 *
 * - A reader coroutine feeds every chunk through the protocol module's [BmapFrameBuffer] and
 *   routes each frame either to the request in flight (same address, see [ReplyMode]) or to
 *   [unsolicited].
 * - [request] is serialised by a [Mutex]: BMAP has no sequence numbers, so matching replies to
 *   requests only works with one request outstanding.
 * - Writes are spaced by at least [minWriteGapMs]; the firmware drops or garbles frames that
 *   arrive back-to-back (bosectl settled on 50-100 ms, Quiet Rebellion on 200 ms).
 *
 * The typed protocol layer only needs [request], [send] and [unsolicited]; it sees this class
 * through [BmapRequester].
 */
class BmapConnection(
    private val transport: ByteTransport,
    parentScope: CoroutineScope,
    /** Idle time after the last frame before a [ReplyMode.MULTI] reply is considered complete. */
    val quietGapMs: Long = DEFAULT_QUIET_GAP_MS,
    /** Minimum spacing between two writes on the wire. */
    val minWriteGapMs: Long = DEFAULT_MIN_WRITE_GAP_MS,
    /** Millisecond clock for write spacing; tests pass the virtual one. */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Requests in a row that may time out (written fine, no byte back) before the link is
     * suspected half-dead. Any incoming byte resets the count. Same threshold as the protocol's
     * refresh tolerance. Reaching it no longer closes the link by itself: see [confirmTimeoutMs].
     */
    val maxSilentReplies: Int = DEFAULT_MAX_SILENT_REPLIES,
    /**
     * After [maxSilentReplies] silent requests, a known-good `[0.1]` GET gets this long to answer.
     * Older firmware stays silent (no ERROR) for functions it lacks, so three unknown functions in
     * a row used to close a healthy link and loop on reconnects. If `[0.1]` answers, the silent
     * functions are recorded in [silentFunctions] and the link stays; if it is silent too, the
     * link really is deaf (the Bose app holding the channel) and is closed with
     * [UnresponsiveLinkException] as before.
     */
    val confirmTimeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
) : BmapRequester, ManagedLink {
    // Child of the parent's Job (dies with it) but with its own supervisor so a reader failure
    // never cancels the caller. Keeps the parent's dispatcher when it has one (tests inject a
    // virtual-time dispatcher), otherwise Default.
    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default),
    )
    private val mutex = Mutex()
    // The buffer drops its pending bytes when they cannot be BMAP (bad operator nibble, or more
    // than a frame's worth without decoding); that happens after a greeting burst cut mid-frame
    // or when an SDP fallback landed on the wrong channel. Log it so a stuck link is diagnosable.
    private val frames = BmapFrameBuffer(onDesync = { dropped ->
        junkBytes += dropped.size
        BtLog.w("frame desync: dropped ${dropped.size} undecodable bytes (${dropped.take(16).toByteArray().toHex()}…)")
    })

    // What came in so far, for the strict liveness probe: a beacon or log channel must not pass as
    // BMAP. Written by the reader coroutine only.
    @Volatile private var bytesIn = 0L
    @Volatile private var deviceFrames = 0
    @Volatile private var oddFrames = 0
    @Volatile private var junkBytes = 0L

    /** Counts of what arrived on this connection; see [LivenessProbe.judge]. */
    data class Traffic(val bytes: Long, val deviceFrames: Int, val oddFrames: Int, val junkBytes: Long) {
        /** Something arrived and every byte of it framed as device-side BMAP on a known block. */
        val looksLikeBmap: Boolean get() = deviceFrames > 0 && oddFrames == 0 && junkBytes == 0L
    }

    fun traffic(): Traffic = Traffic(bytesIn, deviceFrames, oddFrames, junkBytes)
    private val closedSignal = CompletableDeferred<Throwable?>()

    private val _unsolicited = MutableSharedFlow<BmapPacket>(extraBufferCapacity = 64)

    /** Frames nobody asked for: battery pushes, mode changes made on the headphones, etc. */
    val unsolicited: SharedFlow<BmapPacket> = _unsolicited.asSharedFlow()

    @Volatile
    private var pending: PendingRequest? = null

    @Volatile
    private var lastWriteAt = clock() - minWriteGapMs // first write goes out immediately

    @Volatile
    override var isOpen: Boolean = true
        private set

    /** Timed-out requests since the last incoming byte; see [maxSilentReplies]. */
    @Volatile
    private var silentInARow = 0

    /** The functions behind [silentInARow]; recorded in [silentFunctions] when `[0.1]` proves the link alive. */
    @Volatile
    private var silentStreak: List<FunctionAddress> = emptyList()

    @Volatile
    private var silentSet: Set<FunctionAddress> = emptySet()

    /**
     * Functions that timed out while the link demonstrably still answered: absent on this firmware,
     * for this connection only (a new connection starts empty). The protocol's refresh skips them.
     */
    override val silentFunctions: Set<FunctionAddress> get() = silentSet

    /** True once any byte arrived on this connection: proof that something is listening on the other end. */
    @Volatile
    var hasAnswered: Boolean = false
        private set

    /** Why the connection ended: null while open or after a local [close], the IOException when the link dropped. */
    @Volatile
    var closeCause: Throwable? = null
        private set

    private val readerJob: Job = scope.launch {
        try {
            transport.incoming.collect { chunk ->
                // Any byte at all proves the far end is alive, even one that does not frame.
                hasAnswered = true
                silentInARow = 0
                silentStreak = emptyList()
                bytesIn += chunk.size
                frames.feed(chunk).forEach(::dispatch)
            }
            finish(null)
        } catch (e: Throwable) {
            finish(e)
            if (e is CancellationException) throw e
        }
    }

    /** Suspends until the connection is closed, by us or by the link. Returns the cause (null when local). */
    override suspend fun awaitClosed(): Throwable? = closedSignal.await()

    /**
     * Send [packet] and collect its reply according to [mode]. Throws [HeadphoneCommandException]
     * ("no reply") after [timeoutMs] without a first matching frame, or when the link drops.
     */
    override suspend fun request(
        packet: BmapPacket,
        mode: ReplyMode,
        timeoutMs: Long,
    ): List<BmapPacket> = exchange(packet, mode, timeoutMs, watched = true)

    /**
     * [request] without the half-dead watchdog: a timeout here never counts towards
     * [maxSilentReplies]. For the Lab explorer, where silent functions are expected and a scan
     * must not tear the link down, and for the connect-time liveness probe, which decides on its own.
     */
    suspend fun requestUnwatched(
        packet: BmapPacket,
        mode: ReplyMode = ReplyMode.SINGLE,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<BmapPacket> = exchange(packet, mode, timeoutMs, watched = false)

    /**
     * Close because the link went deaf, as the watchdog would. For callers that reached the same
     * conclusion on their own terms (the protocol's refresh tolerance, say).
     */
    fun closeUnresponsive(reason: String = MESSAGE_STOPPED_ANSWERING) = close(UnresponsiveLinkException(reason))

    private suspend fun exchange(
        packet: BmapPacket,
        mode: ReplyMode,
        timeoutMs: Long,
        watched: Boolean,
    ): List<BmapPacket> = mutex.withLock {
        requireOpen()
        exchangeLocked(packet, mode, timeoutMs)?.let { return@withLock it }
        if (!watched) throw HeadphoneCommandException("${packet.address}: no reply from the headphones")
        silentStreak = silentStreak + packet.address
        if (++silentInARow < maxSilentReplies) throw HeadphoneCommandException("${packet.address}: no reply from the headphones")
        // Silent N times in a row: a deaf link, or firmware that ignores functions it lacks
        // instead of answering FuncNotSupp. Ask something every firmware answers before deciding.
        val streak = silentStreak
        if (confirmAliveLocked()) {
            silentSet = silentSet + streak
            silentInARow = 0
            silentStreak = emptyList()
            BtLog.w("${streak.joinToString()} got no reply but $CONFIRM_ADDRESS still answers: treating them as absent on this firmware")
            throw HeadphoneCommandException("${packet.address}: no reply from the headphones")
        }
        // The socket takes our bytes but nothing comes back, not even [0.1]: on real hardware this
        // is another app (the Bose app) holding the BMAP channel. Waiting out every later command's
        // timeout helps nobody; drop the link so the manager retries with backoff and tells the
        // user what is going on.
        BtLog.w("${packet.address}: $silentInARow requests in a row and $CONFIRM_ADDRESS got no reply, closing the half-dead link")
        close(UnresponsiveLinkException("$silentInARow requests in a row got no reply"))
        throw HeadphoneCommandException(MESSAGE_STOPPED_ANSWERING)
    }

    /** One `[0.1]` GET under the held mutex: true when anything answers it in time. */
    private suspend fun confirmAliveLocked(): Boolean {
        if (!isOpen) return false
        return try {
            exchangeLocked(BmapPacket.get(CONFIRM_ADDRESS), ReplyMode.SINGLE, confirmTimeoutMs) != null
        } catch (e: HeadphoneCommandException) {
            false
        }
    }

    /**
     * Write [packet] and collect its reply; the caller holds [mutex]. Null when the write went out
     * but nothing came back within [timeoutMs]: the caller decides what that silence means.
     */
    private suspend fun exchangeLocked(packet: BmapPacket, mode: ReplyMode, timeoutMs: Long): List<BmapPacket>? {
        val req = PendingRequest(packet.address, mode)
        pending = req
        try {
            // The timeout covers the write as well as the wait for the first frame: a socket
            // write blocks for good once the peer stops reading, and that must not pin the mutex.
            var written = false
            val first = withTimeoutOrNull(timeoutMs) {
                writeSpaced(packet)
                written = true
                var frame = req.frames.receive()
                // PROCESSING means "accepted, the real answer follows"; keep waiting for it. In
                // MULTI mode too: the quiet-gap clock must start at the first real frame, or a
                // dump that takes longer than the gap to begin is cut off after the ack alone.
                while (frame.operator == BmapOperator.PROCESSING) frame = req.frames.receive()
                frame
            }
            if (first == null) {
                if (!written) {
                    // The stack never took the bytes: nothing later will get through either, so
                    // drop the link (which also unblocks the stuck write) and let the manager
                    // reconnect instead of every command queueing behind this one.
                    BtLog.w("${packet.address}: write did not complete within ${timeoutMs}ms, closing the connection")
                    close(IOException("write stalled"))
                    throw HeadphoneCommandException("Connection to the headphones was lost")
                }
                // Leftover bytes after a timeout usually mean we lost frame sync (bad length byte
                // in a partial greeting, say); start clean rather than swallow the next reply too.
                if (frames.pendingSize > 0) {
                    BtLog.w("${packet.address}: timeout with ${frames.pendingSize} undecoded bytes, resetting frame buffer")
                    frames.reset()
                }
                return null
            }
            return when (mode) {
                ReplyMode.SINGLE -> listOf(first)
                ReplyMode.MULTI -> {
                    val all = arrayListOf(first)
                    while (true) {
                        val next = withTimeoutOrNull(quietGapMs) { req.frames.receive() } ?: break
                        all += next
                    }
                    all
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            throw HeadphoneCommandException("Connection to the headphones was lost", e)
        } catch (e: IOException) {
            close(e)
            throw HeadphoneCommandException("Connection to the headphones was lost", e)
        } finally {
            pending = null
            // Anything that arrived for this address after we stopped listening is still real
            // (a second STATUS after a SET_GET, for instance); hand it to the unsolicited stream.
            while (true) {
                val leftover = req.frames.tryReceive().getOrNull() ?: break
                _unsolicited.tryEmit(leftover)
            }
            req.frames.close()
        }
    }

    /**
     * Write without waiting for a reply. For commands whose "reply" is the link going away:
     * `[7.4]` power off and `[4.8]` pairing mode.
     */
    override suspend fun send(packet: BmapPacket) = mutex.withLock {
        requireOpen()
        try {
            writeSpaced(packet)
        } catch (e: IOException) {
            close(e)
            throw HeadphoneCommandException("Connection to the headphones was lost", e)
        }
    }

    override fun close() = close(null)

    private fun close(cause: Throwable?) {
        if (!isOpen) return
        BtLog.d("closing connection${cause?.let { " (${it.message})" } ?: ""}")
        transport.close()
        finish(cause)
    }

    private fun finish(cause: Throwable?) {
        if (isOpen) closeCause = cause
        isOpen = false
        transport.close()
        pending?.frames?.close(cause ?: ClosedReceiveChannelException("connection closed"))
        closedSignal.complete(cause)
        // Nothing of ours runs after this; without it every reconnect leaves a SupervisorJob
        // child hanging off the manager's scope for the life of the process.
        scope.cancel()
    }

    private fun requireOpen() {
        if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
    }

    private suspend fun writeSpaced(packet: BmapPacket) {
        WriteGuard.check(packet) // the single choke point: firmware-update/auth/reset frames never leave
        val wait = minWriteGapMs - (clock() - lastWriteAt)
        if (wait > 0) delay(wait)
        BtLog.d("-> $packet")
        // Written from the connection's own scope and awaited rather than called inline: a
        // blocking socket write cannot be interrupted, and the request timeout can only fire
        // while the caller is free to be cancelled. close() then releases the stuck write by
        // closing the socket.
        val write = scope.async { transport.write(packet.encode()) }
        try {
            write.await()
        } catch (e: CancellationException) {
            // The connection scope was cancelled under us (link dropped, local close) while the
            // caller itself is still alive: report it as a lost link, not as the caller's own
            // cancellation.
            if (!isOpen && currentCoroutineContext().isActive) throw HeadphoneCommandException("Connection to the headphones was lost", e)
            throw e
        }
        lastWriteAt = clock()
    }

    private fun dispatch(frame: BmapPacket) {
        BtLog.d("<- $frame")
        if (LivenessProbe.isDeviceFrame(frame)) deviceFrames++ else oddFrames++
        val req = pending
        if (req != null && req.accepts(frame)) {
            req.frames.trySend(frame)
        } else {
            _unsolicited.tryEmit(frame)
        }
    }

    private class PendingRequest(val address: FunctionAddress, val mode: ReplyMode) {
        val frames = Channel<BmapPacket>(Channel.UNLIMITED)

        fun accepts(frame: BmapPacket): Boolean = when (mode) {
            ReplyMode.SINGLE -> frame.block == address.block && frame.function == address.function
            ReplyMode.MULTI -> frame.block == address.block
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
        const val DEFAULT_QUIET_GAP_MS = 400L
        const val DEFAULT_MIN_WRITE_GAP_MS = 80L
        const val DEFAULT_MAX_SILENT_REPLIES = 3
        const val DEFAULT_CONFIRM_TIMEOUT_MS = 1_500L

        /** The known-good GET of the silence check: `[0.1]` BmapVersion, answered by every firmware (see [LivenessProbe]). */
        val CONFIRM_ADDRESS = FunctionAddress(0, 1)
        const val MESSAGE_STOPPED_ANSWERING = "The headphones stopped answering"
    }
}

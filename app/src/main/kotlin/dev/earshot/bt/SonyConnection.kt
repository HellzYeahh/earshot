package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.sony.SonyMessage
import dev.earshot.protocol.sony.SonyMessages
import dev.earshot.protocol.sony.SonyProtocolInfo
import dev.earshot.protocol.sony.SonyTable
import dev.earshot.protocol.sony.SonyWriteGuard
import dev.earshot.protocol.sony.TandemDataType
import dev.earshot.protocol.sony.TandemFrame
import dev.earshot.protocol.sony.TandemFrameBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor

/**
 * One session with a Sony headset over its MDR v2 RFCOMM service: the Tandem link layer with
 * Sound Connect's stop-and-wait rules (sony.md §2):
 *  - one frame in flight, with a 1-bit sequence. The headset ACKs with `1 − seq`, and that ACK's
 *    seq becomes the next outgoing seq; an ACK carrying the current seq is stale and ignored;
 *  - no ACK within [ackTimeoutMs] (750 ms) → resend the same frame, up to [maxResends] (10) times,
 *    then the link is dropped ("Remote endpoint does not respond"), as the app does;
 *  - every received frame of an acknowledged type is ACKed with `1 − its seq`; a frame repeating
 *    the previous seq is a resend and dropped, unless it is a NTFY (the app's exception);
 *  - nothing is sent for [openDelayMs] (500 ms) after the socket opens: Gadgetbridge found that
 *    talking too soon fails on some units.
 *
 * Replies are separate frames after the ACK: [request] waits for the RET with the next command
 * byte and the same inquired type. Everything else the headset sends (NTFY pushes, late replies)
 * goes to [unsolicited]. Every outgoing message passes [SonyWriteGuard] first.
 */
class SonyConnection(
    private val transport: ByteTransport,
    parentScope: CoroutineScope,
    private val openDelayMs: Long = OPEN_DELAY_MS,
    private val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    private val maxResends: Int = MAX_RESENDS,
    private val replyTimeoutMs: Long = REPLY_TIMEOUT_MS,
) : ManagedLink {
    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default),
    )
    private val frames = TandemFrameBuffer(
        onJunk = { n -> SonyLog.w("dropped $n undecodable bytes") },
        onLengthMismatch = { d, a -> SonyLog.w("frame declares $d payload bytes, carries $a") },
    )

    /** Socket writes: data frames and the ACKs the reader sends. */
    private val writeLock = Mutex()

    /** One data frame in flight. */
    private val frameLock = Mutex()

    /** One request/reply exchange at a time. */
    private val requestLock = Mutex()

    private data class ReplyKey(val table: SonyTable, val command: Int, val type: Int)

    private val pendingReplies = ConcurrentHashMap<ReplyKey, CompletableDeferred<SonyMessage>>()

    @Volatile private var pendingAck: CompletableDeferred<Int>? = null
    @Volatile private var sendSeq = 0
    private var lastReceivedSeq = -1
    private var openDelayDone = false
    private val closed = CompletableDeferred<Throwable?>()

    private val _unsolicited = MutableSharedFlow<SonyMessage>(extraBufferCapacity = 64)
    val unsolicited: SharedFlow<SonyMessage> = _unsolicited.asSharedFlow()

    @Volatile
    override var isOpen: Boolean = true
        private set

    /** Set by the link factory's probe: the `CONNECT_RET_PROTOCOL_INFO` it read. */
    @Volatile
    var protocolInfo: SonyProtocolInfo? = null

    init {
        scope.launch {
            try {
                transport.incoming.collect { chunk -> frames.feed(chunk).forEach { handle(it) } }
                finish(null)
            } catch (e: Throwable) {
                finish(e)
                if (e is CancellationException) throw e
            }
        }
    }

    private suspend fun handle(frame: TandemFrame) {
        if (frame.isAck) {
            val waiting = pendingAck
            if (waiting != null && frame.seq != sendSeq) waiting.complete(frame.seq)
            else SonyLog.d("ignored ACK seq ${frame.seq} (current ${sendSeq})")
            return
        }
        val acknowledged = TandemDataType.needsAck(frame.dataType)
        if (acknowledged) {
            try {
                writeRaw(TandemFrame.ack((frame.seq xor 1) and 1).encode())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SonyLog.w("could not ACK: ${e.message}")
            }
        }
        val table = SonyTable.forDataType(frame.dataType)
        if (table == null || frame.payload.isEmpty()) {
            SonyLog.d("ignored frame $frame")
            return
        }
        val message = SonyMessage(table, frame.payload)
        if (acknowledged) {
            val resend = frame.seq == lastReceivedSeq && !message.isNotify
            lastReceivedSeq = frame.seq
            if (resend) {
                SonyLog.d("dropped a resent $message")
                return
            }
        }
        // Command, type and size only: some replies carry names and addresses (paired devices, the headset's own address).
        SonyLog.d("<- $message")
        pendingReplies.remove(ReplyKey(table, message.command, message.type))?.complete(message) ?: _unsolicited.tryEmit(message)
    }

    private suspend fun writeRaw(bytes: ByteArray) = writeLock.withLock { transport.write(bytes) }

    /**
     * Send [message] and wait for the headset's ACK, resending as described above. Throws
     * [HeadphoneCommandException] when the message is not on the allow-list, the link is closed, or
     * the headset never ACKs (the link is then dropped). [resends] defaults to [maxResends].
     */
    suspend fun send(message: SonyMessage, resends: Int = maxResends) {
        if (!SonyWriteGuard.isAllowed(message)) {
            throw HeadphoneCommandException("Earshot doesn't send that command to Sony headphones ($message)")
        }
        frameLock.withLock {
            if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
            if (!openDelayDone) {
                delay(openDelayMs)
                openDelayDone = true
            }
            val seq = sendSeq
            val bytes = TandemFrame(message.table.dataType, seq, message.payload).encode()
            for (attempt in 0..resends) {
                if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
                val ack = CompletableDeferred<Int>()
                pendingAck = ack
                SonyLog.d("-> $message seq $seq${if (attempt > 0) " (resend $attempt)" else ""}")
                try {
                    writeRaw(bytes)
                } catch (e: CancellationException) {
                    pendingAck = null
                    throw e
                } catch (e: Exception) {
                    pendingAck = null
                    throw HeadphoneCommandException("Connection to the headphones was lost", e)
                }
                val acked = withTimeoutOrNull(ackTimeoutMs) { ack.await() }
                pendingAck = null
                if (acked != null) {
                    sendSeq = acked
                    return
                }
            }
            SonyLog.w("no ACK for $message after ${resends + 1} tries; dropping the link")
            finish(UnresponsiveLinkException("Remote endpoint does not respond"))
            throw HeadphoneCommandException("The headphones stopped answering")
        }
    }

    /**
     * Send [message] and return the headset's reply: the next command byte ([replyCommand]) with the
     * same inquired type, on the same table. Throws [HeadphoneCommandException] when nothing comes
     * back within [timeoutMs]; the link stays up (unlike a missing ACK).
     */
    suspend fun request(
        message: SonyMessage,
        replyCommand: Int = SonyMessages.replyTo(message.command),
        timeoutMs: Long = replyTimeoutMs,
        resends: Int = maxResends,
    ): SonyMessage = requestLock.withLock {
        val key = ReplyKey(message.table, replyCommand, message.type)
        val reply = CompletableDeferred<SonyMessage>()
        pendingReplies[key] = reply
        try {
            send(message, resends)
            withTimeoutOrNull(timeoutMs) { reply.await() } ?: throw HeadphoneCommandException("The headphones didn't answer ($message)")
        } finally {
            pendingReplies.remove(key, reply)
        }
    }

    override suspend fun awaitClosed(): Throwable? = closed.await()

    override fun close() = finish(null)

    private fun finish(cause: Throwable?) {
        if (!isOpen) return
        isOpen = false
        runCatching { transport.close() }
        pendingAck?.cancel()
        pendingReplies.values.forEach { it.cancel() }
        pendingReplies.clear()
        closed.complete(cause)
        scope.cancel()
    }

    companion object {
        const val OPEN_DELAY_MS = 500L
        const val ACK_TIMEOUT_MS = 750L
        const val MAX_RESENDS = 10
        const val REPLY_TIMEOUT_MS = 2_000L
    }
}

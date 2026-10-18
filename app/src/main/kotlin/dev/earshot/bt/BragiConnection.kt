package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.bragi.BosMessageType
import dev.earshot.protocol.bragi.BosRpcPacket
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.bragi.ChannelCipher
import dev.earshot.protocol.bragi.PppFraming
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.ContinuationInterceptor

/** What one BOS-RPC call returned: the response payloads (protobuf bodies) and the final status. */
class BragiReply(val payloads: List<ByteArray>, val statusRaw: ByteArray?) {
    /** The single response body, or empty when the method returns nothing (Empty). */
    val payload: ByteArray get() = payloads.firstOrNull() ?: ByteArray(0)
}

/** The slice of [BragiConnection] the Crusher protocol layer uses, so it can be tested against a fake. */
interface BragiRequester {
    val isOpen: Boolean

    /**
     * Call [address] with the protobuf [body]. A unary call completes on its final RESPONSE or its
     * STATUS; with [streaming] it completes on the first RESPONSE (the initial state of a
     * subscription), and later pushes arrive on [unsolicited]. Throws [HeadphoneCommandException]
     * on a non-zero status, a timeout, or a lost link.
     */
    suspend fun call(
        address: BragiServices.Address,
        body: ByteArray = ByteArray(0),
        timeoutMs: Long = BragiConnection.DEFAULT_TIMEOUT_MS,
        streaming: Boolean = false,
    ): BragiReply

    /** Responses nobody is waiting for: subscription pushes, late frames. */
    val unsolicited: SharedFlow<BosRpcPacket>
}

/**
 * One live BOS-RPC session on a [ByteTransport] (the Skullcandy Crusher 1080 ANC family).
 *
 * Same shape as [BmapConnection]: a reader coroutine, one exchange at a time under a mutex, spaced
 * writes, and a half-dead-link watchdog. Differences: calls carry a tag and a subscription model,
 * frames are PPP-framed and CRC-checked, and once a secure session is installed
 * ([installCipher]) everything except the handshake is sealed with the negotiated AEAD.
 *
 * Which services may be called is limited to [BragiServices.ALLOWED_SERVICES]: the firmware-update
 * surface is not reachable from here.
 */
class BragiConnection(
    private val transport: ByteTransport,
    parentScope: CoroutineScope,
    /** The vendor wrapper placed around outgoing frames; see [BragiTxWrapper]. */
    @Volatile var txWrapper: BragiTxWrapper = RawPppWrapper,
    val minWriteGapMs: Long = DEFAULT_MIN_WRITE_GAP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    val maxSilentReplies: Int = DEFAULT_MAX_SILENT_REPLIES,
) : BragiRequester {
    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default),
    )
    private val mutex = Mutex()
    private val reader = BragiFrameReader(onSkipped = { BtLog.d("bragi: skipped $it bytes that are not a frame") })
    private val closedSignal = CompletableDeferred<Throwable?>()
    private val _unsolicited = MutableSharedFlow<BosRpcPacket>(extraBufferCapacity = 64)
    override val unsolicited: SharedFlow<BosRpcPacket> = _unsolicited.asSharedFlow()

    @Volatile private var pending: Pending? = null
    @Volatile private var lastWriteAt = clock() - minWriteGapMs
    @Volatile private var tagCounter = 0
    @Volatile private var silentInARow = 0

    @Volatile override var isOpen: Boolean = true
        private set

    /** True once any byte arrived: something is listening on the other end. */
    @Volatile var hasAnswered: Boolean = false
        private set

    /** True once a valid PPP frame arrived: the vendor wrapper and the framing are right. */
    @Volatile var hasFramed: Boolean = false
        private set

    @Volatile var closeCause: Throwable? = null
        private set

    // Secure session (only when the device demands it).
    @Volatile private var cipher: ChannelCipher? = null
    private val sendSeq = AtomicLong(0)

    private val readerJob: Job = scope.launch {
        try {
            transport.incoming.collect { chunk ->
                hasAnswered = true
                silentInARow = 0
                reader.feed(chunk).forEach(::onFrame)
            }
            finish(null)
        } catch (e: Throwable) {
            finish(e)
            if (e is CancellationException) throw e
        }
    }

    suspend fun awaitClosed(): Throwable? = closedSignal.await()

    /** Switch to the encrypted-data-message path for everything but the handshake. */
    fun installCipher(c: ChannelCipher) { cipher = c; sendSeq.set(0) }

    override suspend fun call(
        address: BragiServices.Address,
        body: ByteArray,
        timeoutMs: Long,
        streaming: Boolean,
    ): BragiReply = exchange(address, body, timeoutMs, streaming, watched = true)

    /** [call] without the watchdog: for the connect probe, which decides on its own. */
    suspend fun callUnwatched(
        address: BragiServices.Address,
        body: ByteArray = ByteArray(0),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        streaming: Boolean = false,
    ): BragiReply = exchange(address, body, timeoutMs, streaming, watched = false)

    fun close() = close(null)

    fun closeUnresponsive(reason: String = MESSAGE_STOPPED_ANSWERING) = close(UnresponsiveLinkException(reason))

    private suspend fun exchange(
        address: BragiServices.Address, body: ByteArray, timeoutMs: Long, streaming: Boolean, watched: Boolean,
    ): BragiReply = mutex.withLock {
        requireOpen()
        if (!BragiServices.isAllowed(address)) {
            throw HeadphoneCommandException("Refusing to send ${address.service}.${address.method}: not a supported control call")
        }
        val tag = nextTag()
        val req = Pending(address, tag)
        pending = req
        try {
            var written = false
            val result = withTimeoutOrNull(timeoutMs) {
                writeSpaced(BosRpcPacket.request(address, tag, body))
                written = true
                awaitReply(req, streaming)
            }
            if (result == null) {
                if (!written) {
                    BtLog.w("bragi $address: write did not complete within ${timeoutMs}ms, closing")
                    close(IOException("write stalled"))
                    throw HeadphoneCommandException("Connection to the headphones was lost")
                }
                if (reader.pendingSize > 0) reader.reset()
                if (watched && ++silentInARow >= maxSilentReplies) {
                    BtLog.w("bragi $address: $silentInARow requests in a row got no reply, closing the half-dead link")
                    close(UnresponsiveLinkException("$silentInARow requests in a row got no reply"))
                    throw HeadphoneCommandException(MESSAGE_STOPPED_ANSWERING)
                }
                throw HeadphoneCommandException("No reply from the headphones (${address.service}.${address.method})")
            }
            checkStatus(address, result.statusRaw)
            result
        } catch (e: ClosedReceiveChannelException) {
            throw HeadphoneCommandException("Connection to the headphones was lost", e)
        } catch (e: IOException) {
            close(e)
            throw HeadphoneCommandException("Connection to the headphones was lost", e)
        } finally {
            pending = null
            while (true) { val left = req.frames.tryReceive().getOrNull() ?: break; _unsolicited.tryEmit(left) }
            req.frames.close()
        }
    }

    /** Collect frames for [req] until its final RESPONSE or its STATUS (or the first RESPONSE when [streaming]). */
    private suspend fun awaitReply(req: Pending, streaming: Boolean): BragiReply {
        val payloads = ArrayList<ByteArray>()
        while (true) {
            val p = req.frames.receive()
            when (p.type) {
                BosMessageType.RESPONSE -> {
                    payloads += p.payload
                    if (p.isFinal || streaming) return BragiReply(payloads, null)
                }
                BosMessageType.STATUS -> return BragiReply(payloads, p.payload)
                else -> Unit
            }
        }
    }

    /**
     * STATUS body: empty or a leading zero byte = OK. Anything else is an error code (0x60 is "invalid
     * arguments" per the service docs). The exact STATUS encoding is not yet confirmed on hardware.
     */
    private fun checkStatus(address: BragiServices.Address, status: ByteArray?) {
        if (status == null || status.isEmpty() || status[0] == 0.toByte()) return
        val code = status[0].toInt() and 0xFF
        val why = when (code) { 0x60 -> "invalid arguments"; 0x02 -> "generic failure"; else -> "status 0x%02x".format(code) }
        throw HeadphoneCommandException("The headphones rejected that ($why)")
    }

    private fun nextTag(): Int { tagCounter = (tagCounter + 1) and 0xFF; return tagCounter }

    private fun requireOpen() { if (!isOpen) throw HeadphoneCommandException("Headphones not connected") }

    private suspend fun writeSpaced(packet: BosRpcPacket) {
        val wait = minWriteGapMs - (clock() - lastWriteAt)
        if (wait > 0) delay(wait)
        val inner = packet.encode()
        val c = cipher
        val frame = if (c != null && packet.serviceId != BragiServices.AUTHENTICATED_ACCESS) {
            PppFraming.frame(c.seal(inner, sendSeq.getAndIncrement()), PppFraming.PROTOCOL_SECURE)
        } else {
            PppFraming.frame(inner, PppFraming.PROTOCOL_CLEARTEXT)
        }
        val bytes = txWrapper.wrap(frame)
        BtLog.d("bragi -> ${packet.type} ${packet.serviceId}.${packet.methodId} tag=${packet.tag} (${bytes.size} B via $txWrapper)")
        val write = scope.async { transport.write(bytes) }
        try {
            write.await()
        } catch (e: CancellationException) {
            if (!isOpen) throw HeadphoneCommandException("Connection to the headphones was lost", e)
            throw e
        }
        lastWriteAt = clock()
    }

    private fun onFrame(frame: BragiFrameReader.Frame) {
        hasFramed = true
        val inner = if (frame.protocolId == PppFraming.PROTOCOL_SECURE) {
            val c = cipher ?: run { BtLog.w("bragi: secure frame but no session yet; dropped"); return }
            try { c.open(frame.body) } catch (e: Exception) { BtLog.w("bragi: secure frame failed to open (${e.message})"); return }
        } else frame.body
        val packet = try { BosRpcPacket.decode(inner) } catch (e: IllegalArgumentException) {
            BtLog.w("bragi: undecodable packet ${inner.take(12).toByteArray().toHex()} (${e.message})"); return
        }
        BtLog.d("bragi <- ${packet.type} ${packet.serviceId}.${packet.methodId} tag=${packet.tag} final=${packet.isFinal} (${packet.payload.size} B)")
        val req = pending
        if (req != null && req.accepts(packet)) req.frames.trySend(packet) else _unsolicited.tryEmit(packet)
    }

    private fun close(cause: Throwable?) {
        if (!isOpen) return
        BtLog.d("closing bragi connection${cause?.let { " (${it.message})" } ?: ""}")
        transport.close()
        finish(cause)
    }

    private fun finish(cause: Throwable?) {
        if (isOpen) closeCause = cause
        isOpen = false
        transport.close()
        pending?.frames?.close(cause ?: ClosedReceiveChannelException("connection closed"))
        closedSignal.complete(cause)
        scope.cancel()
    }

    /** A call in flight. Replies are matched on service and method (the tag echo is not yet confirmed on hardware). */
    private class Pending(val address: BragiServices.Address, val tag: Int) {
        val frames = Channel<BosRpcPacket>(Channel.UNLIMITED)
        fun accepts(p: BosRpcPacket) =
            p.serviceId == address.service && p.methodId == address.method &&
                (p.type == BosMessageType.RESPONSE || p.type == BosMessageType.STATUS)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
        const val DEFAULT_MIN_WRITE_GAP_MS = 40L
        const val DEFAULT_MAX_SILENT_REPLIES = 3
        const val MESSAGE_STOPPED_ANSWERING = BmapConnection.MESSAGE_STOPPED_ANSWERING
    }
}

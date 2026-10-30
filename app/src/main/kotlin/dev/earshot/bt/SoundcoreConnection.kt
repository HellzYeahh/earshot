package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.soundcore.SoundcoreFrameBuffer
import dev.earshot.protocol.soundcore.SoundcoreModel
import dev.earshot.protocol.soundcore.SoundcorePacket
import dev.earshot.protocol.soundcore.SoundcoreWriteGuard
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
 * One session with a Soundcore headset. Requests go one at a time: each waits for a frame with the
 * same command id (the headset echoes it, usually with an empty body; `01 01` comes back as the
 * full state). Unknown commands get no answer at all, so a request retries three times with
 * growing waits (500 / 1000 / 1500 ms, as OpenSCQ30 does) before failing. Frames nobody asked
 * for (state and battery pushes) go to [unsolicited].
 *
 * Every outgoing frame passes [SoundcoreWriteGuard] first: unpair, pairing mode, power off and
 * any unknown write never leave the phone. [model] selects the allow-list: null (a bare connection)
 * and the Sleep A30 use the original list exactly; any other model gets its own table's list and,
 * on top, may write only after [unlockWrites] (the repository does that once the state it read
 * matched the layout Earshot knows for that model).
 */
class SoundcoreConnection(
    private val transport: ByteTransport,
    parentScope: CoroutineScope,
    private val minWriteGapMs: Long = MIN_WRITE_GAP_MS,
    /** The model the vendor service (or the demo transport) says is at the other end; null = original behaviour. */
    val model: SoundcoreModel? = null,
) : ManagedLink {
    /** The original driver has always written; a newer model must be unlocked by a state that fits its layout. */
    @Volatile
    var writesUnlocked: Boolean = model == null || model == SoundcoreModel.SLEEP_A30
        private set

    fun unlockWrites(unlocked: Boolean) {
        if (model != null && model != SoundcoreModel.SLEEP_A30) writesUnlocked = unlocked
    }

    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default),
    )
    private val frames = SoundcoreFrameBuffer(onJunk = { n -> BtLog.w("soundcore: dropped $n undecodable bytes") })
    private val requests = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<SoundcorePacket>>()
    private val closed = CompletableDeferred<Throwable?>()

    private val _unsolicited = MutableSharedFlow<SoundcorePacket>(extraBufferCapacity = 32)
    val unsolicited: SharedFlow<SoundcorePacket> = _unsolicited.asSharedFlow()

    @Volatile
    override var isOpen: Boolean = true
        private set

    /** True once a valid frame arrived: the far end speaks this protocol. */
    @Volatile
    var hasFramed: Boolean = false
        private set

    init {
        scope.launch {
            try {
                transport.incoming.collect { chunk -> frames.feed(chunk).forEach(::dispatch) }
                finish(null)
            } catch (e: Throwable) {
                finish(e)
                if (e is CancellationException) throw e
            }
        }
    }

    private fun dispatch(packet: SoundcorePacket) {
        if (!packet.inbound) return // an echo of our own frame
        hasFramed = true
        // Command and size only: some frames carry other people's data (the `0b 01` host list has the
        // phone's name and address), and logs hold nothing personal beyond the headset's address.
        BtLog.d("<- soundcore ${SoundcorePacket.commandName(packet.command)} (${packet.body.size} bytes)")
        pending.remove(packet.command)?.complete(packet) ?: _unsolicited.tryEmit(packet)
    }

    /**
     * Send [command] with [body] and return the headset's answer. Throws [HeadphoneCommandException]
     * when the command is not on the allow-list, the link is closed, or nothing answers.
     */
    suspend fun request(
        command: Int,
        body: ByteArray = ByteArray(0),
        timeoutsMs: List<Long> = DEFAULT_TIMEOUTS_MS,
    ): SoundcorePacket {
        val packet = SoundcorePacket(command, body)
        if (!SoundcoreWriteGuard.isAllowed(model, packet)) {
            throw HeadphoneCommandException("Earshot doesn't send that command to Soundcore headsets (${SoundcorePacket.commandName(command)})")
        }
        if (packet.isWrite && !writesUnlocked) {
            throw HeadphoneCommandException("Earshot hasn't matched these earbuds to a layout it knows, so it only reads them")
        }
        return requests.withLock {
            for (timeout in timeoutsMs) {
                if (!isOpen) throw HeadphoneCommandException(NOT_CONNECTED)
                val answer = CompletableDeferred<SoundcorePacket>()
                pending[command] = answer
                BtLog.d("-> soundcore ${SoundcorePacket.commandName(command)} (${body.size} bytes)")
                try {
                    transport.write(packet.encode())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pending.remove(command)
                    throw HeadphoneCommandException("Connection to the headphones was lost", e)
                }
                withTimeoutOrNull(timeout) { answer.await() }?.let { reply ->
                    if (minWriteGapMs > 0) delay(minWriteGapMs)
                    return@withLock reply
                }
                pending.remove(command)
            }
            throw HeadphoneCommandException("The headphones didn't answer (${SoundcorePacket.commandName(command)})")
        }
    }

    override suspend fun awaitClosed(): Throwable? = closed.await()

    override fun close() = finish(null)

    private fun finish(cause: Throwable?) {
        if (!isOpen) return
        isOpen = false
        runCatching { transport.close() }
        // Not cancel(): the caller waiting on the answer is still alive and must hear "not connected".
        // A bare CancellationException escapes withTimeoutOrNull, the link factory then can't report
        // the busy channel, and ConnectionManager takes it for its own cancellation (the connect loop
        // ends silently, stuck in CONNECTING). Same rule as JblConnection.finish.
        pending.values.forEach { it.completeExceptionally(HeadphoneCommandException(NOT_CONNECTED)) }
        pending.clear()
        closed.complete(cause)
        scope.cancel()
    }

    companion object {
        const val NOT_CONNECTED = "Headphones not connected"
        val DEFAULT_TIMEOUTS_MS = listOf(500L, 1_000L, 1_500L)
        const val MIN_WRITE_GAP_MS = 60L
    }
}

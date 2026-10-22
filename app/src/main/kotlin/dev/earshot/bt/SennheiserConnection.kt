package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.gaia.GaiaCommand
import dev.earshot.protocol.gaia.GaiaError
import dev.earshot.protocol.gaia.GaiaFrame
import dev.earshot.protocol.gaia.GaiaFrameBuffer
import dev.earshot.protocol.gaia.GaiaLinkClosedException
import dev.earshot.protocol.gaia.GaiaRefusedException
import dev.earshot.protocol.gaia.GaiaReply
import dev.earshot.protocol.gaia.GaiaRequestQueue
import dev.earshot.protocol.gaia.GaiaRequester
import dev.earshot.protocol.gaia.GaiaTimeoutException
import dev.earshot.protocol.sennheiser.SennheiserCommands
import dev.earshot.protocol.sennheiser.SennheiserWriteGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.ContinuationInterceptor

/**
 * One GAIA v3 session with a Sennheiser headset. Requests go one at a time through a
 * [GaiaRequestQueue] whose policy is [SennheiserWriteGuard]: nothing leaves the phone that isn't
 * on the allow-list, and until the session stores the headset's [features] only discovery reads do.
 * Notifications (and responses nobody waits for, like the wear state a registration triggers) go to
 * [pushes] for the session to fold into the state.
 *
 * Three requests in a row without a single frame back means the socket is open but nobody speaks
 * GAIA on it (Smart Control holding the connection): the session closes with
 * [UnresponsiveLinkException], which the manager reports as the busy channel.
 *
 * Logs carry command ids and sizes only: a model string is harmless, but paired-device and name
 * payloads are not, so no payload is ever logged here.
 */
class SennheiserConnection(
    private val transport: ByteTransport,
    parentScope: CoroutineScope,
) : ManagedLink, GaiaRequester {
    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default),
    )
    private val frames = GaiaFrameBuffer(onJunk = { n -> SennheiserLog.w("dropped $n undecodable bytes") })
    private val closed = CompletableDeferred<Throwable?>()
    private val pushChannel = Channel<GaiaFrame>(Channel.UNLIMITED)

    /** The Sennheiser features the headset reported (`0x0495/0x0001`); the write guard reads it on every send. */
    @Volatile
    var features: Map<Int, Int>? = null

    private val queue = GaiaRequestQueue(
        send = { bytes -> transport.write(bytes) },
        policy = SennheiserWriteGuard { features },
    )

    /** Notifications and unsolicited responses, in arrival order. Collect it once (the session does). */
    val pushes: Flow<GaiaFrame> = pushChannel.receiveAsFlow()

    @Volatile
    override var isOpen: Boolean = true
        private set

    /** True once a valid frame arrived: the far end speaks GAIA. */
    @Volatile
    var hasFramed: Boolean = false
        private set

    @Volatile
    private var silentRequests = 0

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

    private fun dispatch(frame: GaiaFrame) {
        // An echo of our own request (only the virtual device's tests feed those back) is not an answer.
        if (frame.type == GaiaCommand.TYPE_COMMAND) return
        hasFramed = true
        silentRequests = 0
        SennheiserLog.d("<- $frame")
        if (!queue.offer(frame)) pushChannel.trySend(frame)
    }

    /**
     * Send one request and return the headset's answer (an error frame comes back as
     * [GaiaReply.Error], not an exception). Throws [HeadphoneCommandException] when the guard refuses
     * it, the link is closed or lost, or nothing answers in [timeoutMs].
     */
    suspend fun request(
        vendor: Int,
        command: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): GaiaReply {
        if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
        val frame = GaiaFrame(vendor, command, payload)
        SennheiserLog.d("-> $frame")
        return try {
            queue.request(frame, timeoutMs)
        } catch (e: GaiaRefusedException) {
            SennheiserLog.w("${e.message}")
            throw HeadphoneCommandException("Earshot doesn't send that command to Sennheiser headphones (${GaiaCommand.name(vendor, command)})", e)
        } catch (e: GaiaTimeoutException) {
            if (++silentRequests >= SILENT_REQUESTS_BEFORE_DEAD) {
                SennheiserLog.w("$silentRequests requests without a frame back; closing the link")
                finish(UnresponsiveLinkException("the Sennheiser service is open but nothing answers"))
            }
            throw HeadphoneCommandException("The headphones didn't answer (${GaiaCommand.name(vendor, command)})", e)
        } catch (e: GaiaLinkClosedException) {
            throw HeadphoneCommandException("Headphones not connected", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw HeadphoneCommandException("Connection to the headphones was lost", e)
        }
    }

    /** [GaiaRequester] for the discovery helpers; same rules as the other [request]. */
    override suspend fun request(frame: GaiaFrame, timeoutMs: Long): GaiaReply = request(frame.vendor, frame.command, frame.payload, timeoutMs)

    /**
     * A Sennheiser (0x0495) request whose answer must be a response: its payload. An error frame
     * becomes a [HeadphoneCommandException] (flagged [HeadphoneCommandException.notSupportedByFirmware]
     * when the firmware has no such command).
     */
    suspend fun call(command: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray =
        when (val reply = request(SennheiserCommands.VENDOR, command, payload, timeoutMs)) {
            is GaiaReply.Ok -> reply.payload
            is GaiaReply.Error -> throw refusedBy(command, reply.code)
        }

    override suspend fun awaitClosed(): Throwable? = closed.await()

    override fun close() = finish(null)

    private fun finish(cause: Throwable?) {
        if (!isOpen) return
        isOpen = false
        runCatching { transport.close() }
        queue.close()
        pushChannel.close()
        closed.complete(cause)
        scope.cancel()
    }

    companion object {
        /** Ordinary reads and writes answer within tens of milliseconds on a Momentum 4. */
        const val DEFAULT_TIMEOUT_MS = 3_000L

        /** Smart Control waits 9-10 s for battery and version reads; so does Earshot. */
        const val SLOW_TIMEOUT_MS = 10_000L

        const val SILENT_REQUESTS_BEFORE_DEAD = 3

        fun refusedBy(command: Int, code: Int): HeadphoneCommandException =
            if (GaiaError.meansUnsupported(code)) {
                HeadphoneCommandException(HeadphoneCommandException.FIRMWARE_UNSUPPORTED_MESSAGE).apply { notSupportedByFirmware = true }
            } else {
                HeadphoneCommandException("The headphones refused that (${SennheiserCommands.name(command)}: ${GaiaError.name(code)})")
            }
    }
}

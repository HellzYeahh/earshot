package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.airoha.RaceFrameBuffer
import dev.earshot.protocol.airoha.RaceIds
import dev.earshot.protocol.airoha.RaceLinkClosedException
import dev.earshot.protocol.airoha.RacePacket
import dev.earshot.protocol.airoha.RaceRefusedException
import dev.earshot.protocol.airoha.RaceReply
import dev.earshot.protocol.airoha.RaceRequestQueue
import dev.earshot.protocol.airoha.RaceTimeoutException
import dev.earshot.protocol.airoha.RaceVendor
import dev.earshot.protocol.airoha.RaceWriteGuard
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
 * One Airoha RACE session, over the Airoha SPP record or the Airoha GATT service (the transport
 * decides which; the frames are the same). Commands go one at a time through a [RaceRequestQueue]
 * whose guard is the [vendor]'s [RaceWriteGuard]: firmware update, flash, NV-key writes, dumps and
 * everything not on that vendor's list never leave the phone. Indications nobody waits for go to
 * [pushes].
 *
 * Three commands in a row without a single frame back means the channel is open but nobody speaks
 * RACE on it (the vendor's app holding it): the session closes with [UnresponsiveLinkException],
 * which the manager reports as the busy channel.
 *
 * Logs carry ids and sizes only, never payloads (device info and paired lists hold names).
 */
class RaceConnection(
    private val transport: ByteTransport,
    val vendor: RaceVendor,
    parentScope: CoroutineScope,
) : ManagedLink {
    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default),
    )
    private val frames = RaceFrameBuffer(onJunk = { n -> BtLog.w("RACE: dropped $n undecodable bytes") })
    private val closed = CompletableDeferred<Throwable?>()
    private val pushChannel = Channel<RacePacket>(Channel.UNLIMITED)
    private val queue = RaceRequestQueue(send = { bytes -> transport.write(bytes) }, guard = RaceWriteGuard(vendor))

    /** Indications and late replies, in arrival order. Collect it once (the driver's session does). */
    val pushes: Flow<RacePacket> = pushChannel.receiveAsFlow()

    @Volatile
    override var isOpen: Boolean = true
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

    private fun dispatch(packet: RacePacket) {
        // Our own command echoed back (only a test device does that) is not an answer.
        if (packet.isCommand) return
        silentRequests = 0
        BtLog.d("RACE <- ${packet.forLog()}")
        if (!queue.offer(packet)) pushChannel.trySend(packet)
    }

    /**
     * Send one command and return the reply (a non-zero status comes back in [RaceReply], not as an
     * exception). Throws [HeadphoneCommandException] when the guard refuses it, the link is closed
     * or lost, or nothing answers in [timeoutMs]. [userInitiated] is for the find-me tone only.
     */
    suspend fun request(
        id: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        userInitiated: Boolean = false,
    ): RaceReply {
        if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
        val packet = RacePacket.command(id, payload)
        BtLog.d("RACE -> ${packet.forLog()}")
        return try {
            queue.request(packet, timeoutMs, userInitiated)
        } catch (e: RaceRefusedException) {
            BtLog.w("${e.message}")
            throw HeadphoneCommandException("Earshot doesn't send that command to these headphones (${RaceIds.name(id)})", e)
        } catch (e: RaceTimeoutException) {
            if (++silentRequests >= SILENT_REQUESTS_BEFORE_DEAD) {
                BtLog.w("RACE: $silentRequests commands without a frame back; closing the link")
                finish(UnresponsiveLinkException("the RACE channel is open but nothing answers"))
            }
            throw HeadphoneCommandException("The headphones didn't answer (${RaceIds.name(id)})", e)
        } catch (e: RaceLinkClosedException) {
            throw HeadphoneCommandException("Headphones not connected", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw HeadphoneCommandException("Connection to the headphones was lost", e)
        }
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
        const val DEFAULT_TIMEOUT_MS = 3_000L
        const val SILENT_REQUESTS_BEFORE_DEAD = 3
    }
}

/**
 * A packet as the RACE log lines print it: type, id and length, never the payload (audit R09).
 * Built from the fields instead of [RacePacket.toString], so a later change there can't put payload
 * bytes into the connection report: device info and paired lists carry names and addresses, and
 * the report is what users paste into public bug reports.
 */
private fun RacePacket.forLog(): String = "${RacePacket.typeName(type)} ${RaceIds.name(id)} (${payload.size} B)"

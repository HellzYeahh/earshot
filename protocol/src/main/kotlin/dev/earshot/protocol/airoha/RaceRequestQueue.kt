package dev.earshot.protocol.airoha

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What came back for a RACE command. Airoha replies start with a status byte (0 = success) before
 * the command's own fields (the `0x0CD6` battery reply: status, role, percent); [status] is that
 * byte, or null for an empty reply. [type] says whether it came as a response or an indication.
 */
class RaceReply(val type: Int, val payload: ByteArray) {
    val status: Int? get() = payload.firstOrNull()?.toInt()?.and(0xFF)
    val isSuccess: Boolean get() = status == 0

    /** The fields after the status byte. */
    val body: ByteArray get() = if (payload.isEmpty()) payload else payload.copyOfRange(1, payload.size)
}

/** [RaceWriteGuard] said no: nothing left the phone. */
class RaceRefusedException(val packet: RacePacket, reason: String) : Exception("$packet refused: $reason")

/** The link closed while a command waited, or before it was sent. */
class RaceLinkClosedException : Exception("the RACE link is closed")

/** Nothing answered within the timeout. */
class RaceTimeoutException(val packet: RacePacket, timeoutMs: Long) : Exception("$packet: no answer in $timeoutMs ms")

/**
 * One RACE command in flight: it waits for a response, or an indication, with the same id (the
 * TWS battery read answers with an indication), then the next command goes. Every frame passes
 * [guard] before [send]. The reader hands each inbound packet to [offer]; what it does not consume
 * (indications nobody asked for, late replies) is the caller's to apply.
 *
 * Transport-agnostic and Android-free, as `GaiaRequestQueue` is for GAIA.
 */
class RaceRequestQueue(
    private val send: suspend (ByteArray) -> Unit,
    private val guard: RaceWriteGuard,
) {
    private class Pending(val id: Int) {
        val reply = CompletableDeferred<RacePacket>()
    }

    private val lock = Mutex()

    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var closed = false

    /**
     * Send [packet] and wait up to [timeoutMs] for its answer. Throws [RaceRefusedException] when the
     * guard refuses it (nothing is sent), [RaceTimeoutException], [RaceLinkClosedException], or
     * whatever [send] throws when the link drops.
     */
    suspend fun request(packet: RacePacket, timeoutMs: Long, userInitiated: Boolean = false): RaceReply {
        guard.refusal(packet, userInitiated)?.let { throw RaceRefusedException(packet, it) }
        return lock.withLock {
            if (closed) throw RaceLinkClosedException()
            val p = Pending(packet.id)
            pending = p
            try {
                send(packet.encode())
                val reply = withTimeoutOrNull(timeoutMs) { p.reply.await() } ?: throw RaceTimeoutException(packet, timeoutMs)
                RaceReply(reply.type, reply.payload)
            } finally {
                pending = null
            }
        }
    }

    /** Offer an inbound packet. True when it answered the command in flight (then it is consumed). */
    fun offer(packet: RacePacket): Boolean {
        val p = pending ?: return false
        if (packet.isCommand || packet.id != p.id) return false
        return p.reply.complete(packet)
    }

    /** The link is gone: fail the command in flight and refuse new ones. */
    fun close() {
        closed = true
        pending?.reply?.completeExceptionally(RaceLinkClosedException())
    }
}

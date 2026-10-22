package dev.earshot.protocol.gaia

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What came back for a request: its response payload, or the error reason. */
sealed interface GaiaReply {
    class Ok(val payload: ByteArray) : GaiaReply

    class Error(val code: Int) : GaiaReply {
        override fun toString(): String = GaiaError.name(code)
    }
}

/** Anything that can send one GAIA request and wait for its answer: the queue, or a driver's connection around it. */
fun interface GaiaRequester {
    suspend fun request(frame: GaiaFrame, timeoutMs: Long): GaiaReply
}

/**
 * The single write path's policy: every frame a [GaiaRequestQueue] sends passes [refusal] first.
 * Drivers implement it as a refuse-by-default allow-list (see `SennheiserWriteGuard`).
 */
fun interface GaiaWritePolicy {
    /** Null to send [frame]; otherwise why Earshot never sends it (a log line, not user copy). */
    fun refusal(frame: GaiaFrame): String?
}

/** [GaiaWritePolicy] said no: nothing left the phone. */
class GaiaRefusedException(val frame: GaiaFrame, reason: String) : Exception("${GaiaCommand.name(frame.vendor, frame.command)} refused: $reason")

/** The link closed while a request waited, or before it was sent. */
class GaiaLinkClosedException : Exception("the GAIA link is closed")

/** Nothing answered within the timeout. */
class GaiaTimeoutException(val frame: GaiaFrame, timeoutMs: Long) : Exception("${GaiaCommand.name(frame.vendor, frame.command)}: no answer in $timeoutMs ms")

/**
 * One request in flight, as Qualcomm's own clients and momentum4-control's command queue do: a
 * request waits for the response or error with the same vendor and command (type bits aside), then
 * the next one goes. Every frame passes [policy] before [send]. The reader hands each inbound frame
 * to [offer]; what it does not consume (notifications, late or unsolicited responses) is the
 * caller's to apply.
 *
 * Transport-agnostic and Android-free, so any Qualcomm driver can reuse it and tests can drive it
 * with a plain lambda.
 */
class GaiaRequestQueue(
    private val send: suspend (ByteArray) -> Unit,
    private val policy: GaiaWritePolicy,
) : GaiaRequester {
    private class Pending(val vendor: Int, val command: Int) {
        val reply = CompletableDeferred<GaiaFrame>()
    }

    private val lock = Mutex()

    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var closed = false

    /**
     * Send [frame] and wait up to [timeoutMs] for its answer. Throws [GaiaRefusedException] when the
     * policy refuses it (nothing is sent), [GaiaTimeoutException] when nothing answers,
     * [GaiaLinkClosedException] when the link closed, and whatever [send] throws when it drops.
     */
    override suspend fun request(frame: GaiaFrame, timeoutMs: Long): GaiaReply {
        policy.refusal(frame)?.let { throw GaiaRefusedException(frame, it) }
        return lock.withLock {
            if (closed) throw GaiaLinkClosedException()
            val p = Pending(frame.vendor, GaiaCommand.request(frame.command))
            pending = p
            try {
                send(frame.encode())
                val reply = withTimeoutOrNull(timeoutMs) { p.reply.await() } ?: throw GaiaTimeoutException(frame, timeoutMs)
                if (GaiaCommand.isError(reply.command)) {
                    GaiaReply.Error(reply.payload.firstOrNull()?.toInt()?.and(0xFF) ?: -1)
                } else {
                    GaiaReply.Ok(reply.payload)
                }
            } finally {
                pending = null
            }
        }
    }

    /** Offer an inbound frame. True when it answered the request in flight (then it is consumed). */
    fun offer(frame: GaiaFrame): Boolean {
        val p = pending ?: return false
        val type = GaiaCommand.type(frame.command)
        if (type != GaiaCommand.TYPE_RESPONSE && type != GaiaCommand.TYPE_ERROR) return false
        if (frame.vendor != p.vendor || GaiaCommand.request(frame.command) != p.command) return false
        return p.reply.complete(frame)
    }

    /** The link is gone: fail the request in flight and refuse new ones. */
    fun close() {
        closed = true
        pending?.reply?.completeExceptionally(GaiaLinkClosedException())
    }
}

/** `[major, minor]` from Core `0x0000`; 3.1 on a Momentum 4 (firmware 3.37.3). */
data class GaiaApiVersion(val major: Int, val minor: Int) {
    override fun toString(): String = "$major.$minor"

    companion object {
        fun parse(payload: ByteArray): GaiaApiVersion? =
            if (payload.size < 2) null else GaiaApiVersion(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF)
    }
}

/**
 * One page of a supported-features reply (`0x0001`, and `0x0002` for the next page on Qualcomm
 * Core): `[moreData, (featureId, version)…]`. A Momentum 4 fits both its lists in one page.
 */
data class GaiaFeaturePage(val moreData: Boolean, val features: Map<Int, Int>) {
    companion object {
        fun parse(payload: ByteArray): GaiaFeaturePage? {
            if (payload.isEmpty()) return null
            val features = LinkedHashMap<Int, Int>()
            var i = 1
            while (i + 1 < payload.size) {
                features[payload[i].toInt() and 0xFF] = payload[i + 1].toInt() and 0xFF
                i += 2
            }
            return GaiaFeaturePage(moreData = payload[0].toInt() != 0, features = features)
        }
    }
}

/** Discovery helpers on top of any [GaiaRequester]: API version and the feature lists. */
object GaiaDiscovery {
    /** Qualcomm Core (feature 0) commands. */
    const val CORE_API_VERSION = 0x0000
    const val CORE_SUPPORTED_FEATURES = 0x0001
    const val CORE_SUPPORTED_FEATURES_NEXT = 0x0002

    /** A sanity cap: no device needs more pages than this, a looping one gets cut off. */
    private const val MAX_PAGES = 8

    suspend fun apiVersion(queue: GaiaRequester, timeoutMs: Long): GaiaApiVersion? =
        when (val r = queue.request(GaiaFrame(GaiaVendor.QUALCOMM, CORE_API_VERSION), timeoutMs)) {
            is GaiaReply.Ok -> GaiaApiVersion.parse(r.payload)
            is GaiaReply.Error -> null
        }

    /**
     * Every (feature → version) pair [vendor] reports through [command]. Follow-up pages are only
     * read when [nextPageCommand] is given: Qualcomm Core documents `0x0002`, a vendor's own
     * feature 0 may use that id for something else, so for vendors the first page is all there is.
     * Null when the headset refused the list.
     */
    suspend fun features(
        queue: GaiaRequester,
        vendor: Int,
        timeoutMs: Long,
        command: Int = CORE_SUPPORTED_FEATURES,
        nextPageCommand: Int? = if (vendor == GaiaVendor.QUALCOMM) CORE_SUPPORTED_FEATURES_NEXT else null,
    ): Map<Int, Int>? {
        val all = LinkedHashMap<Int, Int>()
        var next = command
        repeat(MAX_PAGES) {
            val page = when (val r = queue.request(GaiaFrame(vendor, next), timeoutMs)) {
                is GaiaReply.Ok -> GaiaFeaturePage.parse(r.payload)
                is GaiaReply.Error -> null
            } ?: return if (all.isEmpty()) null else all
            all.putAll(page.features)
            if (!page.moreData || nextPageCommand == null) return all
            next = nextPageCommand
        }
        return all
    }
}

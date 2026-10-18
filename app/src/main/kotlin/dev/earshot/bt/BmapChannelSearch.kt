package dev.earshot.bt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Walks [BmapCandidates.order] within one connect attempt: open a candidate, run the strict
 * [LivenessProbe] on it, keep the first that answers like BMAP and close everything else.
 *
 * Bounded: each socket gets [connectTimeoutMs] to open and [probeTimeoutMs] to answer, and no new
 * candidate is started once [budgetMs] has passed (the one in flight finishes). On wolverine with
 * the Bose app in the background that is about one probe per candidate; on a prince unit whose
 * BMAP sits on channel 9 it is "ch2 refused, ch8 refused or silent, ch9 answers".
 *
 * The socket opening is injected ([open]) so the walk is unit-tested with fake transports.
 */
internal class BmapChannelSearch(
    private val probeTimeoutMs: Long = LivenessProbe.TIMEOUT_MS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val budgetMs: Long = BUDGET_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How a connection is built on an opened transport; tests pass `minWriteGapMs = 0`. */
    private val connect: (ByteTransport, CoroutineScope) -> BmapConnection = { t, s -> BmapConnection(t, s) },
) {
    class Found(val candidate: BmapCandidate, val connection: BmapConnection)

    /**
     * Returns the first candidate that answers. Throws:
     *  - [RfcommConnectException] with `looksBusy = true` when at least one candidate opened but
     *    none answered BMAP (the "Close the Bose app" case: the channel is held, so it opens and
     *    stays silent, while the other channels refuse or are not BMAP);
     *  - [IOException] when the only sockets that opened dropped during their probe;
     *  - [SecurityException] when every candidate was refused for permission reasons;
     *  - otherwise [RfcommConnectException] carrying the transport's own busy signature.
     */
    suspend fun find(
        candidates: List<BmapCandidate>,
        scope: CoroutineScope,
        open: suspend (BmapCandidate) -> ByteTransport,
    ): Found {
        val start = clock()
        // The LE channel needs a GATT lookup before its socket: give the walk room for it.
        val budget = if (BmapCandidate.LeCoc in candidates) budgetMs + LE_EXTRA_MS else budgetMs
        val failures = ArrayList<String>()
        var openedButMute = 0
        var dropped: Throwable? = null
        var busySignature = false
        var firstSecurity: SecurityException? = null
        var onlySecurity = true

        for ((i, candidate) in candidates.withIndex()) {
            if (i > 0 && clock() - start >= budget) {
                failures += "time budget spent, skipped ${candidates.drop(i).joinToString()}"
                break
            }
            val transport = when (val o = openOne(candidate, open)) {
                is Opened -> o.transport
                is Failed -> {
                    failures += "$candidate: ${o.why.describe()}"
                    when (val e = o.why) {
                        is SecurityException -> if (firstSecurity == null) firstSecurity = e
                        is RfcommConnectException -> { onlySecurity = false; busySignature = busySignature || e.looksBusy }
                        else -> onlySecurity = false
                    }
                    continue
                }
            }
            onlySecurity = false
            val connection = connect(transport, scope)
            val verdict = try {
                LivenessProbe.probe(connection, probeTimeoutMs)
            } catch (t: Throwable) {
                // Cancelled mid-probe (Disconnect, a new device, Bluetooth off): do not leave the
                // socket held, or the next connect looks "busy" because of ourselves.
                connection.close()
                throw t
            }
            if (verdict == LivenessProbe.Verdict.Alive) {
                BtLog.i("BMAP answers on $candidate (after ${i + 1} of ${candidates.size} candidates)")
                return Found(candidate, connection)
            }
            connection.close()
            failures += "$candidate: $verdict"
            BtLog.w("$candidate opened but is not the BMAP channel: $verdict")
            when (verdict) {
                is LivenessProbe.Verdict.Dropped -> dropped = verdict.cause
                // Closed at once above: holding it would look to the headset like a second phone
                // connecting for calls. It is not the "Bose app holds the channel" signature.
                is LivenessProbe.Verdict.Hfp -> Unit
                else -> openedButMute++
            }
        }

        val summary = failures.joinToString("; ")
        if (openedButMute > 0) {
            throw RfcommConnectException(
                "The control channel opened but the headphones did not answer (another app is probably holding it): $summary",
                looksBusy = true,
            )
        }
        dropped?.let { throw IOException("Link dropped while looking for the control channel: $summary", it) }
        firstSecurity?.let { if (onlySecurity) throw it }
        throw RfcommConnectException("Could not open the control channel ($summary)", busySignature)
    }

    private sealed interface Outcome
    private class Opened(val transport: ByteTransport) : Outcome
    private class Failed(val why: Exception) : Outcome

    private suspend fun openOne(candidate: BmapCandidate, open: suspend (BmapCandidate) -> ByteTransport): Outcome = try {
        // RfcommTransport closes the socket when this times out (connectCancellable).
        withTimeoutOrNull(timeoutFor(candidate)) { open(candidate) }?.let(::Opened)
            ?: Failed(RfcommConnectException("connect timed out after ${timeoutFor(candidate)}ms", looksBusy = false))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Failed(e)
    }

    /** GATT connect + discovery + PSM read come before the LE socket opens. */
    private fun timeoutFor(candidate: BmapCandidate): Long =
        if (candidate == BmapCandidate.LeCoc) connectTimeoutMs + LE_EXTRA_MS else connectTimeoutMs

    private fun Throwable.describe(): String = "${this::class.simpleName}: ${message ?: "no message"}"

    companion object {
        /** Opening a socket to an RFCOMM channel with no server is refused quickly; this caps a stack that hangs instead. */
        const val CONNECT_TIMEOUT_MS = 6_000L

        /** No new candidate after this long; a full walk is normally a few seconds. */
        const val BUDGET_MS = 20_000L

        /** Extra time for the LE channel's GATT lookup ([BmapPsmReader.TIMEOUT_MS]). */
        const val LE_EXTRA_MS = BmapPsmReader.TIMEOUT_MS
    }
}

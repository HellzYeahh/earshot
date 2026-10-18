package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.qcultra2.FunctionNames
import java.io.IOException

/**
 * One cheap request right after the socket opens, before the session is published as CONNECTED.
 *
 * Why: with the Bose app running in the background, `createInsecureRfcommSocket(2)` still
 * connects and the greeting is silent, but nothing ever answers (Galaxy, Android 17, firmware
 * 10.12). Without this the user saw CONNECTED with an empty battery and no modes. And since the
 * link factory now tries several channels ([BmapCandidates]), the probe is also what tells the
 * BMAP channel from the others.
 *
 * It is **strict**: until 0.3.0 any incoming byte counted as alive, so a channel that talks
 * without speaking BMAP passed and was learned as "the" channel. bosectl #22/#26 list exactly
 * those: channel 14 on wolverine is a status beacon (`ff 55 02 …`), channel 22 a log stream that
 * "dumps data regardless of input", and 14/25/26 on prince accept the socket and say nothing.
 * Alive now means: a device-side answer (STATUS / ERROR / RESULT) to the probe's own `[0.1]`, or,
 * failing that, traffic in which every byte framed as device-side BMAP on a known block (a push
 * that crossed the probe, say). An echo of the request, a beacon or a log blob is not BMAP.
 */
internal object LivenessProbe {
    /**
     * `[0.1]` BmapVersion: answered by every firmware, tiny reply, and it doubles as the init
     * packet the QC45 / QC SE wait for before answering anything else (bosectl `INIT_PACKET`).
     * It was the battery `[2.2]` until older models were supported; that stays silent there.
     */
    val ADDRESS = FunctionAddress(0, 1)

    /**
     * Per candidate. BMAP answers in well under 200 ms; 1.5 s keeps a search over five candidates
     * bounded while leaving room for a slow phone stack.
     */
    const val TIMEOUT_MS = 1_500L

    /** Operators only the headphones send. PROCESSING is one too, but the connection swallows it while waiting. */
    private val DEVICE_OPERATORS = setOf(BmapOperator.STATUS, BmapOperator.ERROR, BmapOperator.RESULT, BmapOperator.PROCESSING)

    /** A frame the headphones could have sent: device-side operator on a block BMAP knows. */
    fun isDeviceFrame(frame: BmapPacket): Boolean = frame.operator in DEVICE_OPERATORS && frame.block in FunctionNames.blocks

    /** A frame that answers the probe itself: same address, device-side, not the PROCESSING ack. */
    fun answersProbe(frame: BmapPacket): Boolean =
        frame.address == ADDRESS && frame.operator != BmapOperator.PROCESSING && isDeviceFrame(frame)

    /** What a probe found. */
    sealed interface Verdict {
        data object Alive : Verdict
        /** Nothing at all came back: a held channel (the Bose app) or a mute server. */
        data object Silent : Verdict
        /** Bytes came back but not BMAP: a beacon, a log stream, an echo. */
        data class NotBmap(val detail: String) : Verdict
        /**
         * The headset's hands-free (HFP) or headset (HSP) channel: it opened with an AT command.
         * A positive identification of another profile, so it says nothing about BMAP being held.
         */
        data class Hfp(val firstLine: String) : Verdict {
            override fun toString(): String = "the HFP call channel (\"$firstLine\")"
        }
        /** The link went away during the probe; not the busy signature. */
        data class Dropped(val cause: Throwable) : Verdict
    }

    /**
     * Pure decision, unit-tested: [reply] is what the `[0.1]` request returned (null on timeout),
     * [traffic] what the connection saw meanwhile.
     */
    fun judge(reply: BmapPacket?, traffic: BmapConnection.Traffic): Verdict = when {
        reply != null && answersProbe(reply) -> Verdict.Alive
        traffic.looksLikeBmap -> Verdict.Alive
        traffic.bytes == 0L -> Verdict.Silent
        else -> hfpOpener(traffic.head)?.let { Verdict.Hfp(it) } ?: Verdict.NotBmap(
            "${traffic.bytes} bytes, ${traffic.deviceFrames} BMAP frames, ${traffic.oddFrames} odd frames, ${traffic.junkBytes} undecodable bytes" +
                (reply?.let { " (reply was ${it.operator} on ${it.address})" } ?: ""),
        )
    }

    /**
     * The AT command line [head] starts with, or null. HFP 1.8 §4.2.1.1: the hands-free side opens
     * every service-level connection with `AT+BRSF=<features>`, whoever connected, so a socket on
     * the headset's HFP channel greets the probe with it; an HSP headset sends `AT+CKPD=…` on a
     * button press. BMAP never starts like that (`41 54` would be block 65, which doesn't exist),
     * and the line must be printable ASCII up to its CR, so a binary stream can't match by chance.
     */
    fun hfpOpener(head: ByteArray): String? {
        var i = 0
        while (i < head.size && (head[i] == CR || head[i] == LF)) i++
        if (head.size - i < 3 || head[i] != 'A'.code.toByte() || head[i + 1] != 'T'.code.toByte()) return null
        val next = head[i + 2].toInt().toChar()
        if (next != '+' && next != '%' && next != '&' && !next.isUpperCase()) return null
        val end = (i until head.size).firstOrNull { head[it] == CR || head[it] == LF } ?: head.size
        val line = head.copyOfRange(i, end)
        if (line.any { it < 0x20 || it > 0x7E }) return null
        return String(line, Charsets.US_ASCII)
    }

    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A

    /** Send the probe on [connection] and judge it. Never closes the connection; never throws except on cancellation. */
    suspend fun probe(connection: BmapConnection, timeoutMs: Long = TIMEOUT_MS): Verdict {
        val reply = try {
            // Unwatched: this single probe decides on its own; it must not prime the watchdog.
            connection.requestUnwatched(BmapPacket.get(ADDRESS), ReplyMode.SINGLE, timeoutMs).firstOrNull()
        } catch (e: HeadphoneCommandException) {
            null
        }
        if (!connection.isOpen) {
            val cause = connection.closeCause ?: IOException("connection closed during the probe")
            return Verdict.Dropped(cause)
        }
        return judge(reply, connection.traffic())
    }

    /**
     * Returns when [connection] answers like BMAP. Otherwise closes it and throws
     * [RfcommConnectException] with `looksBusy = true`; if the link dropped during the probe, a
     * plain [IOException] (that is not the busy signature).
     */
    suspend fun check(connection: BmapConnection, timeoutMs: Long = TIMEOUT_MS) {
        when (val v = probe(connection, timeoutMs)) {
            Verdict.Alive -> return
            is Verdict.Dropped -> {
                connection.close()
                throw IOException("Link dropped during the liveness probe: ${v.cause.message}", v.cause)
            }
            is Verdict.Hfp -> {
                connection.close()
                BtLog.w("liveness probe: this socket is $v, not BMAP")
                throw RfcommConnectException("That channel is the headphones' call (HFP) channel, not the control channel", looksBusy = false)
            }
            Verdict.Silent, is Verdict.NotBmap -> {
                connection.close()
                BtLog.w("liveness probe: $ADDRESS got no BMAP answer within ${timeoutMs}ms ($v)")
                throw RfcommConnectException(
                    "The control channel opened but the headphones did not answer (another app is probably holding it)",
                    looksBusy = true,
                )
            }
        }
    }
}

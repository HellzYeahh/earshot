package dev.earshot.bt

import dev.earshot.protocol.bragi.PppFraming

/**
 * How a finished `bos_ppp_framing` frame is carried on the Bluetooth socket, and how frames are
 * found again in what comes back.
 *
 * The official app writes the frame **as it is** to a Bragi data socket (the Airoha RACE protocol
 * lives on a different socket and is never used here). The transmit side is still a small
 * interface ([BragiTxWrapper]) so a wrapper can be added without touching the connection, and the
 * receive side does not depend on it: [BragiFrameReader] looks for valid PPP frames anywhere in the
 * byte stream, so any header in front of (or between) frames is skipped.
 */
fun interface BragiTxWrapper {
    /** Bytes to write to the socket for one PPP [frame]. */
    fun wrap(frame: ByteArray): ByteArray
}

/** The PPP frame goes on the wire as it is. */
object RawPppWrapper : BragiTxWrapper {
    override fun wrap(frame: ByteArray): ByteArray = frame
    override fun toString() = "raw PPP"
}

/**
 * Pulls complete PPP frames out of an arbitrary byte stream. Bytes that are not part of a valid
 * frame (vendor headers, noise, a half-sent frame after a drop) are skipped one byte at a time
 * until the next valid `7E` header; a frame split across reads is kept until it completes.
 */
class BragiFrameReader(private val maxBuffer: Int = 70_000, private val onSkipped: (Int) -> Unit = {}) {
    private var buf = ByteArray(0)

    /** One inbound frame: the protocol id (0x94 / 0x95) and the body. */
    data class Frame(val protocolId: Int, val body: ByteArray)

    val pendingSize: Int get() = buf.size

    fun reset() { buf = ByteArray(0) }

    fun feed(chunk: ByteArray): List<Frame> {
        buf += chunk
        val out = ArrayList<Frame>()
        var pos = 0
        var skipped = 0
        while (pos < buf.size) {
            if (buf[pos].toInt() and 0xFF != PppFraming.FLAG) { pos++; skipped++; continue }
            when (val r = PppFraming.parse(buf, pos)) {
                is PppFraming.ParseResult.Frame -> {
                    out += Frame(r.decoded.protocolId, r.decoded.body)
                    pos += r.decoded.consumed
                }
                PppFraming.ParseResult.Incomplete -> break // keep from `pos`, wait for the rest
                is PppFraming.ParseResult.Invalid -> { pos++; skipped++ }
            }
        }
        buf = if (pos >= buf.size) ByteArray(0) else buf.copyOfRange(pos, buf.size)
        if (buf.size > maxBuffer) { skipped += buf.size; buf = ByteArray(0) } // a bogus length claimed more than a frame can hold
        if (skipped > 0) onSkipped(skipped)
        return out
    }
}

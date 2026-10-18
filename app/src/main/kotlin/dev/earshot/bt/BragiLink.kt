package dev.earshot.bt

import dev.earshot.protocol.bragi.PppFraming

/**
 * How a finished `bos_ppp_framing` frame is carried on the Bluetooth socket, and how frames are
 * found again in what comes back.
 *
 * The inner frame (`7E FF 80 94 ...`) is verified. What the Airoha vendor stack puts around it on
 * the real RFCOMM link is not statically provable, so the transmit side is pluggable
 * ([BragiTxWrapper]) and [BragiConnection] can try the candidates in turn. The receive side does
 * not care: [BragiFrameReader] looks for valid PPP frames anywhere in the byte stream, so a vendor
 * header in front of (or between) frames is simply skipped.
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
 * Airoha RACE command wrapper: `05 <type> <len LE16> <opcode LE16> <prefix> <payload>` where len
 * counts everything after itself. A fallback candidate for a vendor header around the PPP frame:
 * the Skullcandy app's own RACE helper builds `05 5A len 01 0D 05 FF <request>` for its native
 * custom commands, which is `AirohaRaceWrapper(opcode = 0x0D01, prefix = 05 FF)`. The opcode bytes
 * go out low byte first, so 0x0D01 appears as `01 0D`.
 */
class AirohaRaceWrapper(
    private val type: Int = 0x5A,
    private val opcode: Int,
    private val prefix: ByteArray = ByteArray(0),
) : BragiTxWrapper {
    override fun wrap(frame: ByteArray): ByteArray {
        val len = 2 + prefix.size + frame.size
        val out = ByteArray(4 + len)
        out[0] = 0x05
        out[1] = type.toByte()
        out[2] = (len and 0xFF).toByte()
        out[3] = ((len ushr 8) and 0xFF).toByte()
        out[4] = (opcode and 0xFF).toByte()
        out[5] = ((opcode ushr 8) and 0xFF).toByte()
        prefix.copyInto(out, 6)
        frame.copyInto(out, 6 + prefix.size)
        return out
    }
    override fun toString() = "RACE type=0x%02x opcode=0x%04x%s".format(type, opcode, if (prefix.isEmpty()) "" else " +prefix")
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

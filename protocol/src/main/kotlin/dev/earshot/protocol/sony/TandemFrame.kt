package dev.earshot.protocol.sony

/**
 * One Sony "Tandem" frame, the link layer under every Sony headset protocol (MDR v1 and v2).
 * Layout from the Sound Connect app (facts only) and mos9527/SonyHeadphonesClient's `Command.cpp`
 * (MIT, see docs/reference/sonyheadphonesclient):
 * ```
 *  3E | escape( dataType | seq | payloadLength u32 BE | payload | sum8 ) | 3C
 * ```
 * `sum8` is the low byte of the plain sum of everything before it (type, seq, length, payload).
 * Escaping covers the **whole** body, the length field and the checksum included: 0x3C → `3D 2C`,
 * 0x3D → `3D 2D`, 0x3E → `3D 2E` (unescape = the byte after 0x3D OR 0x10).
 *
 * [seq] is the 1-bit stop-and-wait sequence number (0 or 1). An ACK is the fixed frame
 * `3E 01 <seq> 00 00 00 00 <sum> 3C`.
 */
class TandemFrame(
    val dataType: Int,
    val seq: Int,
    val payload: ByteArray = ByteArray(0),
) {
    val isAck: Boolean get() = dataType == TandemDataType.ACK

    /** The unescaped body: type, seq, length, payload, checksum. */
    fun body(): ByteArray {
        val out = ByteArray(payload.size + HEADER + 1)
        out[0] = dataType.toByte()
        out[1] = seq.toByte()
        val n = payload.size
        out[2] = (n ushr 24).toByte()
        out[3] = (n ushr 16).toByte()
        out[4] = (n ushr 8).toByte()
        out[5] = n.toByte()
        payload.copyInto(out, HEADER)
        out[out.size - 1] = checksum(out, out.size - 1).toByte()
        return out
    }

    fun encode(): ByteArray {
        val escaped = escape(body())
        val out = ByteArray(escaped.size + 2)
        out[0] = START.toByte()
        escaped.copyInto(out, 1)
        out[out.size - 1] = END.toByte()
        return out
    }

    override fun toString(): String = "type=%02x seq=%d (%d bytes)".format(dataType, seq, payload.size)

    companion object {
        const val START = 0x3E
        const val END = 0x3C
        const val ESCAPE = 0x3D

        /** Type, seq and the 4-byte length. */
        const val HEADER = 6

        fun ack(seq: Int): TandemFrame = TandemFrame(TandemDataType.ACK, seq)

        fun checksum(bytes: ByteArray, count: Int): Int {
            var sum = 0
            for (i in 0 until count) sum += bytes[i].toInt() and 0xFF
            return sum and 0xFF
        }

        fun escape(bytes: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(bytes.size + 8)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v == START || v == END || v == ESCAPE) {
                    out.write(ESCAPE)
                    out.write(v and 0xEF) // 3C → 2C, 3D → 2D, 3E → 2E
                } else {
                    out.write(v)
                }
            }
            return out.toByteArray()
        }

        /** Null when an escape byte is dangling or followed by something that never needed escaping. */
        fun unescape(bytes: ByteArray): ByteArray? {
            val out = java.io.ByteArrayOutputStream(bytes.size)
            var i = 0
            while (i < bytes.size) {
                val v = bytes[i].toInt() and 0xFF
                if (v == ESCAPE) {
                    if (i + 1 >= bytes.size) return null
                    val next = (bytes[i + 1].toInt() and 0xFF) or 0x10
                    if (next != START && next != END && next != ESCAPE) return null
                    out.write(next)
                    i += 2
                } else {
                    out.write(v)
                    i++
                }
            }
            return out.toByteArray()
        }
    }
}

/**
 * The Tandem `DataType` byte: which parser a frame goes to and whether it must be acknowledged
 * (Sound Connect's `tandemfamily.message.DataType`). Table 1 of MDR v2 rides [DATA_MDR], table 2
 * rides [DATA_MDR_NO2]; unknown types are still acknowledged and then ignored, as the app does.
 */
object TandemDataType {
    const val DATA = 0x00
    const val ACK = 0x01
    const val DATA_MC_NO1 = 0x02
    const val DATA_SSH = 0x07
    const val DATA_ICD = 0x09
    const val DATA_EV = 0x0A
    const val DATA_MDR = 0x0C
    const val DATA_COMMON = 0x0D
    const val DATA_MDR_NO2 = 0x0E
    const val SHOT = 0x10
    const val SHOT_MDR = 0x1C
    const val SHOT_COMMON = 0x1D
    const val SHOT_MDR_NO2 = 0x1E
    const val LARGE_DATA_SSH = 0x27
    const val LARGE_DATA_MDR = 0x2C
    const val LARGE_DATA_COMMON = 0x2D

    /** SHOT types (0x1x) are fire-and-forget; ACKs are never acknowledged; everything else is. */
    fun needsAck(type: Int): Boolean = type != ACK && type !in SHOT..0x1F
}

/**
 * Turns the RFCOMM byte stream back into frames. Chunks arrive split and merged arbitrarily. A
 * frame is everything from a 0x3E to the next 0x3C (neither can appear escaped inside); one whose
 * checksum fails is dropped silently (the app sends no NAK either: the sender's resend timer
 * recovers). A length field that disagrees with the frame's real size is tolerated, as the app
 * does, and reported through [onLengthMismatch].
 */
class TandemFrameBuffer(
    private val onJunk: (Int) -> Unit = {},
    private val onLengthMismatch: (declared: Int, actual: Int) -> Unit = { _, _ -> },
) {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<TandemFrame> {
        buf += chunk
        val out = ArrayList<TandemFrame>()
        while (true) {
            val start = buf.indexOfFirst { (it.toInt() and 0xFF) == TandemFrame.START }
            if (start < 0) {
                if (buf.isNotEmpty()) onJunk(buf.size)
                buf = ByteArray(0)
                return out
            }
            if (start > 0) {
                onJunk(start)
                buf = buf.copyOfRange(start, buf.size)
            }
            // A second start marker before the end marker: the first frame was cut short.
            var end = -1
            var restart = -1
            for (i in 1 until buf.size) {
                val v = buf[i].toInt() and 0xFF
                if (v == TandemFrame.END) { end = i; break }
                if (v == TandemFrame.START) { restart = i; break }
            }
            if (restart > 0) {
                onJunk(restart)
                buf = buf.copyOfRange(restart, buf.size)
                continue
            }
            if (end < 0) {
                if (buf.size > MAX_FRAME) {
                    onJunk(buf.size)
                    buf = ByteArray(0)
                }
                return out
            }
            val escaped = buf.copyOfRange(1, end)
            buf = buf.copyOfRange(end + 1, buf.size)
            decode(escaped)?.let(out::add) ?: onJunk(escaped.size + 2)
        }
    }

    private fun decode(escaped: ByteArray): TandemFrame? {
        val body = TandemFrame.unescape(escaped) ?: return null
        if (body.size < TandemFrame.HEADER + 1) return null
        if (TandemFrame.checksum(body, body.size - 1) != (body[body.size - 1].toInt() and 0xFF)) return null
        val declared = ((body[2].toInt() and 0xFF) shl 24) or ((body[3].toInt() and 0xFF) shl 16) or
            ((body[4].toInt() and 0xFF) shl 8) or (body[5].toInt() and 0xFF)
        val payload = body.copyOfRange(TandemFrame.HEADER, body.size - 1)
        if (declared != payload.size) onLengthMismatch(declared, payload.size)
        return TandemFrame(body[0].toInt() and 0xFF, body[1].toInt() and 0xFF, payload)
    }

    private companion object {
        /** The biggest Sony reply seen is a few hundred bytes; mos chunks writes above 2048. */
        const val MAX_FRAME = 16 * 1024
    }
}

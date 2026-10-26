package dev.earshot.protocol.jbl

/**
 * One frame of JBL's Harman "AA" protocol (H-AA): the control protocol of JBL's 2021–24 BES and
 * Realtek models on the Harman GATT service. `AA cmd len payload`, no sequence byte, no checksum.
 * A few commands carry a two-byte little-endian length instead ([longLength]): from the headset the
 * device-info TLV (`06`), the EQ (`A2`) and the case notification (`A9`); from the phone the TLV and
 * the EQ write (the EQ read keeps the short form).
 *
 * Facts from the JBL notes §2–3 (JBL Headphones 5.37.10, research "Top brand headset protocols")
 * and jbl-control (AhmadRun, MIT), which captured them on a Wave Buds 2, firmware 2.7.0.
 */
class JblFrame(
    val command: Int,
    payload: ByteArray = ByteArray(0),
    val longLength: Boolean = command in JblCommands.LONG_OUT,
    /** The frame stopped early (a notification cut at a small MTU); its fields up to the cut are real. */
    val truncated: Boolean = false,
) {
    init {
        require(command in 0..0xFF) { "command $command is not a byte" }
    }

    private val body = payload.copyOf()

    val payload: ByteArray get() = body.copyOf()
    val size: Int get() = body.size

    /** Payload byte [i], unsigned. */
    operator fun get(i: Int): Int = body[i].toInt() and 0xFF

    /** The first payload byte: a sub-command or direction marker for most commands. */
    val sub: Int? get() = if (body.isEmpty()) null else get(0)

    fun encode(): ByteArray = if (longLength) {
        require(body.size <= 0xFFFF) { "payload too long for a two-byte length" }
        byteArrayOf(HEADER.toByte(), command.toByte(), (body.size and 0xFF).toByte(), (body.size shr 8).toByte()) + body
    } else {
        require(body.size <= 0xFF) { "payload too long for a one-byte length" }
        byteArrayOf(HEADER.toByte(), command.toByte(), body.size.toByte()) + body
    }

    override fun equals(other: Any?): Boolean =
        other is JblFrame && command == other.command && longLength == other.longLength && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * command + body.contentHashCode()

    /** Command and size only: payloads may carry the headset's name or address. */
    override fun toString(): String = "AA %02x (%d B)%s".format(command, body.size, if (truncated) ", truncated" else "")

    companion object {
        const val HEADER = 0xAA

        /**
         * One whole frame written by the phone (one GATT write each), for the virtual headset and the
         * guard's tests. The EQ uses both length forms, so the form is the one the size fits.
         */
        fun decodeWhole(bytes: ByteArray): JblFrame? {
            if (bytes.size < 3 || bytes[0].toInt() and 0xFF != HEADER) return null
            val command = bytes[1].toInt() and 0xFF
            val short = bytes[2].toInt() and 0xFF
            if (command !in JblCommands.LONG_OUT && bytes.size == 3 + short) {
                return JblFrame(command, bytes.copyOfRange(3, bytes.size), longLength = false)
            }
            if (bytes.size >= 4) {
                val long = short or ((bytes[3].toInt() and 0xFF) shl 8)
                if (bytes.size == 4 + long) return JblFrame(command, bytes.copyOfRange(4, bytes.size), longLength = true)
            }
            return null
        }
    }
}

/**
 * Reassembles headset frames from GATT notifications, as jbl-control does. With the 512-byte MTU
 * the app asks for, a frame arrives in one notification. When the MTU stayed small the headset
 * cuts a long frame instead of continuing it, so a notification that starts a new frame while an
 * older one is still incomplete means the older one was cut: it comes out with
 * [JblFrame.truncated] set rather than swallowing the new one. Bytes before an `AA` are dropped.
 */
class JblFrameBuffer(private val onJunk: (Int) -> Unit = {}) {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<JblFrame> {
        val frames = mutableListOf<JblFrame>()
        if (buf.isNotEmpty() && chunk.isNotEmpty() && chunk[0].toInt() and 0xFF == JblFrame.HEADER) {
            val need = need(buf)
            if (need != null && buf.size < need) {
                frames += make(buf, truncated = true)
                buf = ByteArray(0)
            }
        }
        buf += chunk
        while (buf.isNotEmpty()) {
            val start = buf.indexOfFirst { it.toInt() and 0xFF == JblFrame.HEADER }
            if (start < 0) {
                onJunk(buf.size)
                buf = ByteArray(0)
                break
            }
            if (start > 0) {
                onJunk(start)
                buf = buf.copyOfRange(start, buf.size)
            }
            val need = need(buf) ?: break
            if (buf.size < need) break
            frames += make(buf.copyOfRange(0, need), truncated = false)
            buf = buf.copyOfRange(need, buf.size)
        }
        return frames
    }

    /** What is left as a cut frame (the link went quiet with it incomplete). */
    fun flush(): List<JblFrame> {
        if (buf.isEmpty()) return emptyList()
        val frame = make(buf, truncated = true)
        buf = ByteArray(0)
        return listOf(frame)
    }

    private fun need(b: ByteArray): Int? {
        if (b.size < 3) return null
        val command = b[1].toInt() and 0xFF
        if (command in JblCommands.LONG_IN) {
            if (b.size < 4) return null
            return 4 + ((b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8))
        }
        return 3 + (b[2].toInt() and 0xFF)
    }

    private fun make(raw: ByteArray, truncated: Boolean): JblFrame {
        val command = if (raw.size > 1) raw[1].toInt() and 0xFF else 0
        val long = command in JblCommands.LONG_IN
        val offset = if (long) 4 else 3
        val payload = if (raw.size > offset) raw.copyOfRange(offset, raw.size) else ByteArray(0)
        return JblFrame(command, payload, longLength = long, truncated = truncated)
    }
}

package dev.earshot.protocol.gaia

/**
 * One Qualcomm GAIA frame as it travels over RFCOMM (GAIA v3; v1/v2 share the header). Layout,
 * from the Sennheiser research notes (the Smart Control app's own framer and deframer, facts only)
 * and the MIT-licensed momentum4-control captures of a Momentum 4:
 * ```
 *  FF | version | flags | length (1 byte, 2 if flags bit 1) | vendor u16 BE | command u16 BE | payload | [checksum]
 * ```
 * `length` counts the payload only (not the 4-byte vendor + command header). Flags bit 0 = a
 * trailing XOR checksum of every preceding byte follows; bit 1 = a 16-bit big-endian length, which
 * Qualcomm's framer uses (with version 4) once the payload passes 254 bytes. Example: battery GET
 * `FF 03 00 00 04 95 06 03`, answered by `FF 03 00 01 04 95 07 03 2A` (42 %).
 *
 * BLE GATT carries the same vendor + command + payload without this header; Earshot only speaks
 * the RFCOMM form.
 */
class GaiaFrame(
    /** Vendor id, e.g. [GaiaVendor.QUALCOMM] (0x001D) or Sennheiser's 0x0495. */
    val vendor: Int,
    /** The 16-bit command word, see [GaiaCommand]. */
    val command: Int,
    val payload: ByteArray = ByteArray(0),
    /** 3 for a GAIA v3 session; [encode] switches to 4 by itself for a long payload. */
    val version: Int = VERSION_3,
    /** True when the frame carried (and passed) a trailing checksum, or [encode] should add one. */
    val checksum: Boolean = false,
) {
    val feature: Int get() = GaiaCommand.feature(command)
    val type: Int get() = GaiaCommand.type(command)

    fun encode(): ByteArray {
        val long = payload.size > MAX_SHORT_PAYLOAD
        val flags = (if (long) FLAG_LENGTH16 else 0) or (if (checksum) FLAG_CHECKSUM else 0)
        val lengthBytes = if (long) 2 else 1
        val size = 3 + lengthBytes + HEADER + payload.size + (if (checksum) 1 else 0)
        val out = ByteArray(size)
        out[0] = SOF.toByte()
        out[1] = (if (long) VERSION_4 else version).toByte()
        out[2] = flags.toByte()
        var i = 3
        if (long) {
            out[i++] = (payload.size shr 8).toByte()
        }
        out[i++] = payload.size.toByte()
        out[i++] = (vendor shr 8).toByte()
        out[i++] = vendor.toByte()
        out[i++] = (command shr 8).toByte()
        out[i++] = command.toByte()
        payload.copyInto(out, i)
        if (checksum) out[size - 1] = xor(out, size - 1).toByte()
        return out
    }

    /** Ids and size only: payloads can hold names (device name, paired phones), never logged. */
    override fun toString(): String = "${GaiaCommand.name(vendor, command)} (${payload.size} bytes)"

    companion object {
        const val SOF = 0xFF
        const val VERSION_1 = 1
        const val VERSION_3 = 3
        const val VERSION_4 = 4
        const val FLAG_CHECKSUM = 0x01
        const val FLAG_LENGTH16 = 0x02

        /** Vendor (2) + command (2). */
        const val HEADER = 4

        /** The longest payload with a 1-byte length, as Qualcomm's framer has it. */
        const val MAX_SHORT_PAYLOAD = 254

        fun xor(bytes: ByteArray, count: Int): Int {
            var x = 0
            for (i in 0 until count) x = x xor (bytes[i].toInt() and 0xFF)
            return x
        }
    }
}

/**
 * The 16-bit GAIA v3 command word: `(feature << 9) | (type << 7) | pdu`. A request is type 0;
 * the headset answers with type 2 (response, `| 0x0100`) or type 3 (error, `| 0x0180`, the first
 * payload byte is the reason) and pushes type 1 (notification, `| 0x0080`). A notification carries
 * the id of the GET it reports on (`0x1A05` GET ANC → `0x1A85` push).
 */
object GaiaCommand {
    const val TYPE_COMMAND = 0
    const val TYPE_NOTIFICATION = 1
    const val TYPE_RESPONSE = 2
    const val TYPE_ERROR = 3

    private const val TYPE_BITS = 0x0180

    fun of(feature: Int, pdu: Int, type: Int = TYPE_COMMAND): Int = (feature shl 9) or (type shl 7) or (pdu and 0x7F)

    fun feature(command: Int): Int = (command ushr 9) and 0x7F
    fun type(command: Int): Int = (command ushr 7) and 0x03
    fun pdu(command: Int): Int = command and 0x7F

    /** The request a response, error or notification belongs to (type bits cleared). */
    fun request(command: Int): Int = command and TYPE_BITS.inv() and 0xFFFF

    fun response(command: Int): Int = request(command) or 0x0100
    fun notification(command: Int): Int = request(command) or 0x0080
    fun error(command: Int): Int = request(command) or 0x0180

    fun isResponse(command: Int): Boolean = type(command) == TYPE_RESPONSE
    fun isError(command: Int): Boolean = type(command) == TYPE_ERROR
    fun isNotification(command: Int): Boolean = type(command) == TYPE_NOTIFICATION

    fun name(vendor: Int, command: Int): String = "%04x/%04x".format(vendor, command)
}

/** Vendor ids seen on GAIA links. */
object GaiaVendor {
    /** Qualcomm's own features: Core (version, feature list), and the upgrade / debug ones nobody may touch. */
    const val QUALCOMM = 0x001D
}

/**
 * GAIA v3 error reasons (first payload byte of an error frame). Only 0x05 is confirmed on hardware
 * (invalid button arguments on a Momentum 4, momentum4-control capture); the other names are the
 * usual GAIA v3 ones and unconfirmed here, since Qualcomm's manual is behind a login.
 */
object GaiaError {
    const val FEATURE_NOT_SUPPORTED = 0x00
    const val COMMAND_NOT_SUPPORTED = 0x01
    const val NOT_AUTHENTICATED = 0x02
    const val INSUFFICIENT_RESOURCES = 0x03
    const val AUTHENTICATING = 0x04
    const val INVALID_PARAMETER = 0x05
    const val INCORRECT_STATE = 0x06
    const val IN_PROGRESS = 0x07

    fun name(code: Int): String = when (code) {
        FEATURE_NOT_SUPPORTED -> "feature not supported"
        COMMAND_NOT_SUPPORTED -> "command not supported"
        NOT_AUTHENTICATED -> "not authenticated"
        INSUFFICIENT_RESOURCES -> "insufficient resources"
        AUTHENTICATING -> "authenticating"
        INVALID_PARAMETER -> "invalid parameter"
        INCORRECT_STATE -> "incorrect state"
        IN_PROGRESS -> "in progress"
        else -> "error 0x%02x".format(code)
    }

    /** The firmware has no such feature or command: the setting doesn't exist on this model. */
    fun meansUnsupported(code: Int): Boolean = code == FEATURE_NOT_SUPPORTED || code == COMMAND_NOT_SUPPORTED
}

/**
 * Turns the RFCOMM byte stream back into [GaiaFrame]s. Chunks arrive split and merged at random;
 * bytes that don't start a plausible frame (SOF 0xFF, version 1/3/4, known flags) are skipped one
 * at a time, and a frame whose checksum fails is dropped, so one corrupt read never wedges the link.
 */
class GaiaFrameBuffer(
    private val maxPayload: Int = DEFAULT_MAX_PAYLOAD,
    private val onJunk: (Int) -> Unit = {},
) {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<GaiaFrame> {
        buf += chunk
        val out = ArrayList<GaiaFrame>()
        while (true) {
            val start = buf.indexOfFirst { (it.toInt() and 0xFF) == GaiaFrame.SOF }
            if (start < 0) {
                if (buf.isNotEmpty()) onJunk(buf.size)
                buf = ByteArray(0)
                return out
            }
            if (start > 0) {
                onJunk(start)
                buf = buf.copyOfRange(start, buf.size)
            }
            if (buf.size < 3) return out
            val version = buf[1].toInt() and 0xFF
            val flags = buf[2].toInt() and 0xFF
            if (version !in VERSIONS || flags and KNOWN_FLAGS.inv() != 0) {
                skip(1)
                continue
            }
            val long = flags and GaiaFrame.FLAG_LENGTH16 != 0
            val lengthEnd = if (long) 5 else 4
            if (buf.size < lengthEnd) return out
            val length = if (long) ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF) else buf[3].toInt() and 0xFF
            if (length > maxPayload) {
                skip(1)
                continue
            }
            val hasChecksum = flags and GaiaFrame.FLAG_CHECKSUM != 0
            val total = lengthEnd + GaiaFrame.HEADER + length + (if (hasChecksum) 1 else 0)
            if (buf.size < total) return out
            val frame = buf.copyOfRange(0, total)
            if (hasChecksum && GaiaFrame.xor(frame, total - 1) != (frame[total - 1].toInt() and 0xFF)) {
                // A corrupt frame: drop its SOF only, the real next frame may start inside it.
                skip(1)
                continue
            }
            buf = buf.copyOfRange(total, buf.size)
            val h = lengthEnd
            val vendor = ((frame[h].toInt() and 0xFF) shl 8) or (frame[h + 1].toInt() and 0xFF)
            val command = ((frame[h + 2].toInt() and 0xFF) shl 8) or (frame[h + 3].toInt() and 0xFF)
            val payload = frame.copyOfRange(h + GaiaFrame.HEADER, h + GaiaFrame.HEADER + length)
            out += GaiaFrame(vendor, command, payload, version = version, checksum = hasChecksum)
        }
    }

    private fun skip(n: Int) {
        onJunk(n)
        buf = buf.copyOfRange(n, buf.size)
    }

    private companion object {
        val VERSIONS = setOf(GaiaFrame.VERSION_1, GaiaFrame.VERSION_3, GaiaFrame.VERSION_4)
        const val KNOWN_FLAGS = GaiaFrame.FLAG_CHECKSUM or GaiaFrame.FLAG_LENGTH16

        /** Nothing a control link sends comes close; a bigger length is a desync, not a frame. */
        const val DEFAULT_MAX_PAYLOAD = 4096
    }
}

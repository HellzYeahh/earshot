package dev.earshot.protocol.airoha

/**
 * One Airoha RACE frame: `05 type len16 id16 payload`, little-endian, where the length counts the
 * two id bytes plus the payload. No checksum. Type `5A` is a command (the only kind Earshot sends),
 * `5B` its response and `5D` an indication (JBL notes §2, from the JBL app's Airoha SDK; facts only).
 *
 * RACE is Airoha's chip-level protocol: besides the settings a companion app needs it carries
 * firmware update, flash, NV-key and debug commands (ERNW, CVE-2025-20700/20701/20702), so every
 * frame Earshot sends passes a [RaceWriteGuard] first.
 */
class RacePacket(val type: Int, val id: Int, val payload: ByteArray = ByteArray(0)) {
    init {
        require(id in 0..0xFFFF) { "RACE id ${"0x%x".format(id)} is not 16-bit" }
        require(payload.size <= MAX_PAYLOAD) { "RACE payload of ${payload.size} bytes is too long" }
    }

    val isCommand: Boolean get() = type == TYPE_COMMAND
    val isResponse: Boolean get() = type == TYPE_RESPONSE
    val isIndication: Boolean get() = type == TYPE_INDICATION

    fun encode(): ByteArray {
        val length = ID_BYTES + payload.size
        return byteArrayOf(
            HEAD.toByte(),
            type.toByte(),
            (length and 0xFF).toByte(),
            (length shr 8).toByte(),
            (id and 0xFF).toByte(),
            (id shr 8).toByte(),
        ) + payload
    }

    /** Type and id only: payloads can hold names or addresses, so they stay out of logs. */
    override fun toString(): String = "RACE ${typeName(type)} ${RaceIds.name(id)} (${payload.size} B)"

    companion object {
        const val HEAD = 0x05
        const val TYPE_COMMAND = 0x5A
        const val TYPE_RESPONSE = 0x5B
        const val TYPE_INDICATION = 0x5D

        /** `05 type len16`, before the id. */
        const val PREFIX = 4
        const val ID_BYTES = 2

        /** The largest frame the JBL app builds is a PEQ coefficient table; nothing legitimate comes near this. */
        const val MAX_PAYLOAD = 1024

        val KNOWN_TYPES = setOf(TYPE_COMMAND, TYPE_RESPONSE, TYPE_INDICATION)

        fun command(id: Int, payload: ByteArray = ByteArray(0)) = RacePacket(TYPE_COMMAND, id, payload)

        fun typeName(type: Int): String = when (type) {
            TYPE_COMMAND -> "cmd"
            TYPE_RESPONSE -> "resp"
            TYPE_INDICATION -> "ind"
            else -> "type 0x%02x".format(type)
        }
    }
}

/**
 * Turns the byte stream of a socket or a GATT notification pipe into [RacePacket]s. A chunk may end
 * mid-frame (kept for the next feed) or start with bytes that are not RACE (skipped up to the next
 * `05` that begins a plausible header, reported through [onJunk]).
 */
class RaceFrameBuffer(private val onJunk: (Int) -> Unit = {}) {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<RacePacket> {
        buf += chunk
        val out = ArrayList<RacePacket>()
        while (true) {
            val start = buf.indexOfFirst { (it.toInt() and 0xFF) == RacePacket.HEAD }
            if (start < 0) {
                if (buf.isNotEmpty()) onJunk(buf.size)
                buf = ByteArray(0)
                return out
            }
            if (start > 0) {
                onJunk(start)
                buf = buf.copyOfRange(start, buf.size)
            }
            if (buf.size < 2) return out
            if ((buf[1].toInt() and 0xFF) !in RacePacket.KNOWN_TYPES) {
                skip(1)
                continue
            }
            if (buf.size < RacePacket.PREFIX) return out
            val length = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
            if (length < RacePacket.ID_BYTES || length > RacePacket.ID_BYTES + RacePacket.MAX_PAYLOAD) {
                skip(1)
                continue
            }
            val total = RacePacket.PREFIX + length
            if (buf.size < total) return out
            val id = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)
            out += RacePacket(buf[1].toInt() and 0xFF, id, buf.copyOfRange(RacePacket.PREFIX + RacePacket.ID_BYTES, total))
            buf = buf.copyOfRange(total, buf.size)
        }
    }

    private fun skip(n: Int) {
        onJunk(n)
        buf = buf.copyOfRange(n, buf.size)
    }
}

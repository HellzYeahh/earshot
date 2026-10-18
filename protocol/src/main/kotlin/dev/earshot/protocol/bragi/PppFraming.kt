package dev.earshot.protocol.bragi

/**
 * The Bragi `bos_ppp_framing` link layer that wraps a [BosRpcPacket] (or an encrypted-data message)
 * before it is handed to the device transport. HDLC/PPP-style, but with **no byte stuffing** (the
 * flag byte only ever appears at offset 0) and a fixed control byte:
 * ```
 * 0      0x7E                 flag
 * 1      0xFF                 address (all-stations)
 * 2      0x80                 control; 0x80 = "FCS present" (the app always sends it)
 * 3      protocolId           0x94 cleartext BOS-RPC, 0x95 secure (AEAD) BOS-RPC
 * 4..5   length (big-endian)  length of the body that follows
 * 6      CRC-8/ROHC           over bytes 0..5 (the flag byte IS included)
 * 7..    body                 the BOS-RPC packet, or the encrypted-data message
 * ..     CRC-16/MCRF4XX       over bytes 1..(6+len), big-endian (flag and FCS excluded)
 * ```
 * Verified against captured frames: the header `7E FF 80 94 00 04` has CRC-8 `CE`, and a
 * `device.dev_info` call frames to `7E FF 80 94 00 04 CE 10 02 01 00 AB 00`.
 *
 * The `0x05`-prefixed native Airoha RACE wrapper is a different, lower layer; the first byte tells
 * them apart (0x7E = this framing, 0x05 = native RACE). On the real RFCOMM link the Airoha
 * liblinker may wrap this whole frame in one more vendor header; that outer layer is the transport's
 * concern, not this object's.
 */
object PppFraming {
    const val FLAG = 0x7E
    const val ADDRESS = 0xFF
    const val CONTROL = 0x80 // bit 0x80 = FCS present

    const val PROTOCOL_CLEARTEXT = 0x94
    const val PROTOCOL_SECURE = 0x95

    const val MAX_BODY = 0xFFFF

    /** Wrap [body] (a BOS-RPC packet, or an encrypted-data message when [protocolId] is 0x95). */
    fun frame(body: ByteArray, protocolId: Int = PROTOCOL_CLEARTEXT): ByteArray {
        require(body.size <= MAX_BODY) { "BOS-RPC body ${body.size} exceeds $MAX_BODY" }
        val out = ByteArray(7 + body.size + 2)
        out[0] = FLAG.toByte()
        out[1] = ADDRESS.toByte()
        out[2] = CONTROL.toByte()
        out[3] = protocolId.toByte()
        out[4] = ((body.size ushr 8) and 0xFF).toByte()
        out[5] = (body.size and 0xFF).toByte()
        out[6] = Crc.crc8Rohc(out, 0, 6).toByte()
        body.copyInto(out, 7)
        // FCS covers bytes 1..(6+len): everything except the leading flag and the FCS itself.
        val fcs = Crc.crc16Mcrf4xx(out, 1, 7 + body.size)
        out[7 + body.size] = ((fcs ushr 8) and 0xFF).toByte()
        out[8 + body.size] = (fcs and 0xFF).toByte()
        return out
    }

    /** One decoded frame and how many bytes it consumed from the input. */
    data class Decoded(val protocolId: Int, val body: ByteArray, val consumed: Int) {
        val isSecure: Boolean get() = protocolId == PROTOCOL_SECURE
        override fun equals(other: Any?) = other is Decoded && protocolId == other.protocolId &&
            consumed == other.consumed && body.contentEquals(other.body)
        override fun hashCode() = (protocolId * 31 + consumed) * 31 + body.contentHashCode()
    }

    sealed interface ParseResult {
        data class Frame(val decoded: Decoded) : ParseResult
        /** Not enough bytes yet; wait for more and retry from the same offset. */
        data object Incomplete : ParseResult
        /** The bytes at [at] are not a valid frame start; skip one byte and resync. */
        data class Invalid(val at: Int, val reason: String) : ParseResult
    }

    /**
     * Parse one frame starting at [offset]. Checks both CRCs. Returns [ParseResult.Incomplete] when
     * the buffer is too short to decide, so a reader can accumulate across reads.
     */
    fun parse(data: ByteArray, offset: Int = 0): ParseResult {
        val avail = data.size - offset
        if (avail < 7) return ParseResult.Incomplete
        if (data[offset].toInt() and 0xFF != FLAG) return ParseResult.Invalid(offset, "no 0x7E flag")
        if (Crc.crc8Rohc(data, offset, offset + 6) != (data[offset + 6].toInt() and 0xFF)) {
            return ParseResult.Invalid(offset, "header CRC-8 mismatch")
        }
        val protocolId = data[offset + 3].toInt() and 0xFF
        val len = ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
        val total = 7 + len + 2
        if (avail < total) return ParseResult.Incomplete
        val fcsAt = offset + 7 + len
        val fcs = ((data[fcsAt].toInt() and 0xFF) shl 8) or (data[fcsAt + 1].toInt() and 0xFF)
        if (Crc.crc16Mcrf4xx(data, offset + 1, fcsAt) != fcs) return ParseResult.Invalid(offset, "FCS CRC-16 mismatch")
        return ParseResult.Frame(Decoded(protocolId, data.copyOfRange(offset + 7, offset + 7 + len), total))
    }
}

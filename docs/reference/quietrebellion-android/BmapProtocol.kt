/*
 * BmapProtocol.kt – BMAP packet encoding and decoding.
 * Port of BmapProtocol.cs / python/pybmap/protocol.py.
 *
 * Wire format (big-endian, variable length):
 *   [0] fblock_id   [1] func_id
 *   [2] flags       (device_id<<6 | port_num<<4 | op & 0x0F)
 *   [3] length      [4…] payload
 */
package net.quietrebellion

object Op {
    const val SET:        Byte = 0
    const val GET:        Byte = 1
    const val SETGET:     Byte = 2
    const val STATUS:     Byte = 3
    const val ERROR:      Byte = 4
    const val START:      Byte = 5
    const val RESULT:     Byte = 6
    const val PROCESSING: Byte = 7
}

object BmapError {
    fun name(code: Byte): String = when (code.toInt()) {
        0  -> "Unknown"          ;  1  -> "Length"
        2  -> "Chksum"           ;  3  -> "FblockNotSupp"
        4  -> "FuncNotSupp"      ;  5  -> "OpNotSupp(auth)"
        6  -> "InvalidData"      ;  7  -> "DataUnavail"
        8  -> "Runtime"          ;  9  -> "Timeout"
        10 -> "InvalidState"     ;  15 -> "InvalidTransition"
        20 -> "InsecureTransport"
        else -> "err$code"
    }
}

data class BmapResponse(
    val fblock: Byte,
    val func:   Byte,
    val op:     Byte,
    val payload: ByteArray,
) {
    override fun equals(other: Any?) = other is BmapResponse &&
        fblock == other.fblock && func == other.func &&
        op == other.op && payload.contentEquals(other.payload)
    override fun hashCode() = 31 * (31 * fblock.hashCode() + func.hashCode()) + payload.contentHashCode()
    fun format(): String {
        val hex = payload.joinToString("") { "%02x".format(it) }
        return if (op == Op.ERROR && payload.isNotEmpty())
            "[$fblock.$func] ERROR: ${BmapError.name(payload[0])} ($hex)"
        else "[$fblock.$func] op$op: $hex"
    }
}

object BmapProtocol {
    fun build(fblock: Byte, func: Byte, op: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val pkt = ByteArray(4 + payload.size)
        pkt[0] = fblock
        pkt[1] = func
        pkt[2] = (op.toInt() and 0x0F).toByte()
        pkt[3] = payload.size.toByte()
        payload.copyInto(pkt, 4)
        return pkt
    }

    fun parse(data: ByteArray): BmapResponse? {
        if (data.size < 4) return null
        val op     = (data[2].toInt() and 0x0F).toByte()
        val length = data[3].toInt() and 0xFF
        val end    = 4 + length
        if (data.size < end) return null
        return BmapResponse(data[0], data[1], op, data.copyOfRange(4, end))
    }

    fun parseAll(data: ByteArray): List<BmapResponse> {
        val results = mutableListOf<BmapResponse>()
        var pos = 0
        while (pos + 4 <= data.size) {
            val op     = (data[pos + 2].toInt() and 0x0F).toByte()
            val length = data[pos + 3].toInt() and 0xFF
            val end    = pos + 4 + length
            if (end > data.size) break
            results.add(BmapResponse(data[pos], data[pos+1], op, data.copyOfRange(pos+4, end)))
            pos = end
        }
        return results
    }
}


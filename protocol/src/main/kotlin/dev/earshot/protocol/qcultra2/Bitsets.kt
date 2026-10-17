package dev.earshot.protocol.qcultra2

/**
 * BMAP "supported X" bitsets are **big-endian** byte strings: bit *i* lives in byte
 * `len - 1 - i / 8`, at position `i % 8` of that byte (the Bose app's `BitSetUtil.fromByteArray`).
 * Every set-of-ids field uses it: `[0.2]` blocks, `[x.1]` GetAll function lists (`[7.1]`,
 * `[13.1]`, `[18.1]`), `[1.9]` action masks, `[1.11]` sidetone levels, `[9.2]` notification
 * blocks, `[31.8]` favourites, `[5.1]`/`[5.3]` supported sources/controls.
 *
 * bosectl decoded `[18.1]` and the `[1.9]` masks byte by byte little-endian, which is wrong: the
 * fw 10.12 scan's `[7.1] 0873` only matches the functions that answered (7.0, 7.1, 7.4, 7.5, 7.6,
 * 7.11) when read big-endian.
 */
object Bitset {
    /** Bits set in [bytes]`[offset, offset + length)`, read big-endian. */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Set<Int> {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "bitset range out of bounds" }
        val out = LinkedHashSet<Int>()
        for (bit in 0 until length * 8) {
            val byte = bytes[offset + length - 1 - bit / 8].toInt() and 0xFF
            if ((byte shr (bit % 8)) and 1 == 1) out += bit
        }
        return out
    }

    /** [bits] as a big-endian bitset of exactly [byteLength] bytes. Throws when a bit does not fit. */
    fun encode(bits: Set<Int>, byteLength: Int): ByteArray {
        require(byteLength >= 0) { "negative length" }
        val out = ByteArray(byteLength)
        for (bit in bits) {
            require(bit in 0 until byteLength * 8) { "bit $bit does not fit in $byteLength bytes" }
            val i = byteLength - 1 - bit / 8
            out[i] = (out[i].toInt() or (1 shl (bit % 8))).toByte()
        }
        return out
    }

    /** Smallest byte count that holds [bits] (at least [minBytes]). */
    fun bytesFor(bits: Set<Int>, minBytes: Int = 1): Int = maxOf(minBytes, ((bits.maxOrNull() ?: -1) + 8) / 8)
}

/**
 * `[x.1]` GetAll answered to a GET: the big-endian bitset of the functions the block implements.
 * Real frames (fw 10.12): `[7.1] 0873` → {0, 1, 4, 5, 6, 11} (FblockInfo, GetAll, Power,
 * FactoryDefault, ShipMode, Reset); `[13.1] ff83` → {0, 1, 7..15}; `[18.1] 0339083e07` →
 * {0, 1, 2, 9..13, 19, 24, 27, 28, 29, 32, 33}. (`[1.1]`, `[2.1]` and `[31.1]` answer GET with
 * error 5: on those blocks GetAll is a START that streams every function's STATUS.)
 */
object SupportedFunctions {
    fun parse(payload: ByteArray): Set<Int> = Bitset.decode(payload)
}

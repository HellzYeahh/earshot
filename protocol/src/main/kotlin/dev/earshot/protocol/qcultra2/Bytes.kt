package dev.earshot.protocol.qcultra2

/** Unsigned read of byte [i]. */
internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

/** Signed read of byte [i] (two's complement, -128..127). */
internal fun ByteArray.s8(i: Int): Int = this[i].toInt()

/** Big-endian unsigned 16-bit read at [i]. */
internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

/** Big-endian signed 16-bit read at [i] (-32768..32767). */
internal fun ByteArray.s16be(i: Int): Int = u16be(i).toShort().toInt()

/** Big-endian unsigned 32-bit read at [i]. */
internal fun ByteArray.u32be(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

/** UTF-8 decode of [from, to), stopping at the first NUL (fixed-width, null-padded fields). */
internal fun ByteArray.utf8Until(from: Int, to: Int = size): String {
    val end = minOf(to, size)
    var nul = from
    while (nul < end && this[nul] != 0.toByte()) nul++
    return String(this, from, nul - from, Charsets.UTF_8)
}

internal fun Boolean.toByteValue(): Byte = if (this) 1 else 0

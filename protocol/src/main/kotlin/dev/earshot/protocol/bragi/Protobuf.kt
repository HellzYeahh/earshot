package dev.earshot.protocol.bragi

import java.io.ByteArrayOutputStream

/**
 * A minimal protobuf (proto3) writer and reader, just enough for the handful of BOS-RPC messages
 * the Crusher control plane uses (see `CrusherMessages.kt`). Earshot does not pull
 * in a protobuf runtime: the messages are tiny (scalars, enums, floats and one repeated nested
 * message for EQ bands), so hand-rolling keeps `:protocol` dependency-free.
 *
 * Wire types used here: VARINT(0) for bool/enum/uint32, FIXED32(5) for float, LEN(2) for
 * length-delimited bytes/strings/embedded messages. proto3 scalar fields are omitted when they
 * hold the default value; [ProtoReader] therefore treats any field it does not see as the default.
 */

internal const val WIRE_VARINT = 0
internal const val WIRE_FIXED64 = 1
internal const val WIRE_LEN = 2
internal const val WIRE_FIXED32 = 5

internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = writeVarint(((field.toLong() shl 3) or wire.toLong()))

    fun writeVarint(value: Long): ProtoWriter {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { out.write(b); break } else out.write(b or 0x80)
        }
        return this
    }

    /** A proto3 scalar at its default value is omitted, matching the app's encoder. */
    fun uint32(field: Int, value: Int, writeDefault: Boolean = false): ProtoWriter {
        if (value != 0 || writeDefault) { tag(field, WIRE_VARINT); writeVarint(value.toLong() and 0xFFFFFFFFL) }
        return this
    }

    fun bool(field: Int, value: Boolean, writeDefault: Boolean = false): ProtoWriter {
        if (value || writeDefault) { tag(field, WIRE_VARINT); writeVarint(if (value) 1 else 0) }
        return this
    }

    /** Enums are varints; value 0 (UNSPECIFIED) is omitted unless [writeDefault]. */
    fun enum(field: Int, value: Int, writeDefault: Boolean = false): ProtoWriter = uint32(field, value, writeDefault)

    fun float(field: Int, value: Float, writeDefault: Boolean = false): ProtoWriter {
        if (value != 0f || writeDefault) {
            tag(field, WIRE_FIXED32)
            val bits = java.lang.Float.floatToRawIntBits(value)
            out.write(bits and 0xFF); out.write((bits ushr 8) and 0xFF)
            out.write((bits ushr 16) and 0xFF); out.write((bits ushr 24) and 0xFF)
        }
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LEN); writeVarint(value.size.toLong()); out.write(value)
        return this
    }

    fun message(field: Int, body: ByteArray): ProtoWriter = bytes(field, body)

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    companion object {
        inline fun build(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(block).toByteArray()
    }
}

/** One decoded field: its number, wire type, and the raw value (Long for varint/fixed, ByteArray for LEN). */
internal data class ProtoField(val number: Int, val wire: Int, val varint: Long, val bytes: ByteArray?) {
    val int: Int get() = varint.toInt()
    val bool: Boolean get() = varint != 0L
    val float: Float get() = java.lang.Float.intBitsToFloat(varint.toInt())
    override fun equals(other: Any?) = other is ProtoField && number == other.number && wire == other.wire &&
        varint == other.varint && (bytes?.contentEquals(other.bytes ?: ByteArray(0)) ?: (other.bytes == null))
    override fun hashCode() = number * 31 + wire
}

/** Reads a protobuf message into a field list. Tolerant: unknown fields are kept, not an error. */
internal class ProtoReader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {

    fun fields(): List<ProtoField> {
        val out = ArrayList<ProtoField>()
        while (pos < end) {
            val tag = readVarint()
            val field = (tag ushr 3).toInt()
            when (val wire = (tag and 0x7).toInt()) {
                WIRE_VARINT -> out += ProtoField(field, wire, readVarint(), null)
                WIRE_FIXED64 -> out += ProtoField(field, wire, readFixed(8), null)
                WIRE_FIXED32 -> out += ProtoField(field, wire, readFixed(4), null)
                WIRE_LEN -> {
                    val len = readVarint().toInt()
                    require(len >= 0 && pos + len <= end) { "protobuf length $len out of bounds" }
                    out += ProtoField(field, wire, 0, data.copyOfRange(pos, pos + len))
                    pos += len
                }
                else -> throw IllegalArgumentException("unsupported wire type $wire for field $field")
            }
        }
        return out
    }

    private fun readVarint(): Long {
        var result = 0L; var shift = 0
        while (true) {
            require(pos < end) { "truncated varint" }
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            require(shift < 64) { "varint too long" }
        }
        return result
    }

    private fun readFixed(n: Int): Long {
        require(pos + n <= end) { "truncated fixed$n" }
        var v = 0L
        for (i in 0 until n) v = v or ((data[pos++].toLong() and 0xFF) shl (8 * i))
        return v
    }

    companion object {
        /** Convenience: map field number -> field for the common "read a few scalars" case (last wins). */
        fun map(data: ByteArray): Map<Int, ProtoField> = ProtoReader(data).fields().associateBy { it.number }

        /** All LEN sub-messages with [field] (for repeated embedded messages such as EQ bands). */
        fun repeated(data: ByteArray, field: Int): List<ByteArray> =
            ProtoReader(data).fields().filter { it.number == field && it.bytes != null }.map { it.bytes!! }
    }
}

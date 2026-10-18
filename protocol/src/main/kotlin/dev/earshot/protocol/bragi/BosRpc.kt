package dev.earshot.protocol.bragi

/**
 * The Bragi "BOS-RPC" control plane used by the Skullcandy Crusher 1080 ANC (and other "Sound by
 * Bose" / Bragi AppKit products). This is **not** Bose BMAP: the Crusher is an Airoha chip and
 * ignores BMAP entirely, which is why Earshot's BMAP socket opens but never gets a reply.
 *
 * A BOS-RPC call is a tiny fixed header plus a protobuf body, addressed by numeric service and
 * method ids (the gRPC-style method name never reaches the wire):
 * ```
 * 0  flags      ((type << 2) & 0x0C) | 0x10   outbound always FINAL
 * 1  serviceId  low byte of the numeric service id
 * 2  methodId   low byte of the numeric method id
 * 3  tag        8-bit per-connection sequence counter
 * 4.. protobuf  message body (absent for ABORT)
 * ```
 * The body is framed by [PppFraming] and (on an Airoha device) handed to the vendor transport.
 *
 * Source: interoperability analysis of the Skullcandy app 5.0.6 (the protobuf definitions it
 * ships and its `transport.rpc` registry). The wire layout and the numeric service/method ids were
 * cross-checked against the definitions' `bos_service_id` / `bos_method_id` options, and the framed
 * bytes against the golden frames in `BragiWireTest`. The definition files themselves are not
 * redistributed here (they carry a Bragi copyright notice); only the facts needed to talk to the
 * device are.
 */

/** BOS-RPC message type (wire value = enum ordinal, carried in flag bits 2-3). */
enum class BosMessageType(val wire: Int) {
    REQUEST(0), RESPONSE(1), STATUS(2), ABORT(3);

    companion object {
        fun fromFlags(flags: Int): BosMessageType? = entries.firstOrNull { it.wire == (flags ushr 2) and 0x3 }
    }
}

/** A decoded or to-be-encoded BOS-RPC packet (header + body), before/after [PppFraming]. */
data class BosRpcPacket(
    val type: BosMessageType,
    val serviceId: Int,
    val methodId: Int,
    val tag: Int,
    val payload: ByteArray = EMPTY,
    /** FINAL bit (0x10). Outbound is always final; an inbound streamed RESPONSE clears it. */
    val isFinal: Boolean = true,
) {
    /** Header byte 0. The app sets FINAL unconditionally on everything it sends. */
    val flags: Int get() = ((type.wire shl 2) and 0x0C) or (if (isFinal) 0x10 else 0x00)

    fun encode(): ByteArray {
        val header = byteArrayOf(flags.toByte(), (serviceId and 0xFF).toByte(), (methodId and 0xFF).toByte(), (tag and 0xFF).toByte())
        return if (type == BosMessageType.ABORT || payload.isEmpty()) header else header + payload
    }

    val address: BragiServices.Address get() = BragiServices.Address(serviceId, methodId)

    override fun equals(other: Any?) = other is BosRpcPacket && type == other.type && serviceId == other.serviceId &&
        methodId == other.methodId && tag == other.tag && isFinal == other.isFinal && payload.contentEquals(other.payload)
    override fun hashCode(): Int {
        var h = type.hashCode(); h = 31 * h + serviceId; h = 31 * h + methodId; h = 31 * h + tag
        h = 31 * h + isFinal.hashCode(); h = 31 * h + payload.contentHashCode(); return h
    }

    companion object {
        private val EMPTY = ByteArray(0)

        fun request(address: BragiServices.Address, tag: Int, payload: ByteArray = EMPTY) =
            BosRpcPacket(BosMessageType.REQUEST, address.service, address.method, tag, payload)

        fun abort(address: BragiServices.Address, tag: Int) =
            BosRpcPacket(BosMessageType.ABORT, address.service, address.method, tag)

        /**
         * Decode a BOS-RPC packet (the inner body of a PPP frame). Header bytes 1-3 (service,
         * method, tag) are read as the app writes them; on inbound frames the device is assumed to
         * echo them for correlation (unverified on hardware — see the capture checklist).
         */
        fun decode(bytes: ByteArray): BosRpcPacket {
            require(bytes.size >= 4) { "BOS-RPC packet shorter than its 4-byte header" }
            val flags = bytes[0].toInt() and 0xFF
            val type = BosMessageType.fromFlags(flags) ?: throw IllegalArgumentException("bad BOS-RPC type in flags 0x%02x".format(flags))
            return BosRpcPacket(
                type = type,
                serviceId = bytes[1].toInt() and 0xFF,
                methodId = bytes[2].toInt() and 0xFF,
                tag = bytes[3].toInt() and 0xFF,
                payload = if (bytes.size > 4) bytes.copyOfRange(4, bytes.size) else EMPTY,
                isFinal = flags and 0x10 != 0,
            )
        }
    }
}

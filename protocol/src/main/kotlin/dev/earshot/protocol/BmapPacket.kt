package dev.earshot.protocol

/**
 * BMAP (Bose Messaging and Protocol) framing, shared by every Bose Bluetooth product.
 *
 * Wire format:  [block, function, flags, payloadLength, ...payload]
 * Flags byte:   (deviceId shl 6) or (port shl 4) or (operator and 0x0F)
 *
 * This file is the contract between the protocol module and the Bluetooth layer.
 * Device-specific knowledge (which block/function does what) lives in the
 * `dev.earshot.protocol.qcultra2` package, never here.
 */
enum class BmapOperator(val code: Int) {
    // The Bose app 13.3.4 never sends plain SET to settings: every write it makes is SET_GET or
    // START. Error 5 on SET means "this operator is not implemented for that function", not a
    // login gate (docs/bmap-from-bose-app-13.3.4.md section 0).
    SET(0),        // write without read-back; not implemented for most functions (error 5), do not use
    GET(1),        // read; works everywhere
    SET_GET(2),    // write and read back; the operator the Bose app uses for every setting
    STATUS(3),     // reply to GET / SET_GET, or an unsolicited notification (see [9.2])
    ERROR(4),      // error reply, payload[0] is a [BmapError] code
    START(5),      // trigger an action (mode switch, power, pairing, routing, transport, LE Audio check)
    RESULT(6),     // reply to START
    PROCESSING(7); // async START accepted, STATUS frames follow

    companion object {
        fun fromCode(code: Int): BmapOperator? = entries.firstOrNull { it.code == code and 0x0F }
    }
}

/**
 * BMAP error codes. Names and descriptions follow the Bose app 13.3.4 (`BmapPacketError`); code 24
 * is not in the app at all and its meaning is a guess from where the fw 10.12 scan hit it.
 */
enum class BmapError(val code: Int, val description: String) {
    UNKNOWN(0, "Unknown error"),
    LENGTH(1, "Invalid length"),
    CHECKSUM(2, "Invalid checksum"),
    FBLOCK_NOT_SUPPORTED(3, "Function block not supported"),
    FUNC_NOT_SUPPORTED(4, "Function not supported"),
    /** The function exists but this operator is not implemented for it (e.g. GET on a START-only function). */
    OP_NOT_SUPPORTED(5, "The headphones' firmware doesn't support changing this"),
    INVALID_DATA(6, "Invalid data"),
    DATA_UNAVAILABLE(7, "Data unavailable"),
    RUNTIME(8, "Runtime error (firmware rejected the value)"),
    TIMEOUT(9, "Timeout"),
    INVALID_STATE(10, "Not applicable in the current state"),
    DEVICE_NOT_FOUND(11, "Device not in the paired device list"),
    BUSY(12, "Device busy"),
    NO_CONNECTION_TIMEOUT(13, "Could not connect to the paired device"),
    NO_CONNECTION_KEY(14, "Pairing key deleted on the source"),
    /** The Bose app calls 15 OtaUpdate ("update already in progress"); bosectl called it invalid transition. */
    INVALID_TRANSITION(15, "Firmware update in progress"),
    OTA_LOW_BATTERY(16, "Battery too low for a firmware update"),
    OTA_NO_CHARGER(17, "Charger needed for a firmware update"),
    OTA_NOT_ALLOWED(18, "Firmware update not allowed"),
    UNKNOWN_PORT(19, "Unknown port number (no [4.12] routing for it)"),
    INSECURE_TRANSPORT(20, "Insecure transport"),
    INVALID_OTP_KEY(21, "Invalid OTP key"),
    /**
     * Not in the Bose app 13.3.4. The fw 10.12 scan returns it for FblockInfo and most functions of
     * the debug-ish blocks 8 and 22-26, never for ordinary settings. **Guess**: needs a secure /
     * debug session that the phone app never opens.
     */
    SECURE_SESSION_REQUIRED(24, "Needs a secure/debug session (guess: error 24 is undocumented)"),
    /** Payload byte 1 is a block-specific code (block 18: `AuthenticationError`). */
    FBLOCK_SPECIFIC(0xFF, "Block-specific error");

    companion object {
        fun fromCode(code: Int): BmapError? = entries.firstOrNull { it.code == code }
    }
}

/** A (block, function) pair, printed the way bosectl's notes do: `[31.10]`. */
data class FunctionAddress(val block: Int, val function: Int) {
    init {
        require(block in 0..255 && function in 0..255) { "address out of range: [$block.$function]" }
    }
    override fun toString(): String = "[$block.$function]"
}

class BmapPacket(
    val block: Int,
    val function: Int,
    val operator: BmapOperator,
    val payload: ByteArray = EMPTY,
    val deviceId: Int = 0,
    val port: Int = 0,
) {
    init {
        require(block in 0..255 && function in 0..255) { "address out of range: [$block.$function]" }
        require(payload.size <= 255) { "payload too long: ${payload.size}" }
        require(deviceId in 0..3 && port in 0..3) { "deviceId/port out of range" }
    }

    val address: FunctionAddress get() = FunctionAddress(block, function)
    val isError: Boolean get() = operator == BmapOperator.ERROR
    val errorCode: Int? get() = if (isError && payload.isNotEmpty()) payload[0].toInt() and 0xFF else null
    val error: BmapError? get() = errorCode?.let { BmapError.fromCode(it) }

    fun encode(): ByteArray {
        val flags = ((deviceId and 0x03) shl 6) or ((port and 0x03) shl 4) or (operator.code and 0x0F)
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = block.toByte()
        out[1] = function.toByte()
        out[2] = flags.toByte()
        out[3] = payload.size.toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    fun matches(address: FunctionAddress): Boolean = block == address.block && function == address.function

    override fun toString(): String {
        val body = if (isError) "${error?.name ?: "err$errorCode"} (${payload.toHex()})" else payload.toHex()
        return "$address ${operator.name}: $body"
    }

    override fun equals(other: Any?): Boolean =
        other is BmapPacket && block == other.block && function == other.function &&
            operator == other.operator && deviceId == other.deviceId && port == other.port &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var h = block
        h = 31 * h + function
        h = 31 * h + operator.code
        h = 31 * h + payload.contentHashCode()
        return h
    }

    companion object {
        const val HEADER_SIZE = 4
        val EMPTY = ByteArray(0)

        fun get(address: FunctionAddress) = BmapPacket(address.block, address.function, BmapOperator.GET)
        fun setGet(address: FunctionAddress, payload: ByteArray) =
            BmapPacket(address.block, address.function, BmapOperator.SET_GET, payload)
        fun start(address: FunctionAddress, payload: ByteArray = EMPTY) =
            BmapPacket(address.block, address.function, BmapOperator.START, payload)

        /**
         * Decode one packet from the start of [bytes]. Returns null if the bytes do not yet hold
         * a complete packet (short read) or the operator nibble is not a known BMAP operator.
         */
        fun decodeOne(bytes: ByteArray, offset: Int = 0): BmapPacket? {
            if (bytes.size - offset < HEADER_SIZE) return null
            val flags = bytes[offset + 2].toInt() and 0xFF
            val operator = BmapOperator.fromCode(flags and 0x0F) ?: return null
            val length = bytes[offset + 3].toInt() and 0xFF
            if (bytes.size - offset < HEADER_SIZE + length) return null
            return BmapPacket(
                block = bytes[offset].toInt() and 0xFF,
                function = bytes[offset + 1].toInt() and 0xFF,
                operator = operator,
                payload = bytes.copyOfRange(offset + HEADER_SIZE, offset + HEADER_SIZE + length),
                deviceId = (flags shr 6) and 0x03,
                port = (flags shr 4) and 0x03,
            )
        }

        /**
         * Decode every complete packet in [bytes]. The device concatenates frames (a GetAll
         * returns one STATUS per mode), and a Bluetooth read can split or merge them.
         * Returns the packets and the number of bytes consumed; the caller keeps the rest for
         * the next read.
         */
        fun decodeStream(bytes: ByteArray): DecodeResult {
            val packets = ArrayList<BmapPacket>()
            var pos = 0
            while (true) {
                val packet = decodeOne(bytes, pos) ?: break
                packets.add(packet)
                pos += HEADER_SIZE + packet.payload.size
            }
            return DecodeResult(packets, pos)
        }

        fun decodeAll(bytes: ByteArray): List<BmapPacket> = decodeStream(bytes).packets
    }
}

data class DecodeResult(val packets: List<BmapPacket>, val consumed: Int)

/** Thrown by typed protocol helpers when the device answers with ERROR or an unexpected frame. */
class BmapException(
    val address: FunctionAddress,
    val error: BmapError?,
    val rawCode: Int?,
    message: String = buildMessage(address, error, rawCode),
) : Exception(message) {
    /**
     * Error 5: the function exists but does not implement this operator (the Bose app never uses
     * plain SET; a SET_GET answering 5 means the firmware offers no way to change the value).
     */
    val operatorNotSupported: Boolean get() = error == BmapError.OP_NOT_SUPPORTED

    @Deprecated("Error 5 is not an authentication gate", ReplaceWith("operatorNotSupported"))
    val needsAuthentication: Boolean get() = operatorNotSupported

    companion object {
        private fun buildMessage(address: FunctionAddress, error: BmapError?, rawCode: Int?) =
            "$address: ${error?.description ?: "error code $rawCode"}"

        fun from(packet: BmapPacket): BmapException =
            BmapException(packet.address, packet.error, packet.errorCode)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

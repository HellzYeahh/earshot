package dev.earshot.protocol.qcultra2

/**
 * Bluetooth address helpers. BMAP carries addresses as 6 raw bytes, most significant first,
 * in the same order Android's `BluetoothDevice.address` prints them (`C4:0F:08:11:22:33`).
 */
object Mac {
    const val LENGTH = 6

    /** Format 6 bytes at [offset] as `AA:BB:CC:DD:EE:FF` (upper case, Android style). */
    fun format(bytes: ByteArray, offset: Int = 0): String {
        require(offset >= 0 && offset + LENGTH <= bytes.size) { "need $LENGTH bytes at $offset, have ${bytes.size}" }
        return (offset until offset + LENGTH).joinToString(":") { "%02X".format(bytes[it]) }
    }

    /** Parse `AA:BB:CC:DD:EE:FF` (also accepts `-` separators, lower case, or 12 bare hex digits). */
    fun toBytes(mac: String): ByteArray {
        val hex = mac.replace(":", "").replace("-", "").trim()
        require(hex.length == LENGTH * 2 && hex.all { it.isLetterOrDigit() }) { "bad MAC: $mac" }
        return ByteArray(LENGTH) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    fun isValid(mac: String): Boolean = runCatching { toBytes(mac) }.isSuccess

    /** True when all six bytes are zero (padding in the paired-device list). */
    fun isZero(bytes: ByteArray, offset: Int = 0): Boolean =
        (offset until offset + LENGTH).all { bytes[it] == 0.toByte() }
}

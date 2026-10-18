package dev.earshot.protocol.qcultra2

/**
 * `[1.4]` StandbyTimer, the auto-off time in minutes (0 = never). Formats from the Bose app 13.3.4:
 *
 * - GET / STATUS (`SettingsStandbyTimerStatusResponse`): fewer than 3 bytes → minutes = byte 0;
 *   3 bytes or more → minutes = `(byte2 << 8) | byte0` and the unit "supports two-byte minutes".
 *   Byte 1 is not read by the app; what it holds is unknown.
 * - SET_GET (`SettingsStandbyTimerSetGetPacket`): minutes ≤ 255 → `[m]`, else two bytes
 *   **little-endian** `[lo, hi]`.
 *
 * wolverine fw 10.12 answers FuncNotSupp to the GET (it sleeps on head detection instead), so on
 * the QC Ultra 2 the setting is simply absent. Never exercised on hardware.
 */
object StandbyTimer {
    const val MAX_MINUTES = 0xFFFF

    fun parse(payload: ByteArray): Int? = when {
        payload.isEmpty() -> null
        payload.size < 3 -> payload.u8(0)
        else -> (payload.u8(2) shl 8) or payload.u8(0)
    }

    fun build(minutes: Int): ByteArray {
        require(minutes in 0..MAX_MINUTES) { "auto-off $minutes min outside 0..$MAX_MINUTES" }
        return if (minutes <= 0xFF) byteArrayOf(minutes.toByte())
        else byteArrayOf((minutes and 0xFF).toByte(), (minutes shr 8).toByte())
    }

    /** Device side (the virtual unit): the 1-byte form, or the 3-byte form `[lo, 0, hi]` for two-byte firmware. */
    fun buildStatus(minutes: Int, twoByte: Boolean): ByteArray =
        if (!twoByte) byteArrayOf(minutes.coerceIn(0, 0xFF).toByte())
        else byteArrayOf((minutes and 0xFF).toByte(), 0, (minutes shr 8).toByte())
}

/**
 * The auto-off choices the Bose app offers (`defpackage.VW`, the "Auto-Off" list): 5, 20 and 40
 * minutes, 1 and 3 hours, 24 hours, and never (0), in that order. The UI should offer exactly
 * these; a value the headphones report outside the list is still shown as read.
 */
object AutoOffOptions {
    const val NEVER = 0
    val MINUTES: List<Int> = listOf(5, 20, 40, 60, 180, 1440, NEVER)
}

package dev.earshot.protocol.soundcore

/**
 * Soundcore Sleep A30 (model D1301) state, the 150-byte body of `01 01`. Offsets from OpenSCQ30's
 * D1301 support (verified there on firmware 01.91 and 01.07; facts only, the code is GPL) and its
 * device simulator; unverified on our unit until a real read lands in the log.
 * ```
 *  0  host bud (0 left, 1 right)       1  buds linked
 *  2  battery left  3 battery right    0..9 = (n+1)×10 %, 255 = bud absent
 *  4..8 / 9..13  firmware L / R (ASCII "01.91"; all 00 or FF = absent)
 *  14..29 serial (ASCII, starts "1301")    36..40 case firmware
 *  46..49 buttons (L double, R double, L triple, R triple)
 *  65 listening mode (0 Bluetooth, 1 Local)    66 default listening mode    69 low-battery prompt
 *  137 auto-power-off prompt    138 listening-mode prompt    140 ANC on
 *  141 incoming calls during Bluetooth mode    144 post-sleep action (0 pause, 1 play local sounds)
 *  146 tap controls DISABLED    147 ANC prompt tone    148 auto-switch once asleep
 * ```
 * No charging flag, case battery, EQ or ANC level in this model's state.
 */
data class D1301State(
    val leftPercent: Int?,
    val rightPercent: Int?,
    val budsLinked: Boolean,
    val firmwareLeft: String?,
    val firmwareRight: String?,
    val serial: String?,
    val caseFirmware: String?,
    val listeningLocal: Boolean,
    val ancOn: Boolean,
    val tapControlsDisabled: Boolean,
    val defaultListeningLocal: Boolean = false,
    val lowBatteryPrompt: Boolean = false,
    val autoOffPrompt: Boolean = false,
    val listeningModePrompt: Boolean = false,
    val incomingCallsInBluetoothMode: Boolean = false,
    /** 0 pause, 1 play local sounds with noise cancelling off. */
    val postSleepAction: Int = 0,
    val ancPrompt: Boolean = false,
    val autoSwitchOnceAsleep: Boolean = false,
) {
    val firmware: String? get() = listOfNotNull(firmwareLeft, firmwareRight).distinct().joinToString(" / ").ifEmpty { null }

    companion object {
        const val BODY_SIZE = 150

        /** Null when [body] is too short to be this model's state. */
        fun parse(body: ByteArray): D1301State? {
            if (body.size < 149) return null
            return D1301State(
                leftPercent = battery(body.u8(2)),
                rightPercent = battery(body.u8(3)),
                budsLinked = body.u8(1) != 0,
                firmwareLeft = ascii(body, 4, 5),
                firmwareRight = ascii(body, 9, 5),
                serial = ascii(body, 14, 16),
                caseFirmware = ascii(body, 36, 5),
                listeningLocal = body.u8(65) == 1,
                ancOn = body.u8(140) != 0,
                tapControlsDisabled = body.u8(146) != 0,
                defaultListeningLocal = body.u8(66) == 1,
                lowBatteryPrompt = body.u8(69) != 0,
                autoOffPrompt = body.u8(137) != 0,
                listeningModePrompt = body.u8(138) != 0,
                incomingCallsInBluetoothMode = body.u8(141) != 0,
                postSleepAction = body.u8(144),
                ancPrompt = body.u8(147) != 0,
                autoSwitchOnceAsleep = body.u8(148) != 0,
            )
        }

        /** `01 03` battery push: `[left, right, …]` on the same scale. */
        fun parseBattery(body: ByteArray): Pair<Int?, Int?>? =
            if (body.size < 2) null else battery(body.u8(0)) to battery(body.u8(1))

        /** 0..9 → 10..100 %; anything else (255 = bud not there) → null. */
        fun battery(raw: Int): Int? = if (raw in 0..9) (raw + 1) * 10 else null

        private fun ascii(b: ByteArray, from: Int, len: Int): String? {
            val slice = b.copyOfRange(from, from + len)
            if (slice.all { it == 0.toByte() } || slice.all { it == 0xFF.toByte() }) return null
            return String(slice, Charsets.US_ASCII).trim { it <= ' ' || it.code > 0x7E }.ifEmpty { null }
        }

        private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
    }
}

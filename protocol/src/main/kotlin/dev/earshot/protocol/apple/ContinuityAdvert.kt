package dev.earshot.protocol.apple

/**
 * Apple's "proximity pairing" BLE advertisement, which AirPods and Beats send about once a second
 * while out of the case, to anyone listening: the model, each bud's and the case's battery in 10 %
 * steps, and which part is charging. Read-only, no connection, nothing is ever sent.
 *
 * Layout (manufacturer-specific data for company 0x004C, company id already stripped), from public
 * research on Apple's Continuity protocol and re-implemented from those facts; the GPL projects
 * that use it (OpenPods, LibrePods, CAPod) were not copied:
 * ```
 *  [0] 0x07              message type: proximity pairing
 *  [1] length            0x19 on every model seen (25 bytes follow)
 *  [2] prefix            0x01 for a paired device
 *  [3..4] model          big-endian, e.g. 0x0E20 AirPods Pro, 0x1420 AirPods Pro (2nd)
 *  [5] status            bit 0x20 set: the low battery nibble belongs to the RIGHT bud ("not flipped")
 *  [6] buds battery      two nibbles, 0..10 = 0..100 %, 15 = unknown (bud not out / not paired)
 *  [7] charge | case     high nibble: bit 0x1 / 0x2 = a bud charging (which one follows [5]),
 *                        bit 0x4 = the case charging; low nibble: case battery, same scale
 * ```
 * Unverified on our own AirPods yet: the parser logs the raw bytes (no addresses) so a real
 * capture can confirm it. Headphone models (AirPods Max, Beats Solo/Studio) report one battery.
 */
data class ContinuityAdvert(
    val model: Int,
    val leftPercent: Int?,
    val rightPercent: Int?,
    val casePercent: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
) {
    val modelName: String? get() = AppleModels.name(model)
    val isHeadphones: Boolean get() = AppleModels.isHeadphones(model)

    /** One figure for headphones (whichever nibble carries it), else the lower bud: the one that dies first. */
    val headlinePercent: Int?
        get() = if (isHeadphones) leftPercent ?: rightPercent else listOfNotNull(leftPercent, rightPercent).minOrNull()

    companion object {
        const val APPLE_COMPANY_ID = 0x004C
        const val TYPE_PROXIMITY_PAIRING = 0x07

        /** [data] = manufacturer data for [APPLE_COMPANY_ID]. Null for any other Continuity message or a short one. */
        fun parse(data: ByteArray?): ContinuityAdvert? {
            if (data == null || data.size < 8) return null
            if (data.u8(0) != TYPE_PROXIMITY_PAIRING) return null
            val model = (data.u8(3) shl 8) or data.u8(4)
            val status = data.u8(5)
            // Status bit 0x20 tells which bud the nibbles start with (the "primary" one).
            val flipped = status and 0x20 == 0
            val hi = data.u8(6) shr 4
            val lo = data.u8(6) and 0x0F
            val charge = data.u8(7) shr 4
            val case = data.u8(7) and 0x0F
            return ContinuityAdvert(
                model = model,
                leftPercent = percent(if (flipped) hi else lo),
                rightPercent = percent(if (flipped) lo else hi),
                casePercent = percent(case),
                leftCharging = charge and (if (flipped) 0x2 else 0x1) != 0,
                rightCharging = charge and (if (flipped) 0x1 else 0x2) != 0,
                caseCharging = charge and 0x4 != 0,
            )
        }

        /** A nibble 0..10 is 0..100 %; 15 (and anything above 10) means "no reading". */
        private fun percent(nibble: Int): Int? = if (nibble in 0..10) nibble * 10 else null

        private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

        /** The bytes as hex, for the log (adverts carry no address in their payload). */
        fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Model ids seen in proximity-pairing adverts. Best known values from public research; an id not
 * listed still parses (named "AirPods" / "Beats" by the caller from the Bluetooth name).
 */
object AppleModels {
    private val NAMES = mapOf(
        0x0220 to "AirPods",
        0x0F20 to "AirPods (2nd generation)",
        0x1320 to "AirPods (3rd generation)",
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro (2nd generation)",
        0x2420 to "AirPods Pro (2nd generation, USB-C)",
        0x0A20 to "AirPods Max",
        0x0320 to "Powerbeats3",
        0x0B20 to "Powerbeats Pro",
        0x0D20 to "Powerbeats4",
        0x0520 to "BeatsX",
        0x0620 to "Beats Solo3",
        0x0920 to "Beats Studio3",
        0x0C20 to "Beats Solo Pro",
        0x1020 to "Beats Flex",
        0x1120 to "Beats Studio Buds",
        0x1220 to "Beats Fit Pro",
        0x1620 to "Beats Studio Buds+",
        0x1720 to "Beats Studio Pro",
    )

    /** Over-ear and on-ear models: one battery, no case. */
    private val HEADPHONES = setOf(0x0A20, 0x0620, 0x0920, 0x0C20, 0x1720)

    fun name(model: Int): String? = NAMES[model]
    fun isHeadphones(model: Int): Boolean = model in HEADPHONES

    /**
     * Does the advert's [model] fit a headset called [bluetoothName]? Used to keep a stranger's
     * AirPods nearby from being shown as ours: "Max" must match Max, "Pro" Pro, Beats Beats.
     * Unknown models and plain "AirPods" names pass.
     */
    fun fitsName(model: Int, bluetoothName: String?): Boolean {
        val n = bluetoothName?.lowercase() ?: return true
        val modelName = name(model)?.lowercase() ?: return true
        if (("beats" in n) != ("beats" in modelName)) return false // "beats" also matches Powerbeats
        if (("max" in n) != ("max" in modelName)) return false
        if ("airpods" in n && ("pro" in n) != ("pro" in modelName)) return false
        return true
    }
}

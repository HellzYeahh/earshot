package dev.earshot.protocol.qcultra2

/** `[0.3]` payload `40 82 01`: product id big-endian, then variant. */
data class ProductIdVariant(val productId: Int, val variant: Int) {
    val isQcUltra2: Boolean get() = productId == QcUltra2.PRODUCT_ID
    /** Products bosectl lists as sharing the wolverine BMAP layout. */
    val hasQcUltra2Layout: Boolean
        get() = productId == QcUltra2.PRODUCT_ID || productId == QcUltra2.PRODUCT_ID_QC_HEADPHONES_2ND_GEN ||
            productId == QcUltra2.PRODUCT_ID_EARBUDS
}

/**
 * ProductInfo block `[0.x]`. All of these are plain GET reads, verified against the capture
 * snapshots (`0.0`="1.1.0", `0.1`="1.2.0", `0.2`=`87cc23ff`, `0.3`=`408201`,
 * `0.5`="8.2.20+g34cf029", `0.15`="Bose QC Ultra 2 HP").
 */
object ProductInfo {
    /** Text payloads (`[0.0]`, `[0.1]`, `[0.5]`, `[0.7]`, `[0.15]`, `[18.12]`, `[18.13]`): UTF-8/ASCII, NUL-trimmed. */
    fun parseString(payload: ByteArray): String = payload.utf8Until(0).trimEnd()

    /** `[0.5]` e.g. "8.2.20+g34cf029" (semver + git hash). */
    fun parseFirmwareVersion(payload: ByteArray): String = parseString(payload)

    /** `[0.3]` → product id and variant; null when shorter than 3 bytes. */
    fun parseProductIdVariant(payload: ByteArray): ProductIdVariant? =
        if (payload.size < 3) null else ProductIdVariant(payload.u16be(0), payload.u8(2))

    /** `[0.6]` the headphones' own Bluetooth address (6 bytes). `[0.17]` ComponentId is the same preceded by the component id 0x00. */
    fun parseMac(payload: ByteArray): String? = when {
        payload.size >= 7 && payload[0] == 0.toByte() -> Mac.format(payload, 1)
        payload.size >= Mac.LENGTH -> Mac.format(payload, 0)
        else -> null
    }

    /**
     * `[0.2]` AllFblocks: a big-endian bitset of block ids ([Bitset]). Capture `87cc23ff` → blocks
     * 0-9, 13, 18, 19, 22-26, 31: exactly the blocks that answered the fw 10.12 deep scan.
     */
    fun parseAllFunctionBlocks(payload: ByteArray): Set<Int>? =
        if (payload.size < 4) null else Bitset.decode(payload)

    /** `[0.1]` BmapVersion "x.y.z"; fw 10.12 reports "1.2.0" (the Bose app branches on `< 1.2.0`). */
    fun parseBmapVersion(payload: ByteArray): String? = parseString(payload).ifBlank { null }

    /** `[0.23]` LeAudioSupported: `[1]` = yes. fw 10.12 answers `01`; the 8.2.20 capture had `00`. */
    fun parseLeAudioSupported(payload: ByteArray): Boolean? = Toggle.parse(payload)

    /**
     * `[0.17]` ComponentId: `[componentId]` or `[componentId][MAC 6]` (scan `00` + own MAC =
     * [BatteryComponent.SINGLE]). Returns the component code; the MAC is [parseMac].
     */
    fun parseComponentId(payload: ByteArray): Int? = if (payload.isEmpty()) null else payload.u8(0)
}

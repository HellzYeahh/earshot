package dev.earshot.codec

/**
 * Codec vocabulary shared by the dumpsys parser, the binder path and the state mapping.
 *
 * Deliberately pure Kotlin (no `android.*` imports) so the parser and the profile picker run as
 * plain JVM unit tests; the `:app` test configuration does not stub Android classes.
 */
object CodecNames {
    const val SBC = "SBC"
    const val AAC = "AAC"
    const val APTX = "aptX"
    const val APTX_HD = "aptX HD"
    const val APTX_ADAPTIVE = "aptX Adaptive"
    const val APTX_TWS_PLUS = "aptX TWS+"
    const val LDAC = "LDAC"
    const val LC3 = "LC3"
    const val OPUS = "Opus"
    const val SAMSUNG_SCALABLE = "Samsung Scalable"
    const val UNKNOWN = "Unknown"

    /**
     * Normalises whatever the stack prints (`dumpsys` `codecName:`, `BluetoothCodecType.getCodecName()`,
     * AOSP constant names, OEM spellings) to the label the UI shows. Unknown names pass through
     * untouched so a new vendor codec is still displayed rather than hidden.
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return UNKNOWN
        val key = trimmed.lowercase().replace(Regex("[_\\-\\s]+"), " ").trim()
        return when {
            key == "sbc" -> SBC
            key == "aac" -> AAC
            key == "ldac" -> LDAC
            key == "lc3" -> LC3
            key == "opus" -> OPUS
            key.contains("aptx") && key.contains("adaptive") -> APTX_ADAPTIVE
            key.contains("aptx") && key.contains("hd") -> APTX_HD
            key.contains("aptx") && key.contains("tws") -> APTX_TWS_PLUS
            key.contains("aptx") || key == "apt x" -> APTX
            // Samsung's own codec shows up as "SSC", "Scalable", "Samsung Scalable Codec" or the
            // older marketing name "Samsung HD"/"UHQ" depending on the One UI version.
            key == "ssc" || key.contains("scalable") || key.contains("samsung") || key.contains("uhq") -> SAMSUNG_SCALABLE
            // One UI prints "INVALID CODEC" and "UNKNOWN CODEC(7)" for slots it has no name for.
            key == "invalid" || key == "invalid codec" || key == "none" || key.startsWith("unknown codec") -> UNKNOWN
            else -> trimmed
        }
    }

    /**
     * `BluetoothCodecConfig.SOURCE_CODEC_TYPE_*` values. Stable since Android 8.1; LC3 was added
     * in 13 and Opus in 14. Vendor codecs (aptX Adaptive, Samsung Scalable) use higher OEM-specific
     * numbers that only the running stack knows, which is why the dumpsys parser keeps the raw int.
     */
    fun fromAospType(type: Int): String? = when (type) {
        0 -> SBC
        1 -> AAC
        2 -> APTX
        3 -> APTX_HD
        4 -> LDAC
        5 -> LC3
        6 -> OPUS
        else -> null
    }

    fun aospType(name: String): Int? = when (name) {
        SBC -> 0
        AAC -> 1
        APTX -> 2
        APTX_HD -> 3
        LDAC -> 4
        LC3 -> 5
        OPUS -> 6
        else -> null
    }

    /** Ordering used by `applyProfile(BEST_QUALITY)`: higher is better. */
    fun qualityRank(name: String): Int = when (name) {
        APTX_ADAPTIVE -> 70
        LDAC -> 60
        APTX_HD -> 50
        SAMSUNG_SCALABLE -> 45
        OPUS -> 40
        LC3 -> 35
        APTX -> 30
        AAC -> 20
        SBC -> 10
        else -> 0
    }

    // LDAC encodes its quality mode in codecSpecific1; these values come from the LDAC ABR/quality
    // constants in the AOSP a2dp_vendor_ldac code and are what Developer options writes.
    const val LDAC_HQ = "High quality (990 kbps)"
    const val LDAC_SQ = "Standard (660 kbps)"
    const val LDAC_MQ = "Connection (330 kbps)"
    const val LDAC_ABR = "Adaptive"
    val LDAC_QUALITY_MODES: List<String> = listOf(LDAC_HQ, LDAC_SQ, LDAC_MQ, LDAC_ABR)

    fun ldacQualityLabel(codecSpecific1: Long?): String? = when (codecSpecific1) {
        1000L -> LDAC_HQ
        1001L -> LDAC_SQ
        1002L -> LDAC_MQ
        1003L -> LDAC_ABR
        else -> null
    }

    fun ldacQualityValue(label: String?): Long? = when (label) {
        LDAC_HQ -> 1000L
        LDAC_SQ -> 1001L
        LDAC_MQ -> 1002L
        LDAC_ABR -> 1003L
        else -> null
    }

    /** Quality modes we know how to both display and write for a codec; empty when we can only display. */
    fun qualityModesFor(name: String): List<String> = when (name) {
        LDAC -> LDAC_QUALITY_MODES
        else -> emptyList()
    }
}

/**
 * Bit values of `BluetoothCodecConfig.SAMPLE_RATE_*`, `BITS_PER_SAMPLE_*`, `CHANNEL_MODE_*` and the
 * `CODEC_PRIORITY_*` constants. They have not changed since Android 8.1 and are public from API 33;
 * kept here as plain ints so the parser stays Android-free and so the pre-33 hidden constructor can
 * be fed the same numbers.
 */
object CodecMasks {
    const val PRIORITY_DISABLED = -1
    const val PRIORITY_DEFAULT = 0
    const val PRIORITY_HIGHEST = 1_000_000

    private val SAMPLE_RATES = linkedMapOf(
        0x01 to 44100, 0x02 to 48000, 0x04 to 88200, 0x08 to 96000, 0x10 to 176400, 0x20 to 192000,
    )
    private val BITS = linkedMapOf(0x01 to 16, 0x02 to 24, 0x04 to 32)
    private val CHANNELS = linkedMapOf(0x01 to "Mono", 0x02 to "Stereo")

    fun sampleRatesFromMask(mask: Int): List<Int> = SAMPLE_RATES.filterKeys { mask and it != 0 }.values.toList()
    fun sampleRateMask(hz: Int?): Int = SAMPLE_RATES.entries.firstOrNull { it.value == hz }?.key ?: 0

    fun bitDepthsFromMask(mask: Int): List<Int> = BITS.filterKeys { mask and it != 0 }.values.toList()
    fun bitDepthMask(bits: Int?): Int = BITS.entries.firstOrNull { it.value == bits }?.key ?: 0

    fun channelModesFromMask(mask: Int): List<String> = CHANNELS.filterKeys { mask and it != 0 }.values.toList()
    fun channelModeMask(mode: String?): Int = CHANNELS.entries.firstOrNull { it.value.equals(mode, ignoreCase = true) }?.key ?: 0

    /** `(44100|48000)` style labels printed next to the hex mask; used when the mask itself is missing. */
    fun sampleRatesFromLabels(labels: String): List<Int> = labels.split('|').mapNotNull { it.trim().toIntOrNull() }
    fun bitDepthsFromLabels(labels: String): List<Int> = labels.split('|').mapNotNull { it.trim().toIntOrNull() }
    fun channelModesFromLabels(labels: String): List<String> = labels.split('|').mapNotNull { l ->
        when (l.trim().uppercase()) { "MONO" -> "Mono"; "STEREO" -> "Stereo"; "DUAL" -> "Dual"; else -> null }
    }
}

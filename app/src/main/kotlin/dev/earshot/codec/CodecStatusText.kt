package dev.earshot.codec

/**
 * The codec user service hands `BluetoothCodecStatus.toString()` across the process boundary
 * instead of the parcelable itself: the text is the same on every Android version we have seen
 * (`{mCodecConfig:{codecName:...},mCodecsLocalCapabilities:[...],mCodecsSelectableCapabilities:[...]}`,
 * identical to the `obj=` payload of One UI's `CODEC_CONFIG_CHANGED` dumpsys records), and
 * [DumpsysCodecParser] already understands it. Pure Kotlin, so it is unit-tested.
 */
object CodecStatusText {

    /** Parses a status string into the device model for [address]; null when it holds no codec block. */
    fun parse(status: String?, address: String?): ParsedA2dpDevice? {
        if (status.isNullOrBlank()) return null
        val device = runCatching { DumpsysCodecParser.parse(status).devices.firstOrNull() }.getOrNull() ?: return null
        if (device.current == null && device.selectable.isEmpty() && device.local.isEmpty()) return null
        return device.copy(address = address)
    }

    /**
     * True when the *current* config in [status] is [codecName] (normalised) and, when
     * [sampleRateMask] is not 0, runs at that rate. The service polls with this after a switch.
     */
    fun currentMatches(status: String?, codecName: String, sampleRateMask: Int = 0): Boolean {
        val current = parse(status, null)?.current ?: return false
        if (current.codecName != CodecNames.normalize(codecName)) return false
        if (sampleRateMask == 0) return true
        val wanted = CodecMasks.sampleRatesFromMask(sampleRateMask)
        return current.sampleRatesHz.singleOrNull()?.let { it in wanted } ?: false
    }
}

package dev.earshot.codec

import dev.earshot.core.CodecInfo
import dev.earshot.core.CodecOption
import dev.earshot.core.CodecSelection
import dev.earshot.core.ConnectionProfile

/**
 * Maps parsed stack data onto the `core` contract and chooses codecs for the two one-tap profiles.
 * Pure Kotlin so it is covered by JVM unit tests.
 */
object CodecProfiles {

    fun toCodecInfo(config: ParsedCodecConfig): CodecInfo = CodecInfo(
        codecName = config.codecName,
        // The *current* config has exactly one bit set per field; a multi-value mask here means the
        // stack printed capabilities instead, in which case we do not pretend to know.
        sampleRateHz = config.sampleRatesHz.singleOrNull(),
        bitsPerSample = config.bitDepths.singleOrNull(),
        channelMode = config.channelModes.singleOrNull(),
        qualityLabel = when (config.codecName) {
            CodecNames.LDAC -> CodecNames.ldacQualityLabel(config.codecSpecific1)
            else -> null
        },
    )

    /**
     * Builds the option list. Selectable capabilities (supported by both ends) come first, then
     * phone-only codecs from the local capability list, then any extra names the caller learned from
     * `BluetoothA2dp.getSupportedCodecTypes()`. One entry per codec name.
     */
    fun toOptions(
        selectable: List<ParsedCodecConfig>,
        local: List<ParsedCodecConfig>,
        extraPhoneCodecNames: List<String> = emptyList(),
    ): List<CodecOption> {
        val out = linkedMapOf<String, CodecOption>()
        for (c in selectable) {
            val prev = out[c.codecName]
            out[c.codecName] = CodecOption(
                codecName = c.codecName,
                sampleRatesHz = ((prev?.sampleRatesHz ?: emptyList()) + c.sampleRatesHz).distinct().sorted(),
                bitDepths = ((prev?.bitDepths ?: emptyList()) + c.bitDepths).distinct().sorted(),
                qualityModes = CodecNames.qualityModesFor(c.codecName),
                isSelectable = true,
            )
        }
        for (c in local) {
            if (c.codecName in out) continue
            out[c.codecName] = CodecOption(
                codecName = c.codecName,
                sampleRatesHz = c.sampleRatesHz.distinct().sorted(),
                bitDepths = c.bitDepths.distinct().sorted(),
                qualityModes = CodecNames.qualityModesFor(c.codecName),
                isSelectable = false,
            )
        }
        for (name in extraPhoneCodecNames.map(CodecNames::normalize)) {
            if (name in out || name == CodecNames.UNKNOWN) continue
            out[name] = CodecOption(name, emptyList(), emptyList(), CodecNames.qualityModesFor(name), isSelectable = false)
        }
        return out.values.sortedByDescending { CodecNames.qualityRank(it.codecName) }
    }

    /**
     * Chooses what [ConnectionProfile] means given what is actually selectable right now.
     * Returns null when nothing is selectable (no privileged read yet, or no sink connected).
     */
    fun pick(profile: ConnectionProfile, options: List<CodecOption>): CodecSelection? {
        val selectable = options.filter { it.isSelectable }
        if (selectable.isEmpty()) return null
        return when (profile) {
            ConnectionProfile.BEST_QUALITY -> {
                val best = selectable.maxBy { CodecNames.qualityRank(it.codecName) }
                CodecSelection(
                    codecName = best.codecName,
                    sampleRateHz = best.sampleRatesHz.maxOrNull(),
                    bitsPerSample = best.bitDepths.maxOrNull(),
                    qualityMode = if (best.codecName == CodecNames.LDAC) CodecNames.LDAC_HQ else null,
                )
            }
            ConnectionProfile.MOST_STABLE -> {
                // SBC is the mandatory codec every sink implements at a modest bitrate; AAC at 44.1 kHz
                // is the next most conservative choice on Android. Anything else: lowest rank, lowest rate.
                val chosen = selectable.firstOrNull { it.codecName == CodecNames.SBC }
                    ?: selectable.firstOrNull { it.codecName == CodecNames.AAC }
                    ?: selectable.minBy { CodecNames.qualityRank(it.codecName) }
                CodecSelection(
                    codecName = chosen.codecName,
                    sampleRateHz = if (44100 in chosen.sampleRatesHz) 44100 else chosen.sampleRatesHz.minOrNull(),
                    bitsPerSample = chosen.bitDepths.minOrNull(),
                    qualityMode = if (chosen.codecName == CodecNames.LDAC) CodecNames.LDAC_MQ else null,
                )
            }
        }
    }
}

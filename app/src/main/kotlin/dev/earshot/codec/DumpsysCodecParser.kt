package dev.earshot.codec

/** One `BluetoothCodecConfig` as printed by the Bluetooth stack. */
data class ParsedCodecConfig(
    /** Normalised through [CodecNames.normalize]. */
    val codecName: String,
    val rawCodecName: String,
    /** `SOURCE_CODEC_TYPE_*` int when the dump prints it numerically (null on Android 15+, which prints an object). */
    val codecType: Int?,
    val priority: Int?,
    val sampleRatesHz: List<Int>,
    val bitDepths: List<Int>,
    val channelModes: List<String>,
    val codecSpecific1: Long?,
    val codecSpecific2: Long?,
    val codecSpecific3: Long?,
    val codecSpecific4: Long?,
)

/** Codec status of one A2DP sink as seen in `dumpsys bluetooth_manager` (or in `BluetoothCodecStatus.toString()`). */
data class ParsedA2dpDevice(
    val address: String?,
    val current: ParsedCodecConfig?,
    /** Capabilities supported by *both* ends: these are the ones the user can actually switch to. */
    val selectable: List<ParsedCodecConfig>,
    /**
     * Everything the phone's stack can encode, whether or not the sink accepts it. One UI's per-device
     * dump omits this list; it is then recovered from the newest `CODEC_CONFIG_CHANGED` history record.
     */
    val local: List<ParsedCodecConfig>,
)

data class ParsedCodecDump(
    val activeDeviceAddress: String?,
    val devices: List<ParsedA2dpDevice>,
    /**
     * Codecs the phone's stack has enabled, from the service-level `codecConfigPriorities:` table
     * (normalised names, in table order; priority -1 = disabled by the OEM are left out).
     */
    val phoneCodecNames: List<String> = emptyList(),
) {
    /**
     * Picks the section for [address]. One UI / Android 15+ redact addresses in dumps to
     * `XX:XX:XX:XX:AA:BB`, so a suffix match on the last two octets is accepted when nothing matches exactly.
     */
    fun forDevice(address: String?): ParsedA2dpDevice? {
        if (address != null) {
            devices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.let { return it }
            val tail = address.takeLast(5)
            devices.firstOrNull { it.address?.endsWith(tail, ignoreCase = true) == true }?.let { return it }
        }
        if (activeDeviceAddress != null) {
            devices.firstOrNull { it.address.equals(activeDeviceAddress, ignoreCase = true) }?.let { return it }
        }
        return devices.firstOrNull { it.current != null } ?: devices.firstOrNull()
    }
}

/**
 * Parses the A2DP codec information out of `dumpsys bluetooth_manager`.
 *
 * Verified against a real One UI 9 / Android 17 capture (`docs/reference/captures-galaxy/`), which
 * looks like this (AOSP prints `mDevice: <addr>` instead of the `===` header; both are accepted):
 * ```
 * Profile: A2dpService
 *   mActiveDevice: null
 *   codecConfigPriorities:
 *     SBC: 1001
 *     aptX HD: -1
 *     SSC: 8001
 *   codecConfigOffloading:
 *     {codecName:SBC,...,mSampleRate:0x0(NONE),...}      <- offload table, NOT a current config
 *   === A2dpStateMachine for XX:XX:XX:XX:00:11 ===
 *     mCodecConfig: {codecName:AAC,mCodecType:1,...,mSampleRate:0x1(44100),...}
 *     mCodecsSelectableCapabilities:
 *       {codecName:AAC,...}
 *       {codecName:SBC,...}
 *     StateMachineLog:
 *         rec[5]: ... CODEC_CONFIG_CHANGED: ... obj={mCodecConfig:{...},mCodecsLocalCapabilities:[...],mCodecsSelectableCapabilities:[...]}
 * ```
 * Rules that follow from that:
 *  - a block counts as *current* only when `mCodecConfig:` precedes it on the same line;
 *  - `mCodecsSelectableCapabilities:` / `mCodecsLocalCapabilities:` headers carry over to the
 *    following lines until any other header-looking line (`something:`) ends them;
 *  - `rec[n]` history lines never set current/selectable; only their local-capability list is kept
 *    (last record wins) as a fallback, because One UI prints the phone's capabilities nowhere else;
 *  - unlabelled blocks (offload table, anything new) are ignored.
 * `BluetoothCodecStatus.toString()` is the same shape on one line, so this parser serves both.
 */
object DumpsysCodecParser {
    private const val BLOCK_START = "{codecName:"

    private enum class Label(val token: String, val sticky: Boolean) {
        CURRENT("mCodecConfig:", sticky = false),
        SELECTABLE("mCodecsSelectableCapabilities:", sticky = true),
        LOCAL("mCodecsLocalCapabilities:", sticky = true),
    }

    private val MAC = """(?:[0-9A-Fa-fXx]{2}:){5}[0-9A-Fa-f]{2}"""
    private val DEVICE_HEADER = Regex("""(?:A2dpStateMachine for|^\s*mDevice:)\s*($MAC)""")
    private val ACTIVE_LINE = Regex("""mActiveDevice:\s*(\S+)""")
    private val ADDRESS = Regex("""^$MAC$""")
    private val HISTORY_LINE = Regex("""^\s*rec\[\d+]:""")
    private val HEADER_LINE = Regex("""^\s*[^{}]*:\s*$""")
    private val PRIORITIES_HEADER = Regex("""^\s*codecConfigPriorities:\s*$""")
    private val PRIORITY_ENTRY = Regex("""^\s*([^:{}]+?):\s*(-?\d+)\s*$""")

    private val NAME = Regex("""codecName:([^,}]*)""")
    private val CODEC_TYPE = Regex("""mCodecType:(-?\d+)""")
    private val PRIORITY = Regex("""mCodecPriority:(-?\d+)""")
    // Value is "0x3(44100|48000)" on AOSP; the number or the label list may be missing on OEM builds.
    private val SAMPLE_RATE = Regex("""mSampleRate:(0x[0-9a-fA-F]+|-?\d+)?(?:\(([^)]*)\))?""")
    private val BITS = Regex("""mBitsPerSample:(0x[0-9a-fA-F]+|-?\d+)?(?:\(([^)]*)\))?""")
    private val CHANNELS = Regex("""mChannelMode:(0x[0-9a-fA-F]+|-?\d+)?(?:\(([^)]*)\))?""")
    private val SPECIFIC = (1..4).map { Regex("""mCodecSpecific$it:(-?\d+)""") }

    private class DeviceBuilder(val address: String?) {
        var current: ParsedCodecConfig? = null
        val selectable = mutableListOf<ParsedCodecConfig>()
        val local = mutableListOf<ParsedCodecConfig>()
        var historyLocal: List<ParsedCodecConfig> = emptyList()
        fun build() = ParsedA2dpDevice(address, current, selectable.toList(), local.ifEmpty { historyLocal }.toList())
    }

    fun parse(text: String): ParsedCodecDump {
        var active: String? = null
        val devices = mutableListOf<DeviceBuilder>()
        var device: DeviceBuilder? = null
        var stickyLabel: Label? = null
        var inPriorities = false
        val phoneCodecs = linkedMapOf<String, Int>()

        for (line in text.lineSequence()) {
            // Service-level codec table: "    AAC: 2001" lines right after "codecConfigPriorities:".
            if (inPriorities) {
                val entry = PRIORITY_ENTRY.find(line)
                if (entry != null) {
                    phoneCodecs[entry.groupValues[1].trim()] = entry.groupValues[2].toInt()
                    continue
                }
                inPriorities = false
            }
            if (PRIORITIES_HEADER.matches(line)) { inPriorities = true; stickyLabel = null; continue }

            ACTIVE_LINE.find(line)?.groupValues?.get(1)?.takeIf(::looksLikeAddress)?.let { active = it }
            val header = DEVICE_HEADER.find(line)
            if (header != null) {
                val addr = header.groupValues[1].takeIf(::looksLikeAddress)
                // AOSP prints both a header and an mDevice: line for the same sink; keep one section.
                val open = device
                if (open?.address == null || !open.address.equals(addr, ignoreCase = true)) {
                    device = DeviceBuilder(addr).also { devices += it }
                }
                stickyLabel = null
            }

            val blocks = extractBlocks(line)
            val labels = labelsIn(line)

            if (HISTORY_LINE.containsMatchIn(line)) {
                // State-machine log: old CODEC_CONFIG_CHANGED payloads. Harvest local capabilities only.
                val target = device ?: continue
                val local = blocks.mapNotNull { (start, block) ->
                    val label = labels.lastOrNull { it.first < start }?.second
                    if (label == Label.LOCAL) parseCodecConfig(block) else null
                }
                if (local.isNotEmpty()) target.historyLocal = local
                continue
            }

            if (blocks.isEmpty()) {
                val header = labels.lastOrNull()?.second
                when {
                    header?.sticky == true -> stickyLabel = header
                    HEADER_LINE.matches(line) -> stickyLabel = null // some other list follows (offload table, logs, ...)
                }
                continue
            }

            for ((start, block) in blocks) {
                val label = labels.lastOrNull { it.first < start }?.second ?: stickyLabel ?: continue
                val config = parseCodecConfig(block) ?: continue
                // Only a labelled block opens an implicit, address-less section (single-line
                // BluetoothCodecStatus); unlabelled tables such as codecConfigOffloading never do.
                val target = device ?: DeviceBuilder(null).also { devices += it; device = it }
                when (label) {
                    Label.CURRENT -> target.current = config
                    Label.SELECTABLE -> target.selectable += config
                    Label.LOCAL -> target.local += config
                }
            }
            // A header after the last block on the same line applies to the following lines.
            val lastBlockStart = blocks.last().first
            labels.lastOrNull { it.first > lastBlockStart && it.second.sticky }?.let { stickyLabel = it.second }
        }

        val enabled = phoneCodecs.entries
            .filter { it.value >= CodecMasks.PRIORITY_DEFAULT } // -1 = CODEC_PRIORITY_DISABLED
            .map { CodecNames.normalize(it.key) }
            .filter { it != CodecNames.UNKNOWN }
            .distinct()
        return ParsedCodecDump(active, devices.map { it.build() }, enabled)
    }

    /** Parses a single `{codecName:...}` block; null when there is no codec name in it. */
    fun parseCodecConfig(block: String): ParsedCodecConfig? {
        val rawName = NAME.find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val (rateMask, rateLabels) = maskAndLabels(SAMPLE_RATE, block)
        val (bitMask, bitLabels) = maskAndLabels(BITS, block)
        val (chMask, chLabels) = maskAndLabels(CHANNELS, block)
        return ParsedCodecConfig(
            codecName = CodecNames.normalize(rawName),
            rawCodecName = rawName,
            codecType = CODEC_TYPE.find(block)?.groupValues?.get(1)?.toIntOrNull(),
            priority = PRIORITY.find(block)?.groupValues?.get(1)?.toIntOrNull(),
            sampleRatesHz = CodecMasks.sampleRatesFromMask(rateMask).ifEmpty { CodecMasks.sampleRatesFromLabels(rateLabels) },
            bitDepths = CodecMasks.bitDepthsFromMask(bitMask).ifEmpty { CodecMasks.bitDepthsFromLabels(bitLabels) },
            channelModes = CodecMasks.channelModesFromMask(chMask).ifEmpty { CodecMasks.channelModesFromLabels(chLabels) },
            codecSpecific1 = SPECIFIC[0].find(block)?.groupValues?.get(1)?.toLongOrNull(),
            codecSpecific2 = SPECIFIC[1].find(block)?.groupValues?.get(1)?.toLongOrNull(),
            codecSpecific3 = SPECIFIC[2].find(block)?.groupValues?.get(1)?.toLongOrNull(),
            codecSpecific4 = SPECIFIC[3].find(block)?.groupValues?.get(1)?.toLongOrNull(),
        )
    }

    private fun labelsIn(line: String): List<Pair<Int, Label>> =
        Label.entries.flatMap { label ->
            var from = 0
            val hits = mutableListOf<Pair<Int, Label>>()
            while (true) {
                val i = line.indexOf(label.token, from)
                if (i < 0) break
                hits += i to label
                from = i + label.token.length
            }
            hits
        }.sortedBy { it.first }

    private fun maskAndLabels(regex: Regex, block: String): Pair<Int, String> {
        val m = regex.find(block) ?: return 0 to ""
        val value = m.groupValues[1]
        val mask = if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toIntOrNull(16) else value.toIntOrNull()
        return (mask ?: 0) to m.groupValues[2]
    }

    /**
     * Returns every `{codecName:...}` block in [line] with its start offset. Braces are counted
     * because Android 15 prints the codec type as a nested `BluetoothCodecType{...}` object.
     */
    private fun extractBlocks(line: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var from = 0
        while (true) {
            val start = line.indexOf(BLOCK_START, from)
            if (start < 0) break
            var depth = 0
            var end = line.length
            for (i in start until line.length) {
                when (line[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) { end = i + 1; break }
                }
            }
            out += start to line.substring(start, end)
            from = end
        }
        return out
    }

    private fun looksLikeAddress(s: String) = ADDRESS.matches(s)
}

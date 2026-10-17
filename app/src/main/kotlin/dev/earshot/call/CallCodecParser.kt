package dev.earshot.call

/** Voice codec names, as shown in [dev.earshot.core.CallAudioState.callCodec]. */
object VoiceCodec {
    const val CVSD = "CVSD"
    const val MSBC = "mSBC"
    const val LC3_SWB = "LC3"
    const val APTX_SWB = "aptX SWB"
    const val LC3_LE = "LC3 (LE Audio)"

    fun isWideband(name: String): Boolean = name != CVSD

    /** Sample rate the codec implies on HFP. LE Audio varies (16..48 kHz), so it is read from the dump instead. */
    fun impliedSampleRateHz(name: String): Int? = when (name) {
        CVSD -> 8_000
        MSBC -> 16_000
        LC3_SWB, APTX_SWB -> 32_000
        else -> null
    }

    /**
     * Maps whatever a dump prints for the SCO codec to one of the names above, or null.
     * Numeric values follow the HFP codec ids (AT+BCS: 1 = CVSD, 2 = mSBC, 3 = LC3-SWB); the
     * stack's `BTM_SCO_CODEC_LC3` bit is 4, so 4 is LC3 as well. 0 / "none" / booleans are unknown.
     */
    fun normalize(raw: String): String? {
        val v = raw.trim().trim(',', ';', '.', '"', '\'').lowercase()
        return when {
            v.isEmpty() -> null
            "aptx" in v -> APTX_SWB
            "msbc" in v || v == "wbs" || v == "wideband" -> MSBC
            "cvsd" in v || v == "nbs" || v == "narrowband" -> CVSD
            "lc3" in v || v == "swb" -> LC3_SWB
            v == "1" || v == "0x1" || v == "0x01" -> CVSD
            v == "2" || v == "0x2" || v == "0x02" -> MSBC
            v == "3" || v == "4" || v == "0x3" || v == "0x4" || v == "0x03" || v == "0x04" -> LC3_SWB
            else -> null
        }
    }
}

/** One HFP state machine (one headset) from the HeadsetService section. */
data class HfpDeviceDump(
    val address: String?,
    /** SCO audio is up for this headset (`mCurrentState: AudioOn` / `mAudioState: 12`); null when not printed. */
    val audioOn: Boolean?,
    /** Normalised voice codec, from an explicit codec line or the WBS/SWB flags. */
    val codec: String?,
)

data class CallCodecDump(
    val hfpActiveDevice: String?,
    val hfpDevices: List<HfpDeviceDump>,
    /** Codec from a service-level line (not inside a per-device block), or the narrow native SCO fallback. */
    val hfpServiceCodec: String?,
    /** `AudioManager.isBluetoothScoOn(): true` as printed by HeadsetService. */
    val scoOn: Boolean?,
    val leAudioActive: Boolean,
    /** `mActiveAudioInDevice`: an LE headset is the *microphone*, i.e. a call is using LE Audio. */
    val leActiveInDevice: String?,
    val leSampleRateHz: Int?,
) {
    val hasHfpSection: Boolean get() = hfpActiveDevice != null || hfpDevices.isNotEmpty() || hfpServiceCodec != null || scoOn != null
}

/** Where Android says the call audio goes, from the communication device. */
enum class CallRoute { BLUETOOTH_SCO, LE_AUDIO, OTHER, UNKNOWN }

data class CallCodecReading(val codec: String, val wideband: Boolean, val sampleRateHz: Int?)

/**
 * Reads the call (voice) codec out of `dumpsys bluetooth_manager`.
 *
 * Formats targeted (no real in-call capture from the user's Galaxy yet, so this is written from the
 * AOSP sources and kept deliberately tolerant; see `CallCodecParserTest` for the samples):
 * ```
 * Profile: HeadsetService
 *   mActiveDevice: XX:XX:XX:XX:00:11
 *   AudioManager.isBluetoothScoOn(): true
 *   ==== StateMachine for XX:XX:XX:XX:00:11 ====
 *     mCurrentDevice: XX:XX:XX:XX:00:11
 *     mCurrentState: AudioOn            <- HeadsetStateMachine state name
 *     mAudioState: 12                   <- BluetoothHeadset.STATE_AUDIO_CONNECTED
 *     mHasWbsEnabled: true              <- mSBC negotiated (AT+BAC/AT+BCS)
 *     mHasSwbLc3Enabled: false          <- LC3-SWB (HFP 1.9)
 *     mHasSwbAptXEnabled: false         <- Qualcomm aptX voice
 *     StateMachineLog: rec[n]: ...      <- history, ignored (old AT+BCS lines would mislead)
 * Profile: LeAudioService
 *   currentlyActiveGroupId: 1
 *   mActiveAudioOutDevice: ..., mActiveAudioInDevice: ...
 * ```
 * OEM builds (One UI) may print an explicit `...codec...: mSBC` line instead of the flags; any
 * key containing "codec" inside the HeadsetService section is accepted. Outside it only a narrow
 * "SCO ... codec: X" line is trusted.
 */
object CallCodecParser {
    private val MAC = """(?:[0-9A-Fa-fXx]{2}:){5}[0-9A-Fa-f]{2}"""
    private val PROFILE_HEADER = Regex("""^Profile:\s*(\S+)""")
    private val HISTORY_LINE = Regex("""^\s*rec\[\d+]:""")
    private val HFP_DEVICE_HEADER = Regex("""(?:StateMachine for|^\s*mCurrentDevice:)\s*($MAC)""")
    private val ACTIVE_DEVICE = Regex("""^\s*mActiveDevice:\s*($MAC)""")
    private val CURRENT_STATE = Regex("""^\s*mCurrentState:\s*(\S+)""")
    private val AUDIO_STATE = Regex("""^\s*mAudioState:\s*(\S+)""")
    private val SCO_ON = Regex("""isBluetoothScoOn\(\)\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
    private val FLAG = Regex("""^\s*m?(?:Has|Is)?(SwbLc3|SwbAptX|Wbs)\w*\s*[:=]\s*(true|false)""", RegexOption.IGNORE_CASE)
    // Key may contain spaces ("Current SCO codec: CVSD"); A2DP-style `{codecName:` blocks are excluded where used.
    private val CODEC_LINE = Regex("""^\s*([\w .()-]*codec[\w.()-]*)\s*[:=]\s*([A-Za-z0-9+_-]+)""", RegexOption.IGNORE_CASE)
    private val NATIVE_SCO_CODEC = Regex("""\be?sco\b[^:=\n]{0,30}\bcodec\b[^:=\n]{0,12}[:=]\s*([A-Za-z0-9+_-]+)""", RegexOption.IGNORE_CASE)

    private val LE_ACTIVE_GROUP = Regex("""(?:currentlyActiveGroupId|mActiveGroupId|[Aa]ctive group(?: id)?)\s*[:=]\s*(-?\d+)""")
    private val LE_IN_DEVICE = Regex("""mActiveAudioInDevice\s*[:=]\s*(\S+)""")
    private val LE_ON = Regex("""\b(?:m?isLeAudioOn|mLeAudioOn)\w*\s*[:=]\s*(true|false)""", RegexOption.IGNORE_CASE)
    private val SAMPLE_RATE_HZ = Regex("""(?:sampl\w*[ _-]?(?:rate|freq\w*)|frequency)\s*[:=]?\s*(8000|16000|24000|32000|44100|48000)\b""", RegexOption.IGNORE_CASE)
    private val SAMPLE_RATE_KHZ = Regex("""\b(8|16|24|32|44\.1|48)\s*kHz""", RegexOption.IGNORE_CASE)

    private enum class Section { NONE, HFP, LE, OTHER }

    private class HfpBuilder(val address: String?) {
        var audioOn: Boolean? = null
        var explicitCodec: String? = null
        val flags = mutableMapOf<String, Boolean>()
        fun build(): HfpDeviceDump {
            val fromFlags = when {
                flags["swblc3"] == true -> VoiceCodec.LC3_SWB
                flags["swbaptx"] == true -> VoiceCodec.APTX_SWB
                flags["wbs"] == true -> VoiceCodec.MSBC
                flags["wbs"] == false -> VoiceCodec.CVSD
                else -> null
            }
            return HfpDeviceDump(address, audioOn, explicitCodec ?: fromFlags)
        }
    }

    fun parse(text: String): CallCodecDump {
        var section = Section.NONE
        var hfpActive: String? = null
        var scoOn: Boolean? = null
        var serviceCodec: String? = null
        var nativeCodec: String? = null
        val hfp = mutableListOf<HfpBuilder>()
        var device: HfpBuilder? = null
        var leGroupActive = false
        var leOn: Boolean? = null
        var leIn: String? = null
        var leRate: Int? = null

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimStart('\uFEFF')
            // Profile sections are column-0 headers; every line inside them is indented. Any other
            // column-0 line (native stack dumps, "Bluetooth Status") ends the profile section.
            if (line.isNotBlank() && !line[0].isWhitespace()) {
                val profile = PROFILE_HEADER.find(line)?.groupValues?.get(1)
                section = when {
                    profile == null -> if (line.contains("le_audio", true) || line.contains("LeAudio", true) || line.contains("LE Audio", true)) Section.LE else Section.OTHER
                    profile.startsWith("HeadsetService") -> Section.HFP
                    profile.startsWith("LeAudioService") -> Section.LE
                    else -> Section.OTHER
                }
                device = null
            }
            if (HISTORY_LINE.containsMatchIn(line)) continue

            when (section) {
                Section.HFP -> {
                    ACTIVE_DEVICE.find(line)?.let { if (device == null) hfpActive = it.groupValues[1] }
                    SCO_ON.find(line)?.let { scoOn = it.groupValues[1].equals("true", true) }
                    HFP_DEVICE_HEADER.find(line)?.let { m ->
                        val addr = m.groupValues[1]
                        // AOSP prints the "StateMachine for" header and then mCurrentDevice for the same headset.
                        if (device?.address?.equals(addr, true) != true) device = HfpBuilder(addr).also { hfp += it }
                    }
                    val d = device
                    // Either line may say audio is up; the order they are printed in varies by release.
                    CURRENT_STATE.find(line)?.let { if (d != null) d.audioOn = (d.audioOn == true) || it.groupValues[1].contains("AudioOn", true) }
                    AUDIO_STATE.find(line)?.let { m ->
                        val v = m.groupValues[1]
                        if (d != null) d.audioOn = (d.audioOn == true) || v == "12" || v.contains("AUDIO_CONNECTED", true)
                    }
                    FLAG.find(line)?.let { m -> d?.flags?.put(m.groupValues[1].lowercase(), m.groupValues[2].equals("true", true)) }
                    CODEC_LINE.find(line)?.let { m ->
                        if (line.contains("{codecName:")) return@let // an A2DP config block, not voice
                        val codec = VoiceCodec.normalize(m.groupValues[2]) ?: return@let
                        if (d != null) d.explicitCodec = codec else serviceCodec = codec
                    }
                }
                Section.LE -> {
                    LE_ACTIVE_GROUP.find(line)?.let { if (it.groupValues[1].toInt() >= 0) leGroupActive = true }
                    LE_ON.find(line)?.let { leOn = it.groupValues[1].equals("true", true) }
                    LE_IN_DEVICE.find(line)?.let { m -> m.groupValues[1].takeIf { Regex(MAC).matches(it) }?.let { leIn = it } }
                    if (leRate == null) leRate = sampleRate(line)
                }
                Section.NONE, Section.OTHER -> {
                    if (nativeCodec == null) NATIVE_SCO_CODEC.find(line)?.let { nativeCodec = VoiceCodec.normalize(it.groupValues[1]) }
                }
            }
        }

        return CallCodecDump(
            hfpActiveDevice = hfpActive,
            hfpDevices = hfp.map { it.build() },
            hfpServiceCodec = serviceCodec ?: nativeCodec,
            scoOn = scoOn,
            leAudioActive = leOn ?: (leGroupActive || leIn != null),
            leActiveInDevice = leIn,
            leSampleRateHz = leRate,
        )
    }

    /**
     * Picks the codec of the call in progress.
     *
     * @param route from `AudioManager.getCommunicationDevice()`; decides HFP vs LE when known.
     * @param preferAddress the Bose's address, used when several headsets are connected (multipoint)
     *   and none is marked audio-on. Dumps on One UI redact addresses to `XX:XX:XX:XX:00:11`, so the
     *   last two octets are enough to match.
     */
    fun resolve(dump: CallCodecDump, route: CallRoute, preferAddress: String? = null): CallCodecReading? {
        val leLikely = when (route) {
            CallRoute.LE_AUDIO -> true
            CallRoute.BLUETOOTH_SCO -> false
            else -> dump.leActiveInDevice != null && dump.hfpDevices.none { it.audioOn == true } && dump.scoOn != true
        }
        if (leLikely) {
            if (route == CallRoute.LE_AUDIO || dump.leAudioActive) {
                val rate = dump.leSampleRateHz?.takeIf { it in 8_000..48_000 }
                return CallCodecReading(VoiceCodec.LC3_LE, wideband = rate == null || rate >= 16_000, sampleRateHz = rate)
            }
            return null
        }
        val device = pickHfpDevice(dump, preferAddress)
        val codec = device?.codec ?: dump.hfpServiceCodec ?: return null
        return CallCodecReading(codec, VoiceCodec.isWideband(codec), VoiceCodec.impliedSampleRateHz(codec))
    }

    private fun pickHfpDevice(dump: CallCodecDump, preferAddress: String?): HfpDeviceDump? {
        val devices = dump.hfpDevices
        devices.firstOrNull { it.audioOn == true }?.let { return it }
        sameDevice(devices, preferAddress)?.let { return it }
        sameDevice(devices, dump.hfpActiveDevice)?.let { return it }
        return devices.singleOrNull()
    }

    private fun sameDevice(devices: List<HfpDeviceDump>, address: String?): HfpDeviceDump? {
        if (address == null) return null
        devices.firstOrNull { it.address.equals(address, ignoreCase = true) }?.let { return it }
        val tail = address.takeLast(5)
        return devices.firstOrNull { it.address?.endsWith(tail, ignoreCase = true) == true }
    }

    private fun sampleRate(line: String): Int? {
        SAMPLE_RATE_HZ.find(line)?.let { return it.groupValues[1].toInt() }
        SAMPLE_RATE_KHZ.find(line)?.let { return (it.groupValues[1].toDouble() * 1000).toInt() }
        return null
    }

    /** Lines worth logging when nothing resolved, so a real capture can refine the parser. */
    fun diagnosticLines(text: String, max: Int = 20): List<String> {
        val keys = Regex("""codec|wbs|swb|lc3|sco|AudioOn|mAudioState|ActiveGroup|ActiveAudioInDevice""", RegexOption.IGNORE_CASE)
        var inProfile = false
        return text.lineSequence()
            .map { it.trimStart('\uFEFF') }
            .filter { line ->
                if (line.isNotBlank() && !line[0].isWhitespace()) {
                    inProfile = line.startsWith("Profile: HeadsetService") || line.startsWith("Profile: LeAudioService")
                }
                inProfile && !HISTORY_LINE.containsMatchIn(line) && keys.containsMatchIn(line)
            }
            .map { it.trim().take(160) }
            .take(max)
            .toList()
    }
}

package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.FunctionAddress

/**
 * No LE Audio, touch strip, latency control or live AudioSettings on the pre-2024 platforms.
 * Top-level, not in the companion: enum entries are built before the companion is.
 */
private val LEGACY_ABSENT: Set<FunctionAddress> = setOf(
    QcUltra2.AUDIO_SETTINGS, QcUltra2.LE_AUDIO_SUPPORTED, QcUltra2.LE_AUDIO_CONTROL,
    QcUltra2.LE_AUDIO_CHECK, QcUltra2.LATENCY, QcUltra2.DISABLE_CAPTOUCH,
)

// Product ids (bosectl catalog). Top-level for the same reason as LEGACY_ABSENT.
const val PRODUCT_ID_QC_ULTRA_1 = 0x4066
const val PRODUCT_ID_QC_HEADPHONES = 0x4075
const val PRODUCT_ID_QC45 = 0x4039
const val PRODUCT_ID_ULTRA_OPEN = 0x4068

/** The QC Ultra 2's built-in modes; the older models have a prefix of this list. */
private val ULTRA_PRESETS = listOf("Quiet", "Aware", "Immersion", "Cinema")

/**
 * The Bose headphones and earbuds Earshot knows how to talk to, and the few wire facts that differ between
 * them. Everything else (Settings `[1.x]`, battery `[2.2]`, ProductInfo `[0.x]`, the BMAP framing)
 * is shared, and reads the firmware lacks simply answer FuncNotSupp, which the refresh tolerates.
 *
 * Sources: bosectl's catalog and per-device configs (`docs/reference/bosectl/pybmap/devices/`).
 * [verified] says whether the layout below was confirmed on real hardware (by bosectl or by us);
 * unverified models get defaults that fail safe, and `[31.6]` parsing detects the layout from
 * the frame length anyway ([ModeLayout.ofPayload]).
 *
 * Soft-case variants share the hardware and the product id of their base model: the QC SC is a
 * QC Headphones (prince), the QC SE a QC45 (duran).
 */
enum class BoseModel(
    val productIds: Set<Int>,
    val displayName: String,
    val codename: String,
    /** RFCOMM channel of the BMAP control socket. */
    val rfcommChannel: Int,
    val modeLayout: ModeLayout,
    /** `[31.10]` AudioSettings exists. Without it, noise settings live only in the mode slots. */
    val hasLiveAudioSettings: Boolean,
    val hasSpatialAudio: Boolean,
    /** False for open-ear designs: no ANC, no CNC level, no wind block. */
    val hasNoiseCancelling: Boolean,
    /** Names of the built-in modes by slot, for placeholders before the dump; empty when unknown. */
    val presetNames: List<String>,
    /** Built-in modes (SET_GET answers an error); names are what the firmware reports. */
    val presetSlots: IntRange,
    /** Slots that accept the `[31.6]` SET_GET. The STATUS `editable` byte wins when known. */
    val editableSlots: IntRange,
    /** Mode slots when `[31.8]` (whose byte 0 is the real total) cannot be read. */
    val slotCount: Int,
    /**
     * Functions this model is known not to have. The refresh skips them instead of spending a
     * request each (or a timeout, on firmware that stays silent rather than answering FuncNotSupp).
     */
    val knownAbsent: Set<FunctionAddress>,
    val verified: Boolean,
) {
    QC_ULTRA_2(
        setOf(QcUltra2.PRODUCT_ID), "QuietComfort Ultra Headphones (2nd Gen)", "wolverine",
        rfcommChannel = 2, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = true, hasSpatialAudio = true,
        hasNoiseCancelling = true, presetNames = ULTRA_PRESETS,
        presetSlots = QcUltra2.PRESET_MODE_SLOTS, editableSlots = QcUltra2.EDITABLE_MODE_SLOTS,
        slotCount = QcUltra2.MODE_SLOT_COUNT, knownAbsent = emptySet(), verified = true,
    ),

    /** bosectl: firmware 8.x, wolverine MAC prefix, channel 2, 48/40 ModeConfig, Immersion preset. */
    QC_HEADPHONES_2(
        setOf(QcUltra2.PRODUCT_ID_QC_HEADPHONES_2ND_GEN), "QuietComfort Headphones (2nd Gen)", "unconfirmed",
        rfcommChannel = 2, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = true, hasSpatialAudio = true,
        hasNoiseCancelling = true, presetNames = ULTRA_PRESETS,
        presetSlots = QcUltra2.PRESET_MODE_SLOTS, editableSlots = QcUltra2.EDITABLE_MODE_SLOTS,
        slotCount = QcUltra2.MODE_SLOT_COUNT, knownAbsent = emptySet(), verified = false,
    ),

    /**
     * lonestarr. bosectl has no config for it; it is the same Bose app generation as wolverine
     * (Immersive Audio, AudioModes block), so the wolverine layout is the best guess. Cinema is new
     * on the 2nd Gen, so only Quiet / Aware / Immersion are built in.
     */
    QC_ULTRA_1(
        setOf(PRODUCT_ID_QC_ULTRA_1), "QuietComfort Ultra Headphones", "lonestarr",
        rfcommChannel = 2, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = true, hasSpatialAudio = true,
        hasNoiseCancelling = true, presetNames = ULTRA_PRESETS.take(3),
        presetSlots = 0..2, editableSlots = 3..10,
        slotCount = QcUltra2.MODE_SLOT_COUNT, knownAbsent = emptySet(), verified = false,
    ),

    /**
     * prince, verified by bosectl on fw 1.0.6: channel 8 (the SPP UUID resolves to it), 47/39
     * ModeConfig, no ANC toggle, no `[31.10]`; editable slots 2-3 observed. `[31.3]` START acks
     * PROCESSING and the STATUS comes late. Also the QC SC.
     */
    QC_HEADPHONES(
        setOf(PRODUCT_ID_QC_HEADPHONES), "QuietComfort Headphones / QC SC", "prince",
        rfcommChannel = 8, modeLayout = ModeLayout.COMPACT, hasLiveAudioSettings = false, hasSpatialAudio = false,
        hasNoiseCancelling = true, presetNames = ULTRA_PRESETS.take(2),
        presetSlots = 0..1, editableSlots = 2..3, slotCount = 4, knownAbsent = LEGACY_ABSENT, verified = true,
    ),

    /**
     * duran, verified by bosectl on fw 4.0.4: channel 8, needs a GET `[0.1]` before it answers
     * anything (the connect probe sends exactly that), 47/39 ModeConfig, `[31.10]` FuncNotSupp,
     * editable slots 2-3. Also the QC SE.
     */
    QC45(
        setOf(PRODUCT_ID_QC45), "QuietComfort 45 / QC SE", "duran",
        rfcommChannel = 8, modeLayout = ModeLayout.COMPACT, hasLiveAudioSettings = false, hasSpatialAudio = false,
        hasNoiseCancelling = true, presetNames = ULTRA_PRESETS.take(2),
        presetSlots = 0..1, editableSlots = 2..3, slotCount = 4,
        // [1.24] / [1.27] answer FuncNotSupp on hardware (bosectl).
        knownAbsent = LEGACY_ABSENT + setOf(QcUltra2.AUTO_PLAY_PAUSE, QcUltra2.AUTO_ANSWER), verified = true,
    ),

    /**
     * Ultra Open Earbuds, 1st Gen ("serena", 0x4068) from a bosectl device report on fw 4.0.22:
     * channel 2, battery / EQ / Settings writes and `[31.3]` mode switching work; open-ear, so no
     * noise cancelling. The `[31.6]` write layout was never captured, so modes are switch-only.
     * The 2nd Gen's product id is in no reference yet: it is matched by name ([fromName]) and
     * assumed to behave like the 1st Gen. Immersive Audio rides on `[31.10]` if the firmware has it.
     */
    ULTRA_OPEN_EARBUDS(
        setOf(PRODUCT_ID_ULTRA_OPEN), "Ultra Open Earbuds", "serena",
        rfcommChannel = 2, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = true, hasSpatialAudio = true,
        hasNoiseCancelling = false, presetNames = emptyList(),
        presetSlots = 0 until QcUltra2.MODE_SLOT_COUNT, editableSlots = IntRange.EMPTY,
        slotCount = QcUltra2.MODE_SLOT_COUNT,
        knownAbsent = setOf(QcUltra2.CNC, QcUltra2.LE_AUDIO_CONTROL, QcUltra2.LATENCY), verified = false,
    ),
    ;

    /** Custom modes can be written with `[31.6]` SET_GET. */
    val canEditModes: Boolean get() = !editableSlots.isEmpty()

    /** bosectl dumps these with `[31.1]` GetAll; `[31.6]` START is untested on them, so it is the fallback. */
    val dumpsModesWithGetAll: Boolean get() = modeLayout == ModeLayout.COMPACT || this == ULTRA_OPEN_EARBUDS

    /** Wind block and a cancelling level cannot be combined: with wind on, the firmware stores CNC 0 (QC45, bosectl). */
    val windExcludesCnc: Boolean get() = modeLayout == ModeLayout.COMPACT

    companion object {
        /** What Earshot assumes until `[0.3]` says otherwise: the model it was built and tested on. */
        val DEFAULT = QC_ULTRA_2

        /** The other BMAP channel seen on Bose headphones, for the connect fallback. */
        fun otherChannel(channel: Int): Int = if (channel == 8) 2 else 8

        fun fromProductId(productId: Int?): BoseModel? = entries.firstOrNull { productId in it.productIds }

        /**
         * Model from a name: the Bluetooth name before connecting (the product id needs a working
         * channel to read), or `[0.15]` OriginalProductName ("Bose QC Ultra 2 HP" on wolverine) for
         * a product id Earshot does not know yet. Null when the name says nothing useful, e.g. the
         * user renamed the headphones. "QC Ultra" alone is ambiguous between generations; both use
         * channel 2, and `[0.3]` replaces the guess once connected.
         */
        fun fromName(name: String?): BoseModel? {
            val n = name?.lowercase()?.replace("quietcomfort", "qc") ?: return null
            return when {
                "ultra open" in n || "open earbuds" in n -> ULTRA_OPEN_EARBUDS
                "qc ultra" in n && "earbud" !in n ->
                    if ("2nd" in n || "gen 2" in n || Regex("\\bultra (2|ii)\\b").containsMatchIn(n)) QC_ULTRA_2 else QC_ULTRA_1
                Regex("qc ?45").containsMatchIn(n) || Regex("qc ?se\\b").containsMatchIn(n) -> QC45
                Regex("qc ?sc\\b").containsMatchIn(n) -> QC_HEADPHONES
                "qc headphones" in n -> if ("2nd" in n || "gen 2" in n) QC_HEADPHONES_2 else QC_HEADPHONES
                else -> null
            }
        }
    }
}

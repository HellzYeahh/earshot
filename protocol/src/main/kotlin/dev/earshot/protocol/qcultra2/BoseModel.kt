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

/** Earbuds of the prince era: touch controls exist, the 2024 LE Audio / latency functions do not. */
private val EARBUDS_LEGACY_ABSENT: Set<FunctionAddress> = setOf(
    QcUltra2.AUDIO_SETTINGS, QcUltra2.LE_AUDIO_SUPPORTED, QcUltra2.LE_AUDIO_CONTROL,
    QcUltra2.LE_AUDIO_CHECK, QcUltra2.LATENCY,
)

/** Open-ear: no noise cancelling, and bosectl's serena config has no `[31.10]` (docs/bose-models.md §D8). */
private val OPEN_ABSENT: Set<FunctionAddress> = setOf(
    QcUltra2.CNC, QcUltra2.AUDIO_SETTINGS, QcUltra2.LE_AUDIO_CONTROL, QcUltra2.LATENCY,
)

// Product ids (Bose app 13.3.4 `BoseProductId`, docs/bose-models.md §A). Top-level for the same reason as LEGACY_ABSENT.
const val PRODUCT_ID_QC_ULTRA_1 = 0x4066
const val PRODUCT_ID_QC_HEADPHONES = 0x4075
const val PRODUCT_ID_QC45 = 0x4039
const val PRODUCT_ID_ULTRA_OPEN = 0x4068
const val PRODUCT_ID_ULTRA_OPEN_2 = 0x4089
const val PRODUCT_ID_QC_ULTRA_EARBUDS_1 = 0x4072
const val PRODUCT_ID_QC_EARBUDS_2 = 0x4064
const val PRODUCT_ID_QC_EARBUDS = 0x402F
const val PRODUCT_ID_NC700 = 0x4024
const val PRODUCT_ID_QC35 = 0x400C
const val PRODUCT_ID_QC35_II = 0x4020
/** The 2024 "QuietComfort Earbuds" (brussels): a Bragi product, value 35 in the app's enum. */
const val PRODUCT_ID_QC_EARBUDS_2024 = 0x0023

/** The QC Ultra 2's built-in modes; the older models have a prefix of this list. */
private val ULTRA_PRESETS = listOf("Quiet", "Aware", "Immersion", "Cinema")

/** Open-ear presets (docs/bose-models.md §A11, §A12): Stereo p35, Immersion p34, Cinema p36 on the 2nd Gen. */
private val OPEN_PRESETS = listOf("Stereo", "Immersion", "Cinema")

private const val QC35_NOTE = "These headphones use Bose's older app (Bose Connect); Earshot shows the battery only."
private const val BRUSSELS_NOTE =
    "These earbuds use the separate Bose QC Earbuds app and a different protocol; Earshot can't control them."
private const val NC700_NOTE =
    "Basic support: battery, EQ, settings and the noise cancelling level. These headphones have no listening modes."
private const val LANDO_NOTE =
    "Basic support: battery, EQ, settings, the noise cancelling level and switching modes. Saving custom modes is untested on these earbuds."

/** Over-ear, in-ear or open-ear; the core maps it to `FormFactor`. */
enum class ProductForm { HEADPHONES, EARBUDS, OPEN_EARBUDS }

/** How much of the product Earshot drives. */
enum class SupportTier {
    /** Listening modes, noise control and settings. */
    FULL,

    /** Pre-AudioModes or partial-AudioModes products (NC 700, QC Earbuds 2020): battery, EQ, settings, `[1.5]` noise. */
    BASIC,

    /** Recognised but not driven (QC35 / QC35 II, the 2024 QC Earbuds): battery at most. */
    UNSUPPORTED,
}

/**
 * Which mode slots are built in and which take the `[31.6]` write. [BoseModel] gives the
 * catalogue's first guess; the service layer replaces it with what `[31.2]` and `[31.8]` report.
 */
interface ModeSlotPlan {
    val presetSlots: IntRange
    val editableSlots: IntRange
    /** Names of the built-in modes by slot, for placeholders before the dump; empty when unknown. */
    val presetNames: List<String>
    val slotCount: Int
    /** Custom modes can be written with `[31.6]` SET_GET. */
    val canEditModes: Boolean get() = !editableSlots.isEmpty()
}

/**
 * The Bose headphones and earbuds Earshot knows, and the few facts that need a table: names,
 * form factor, the first-guess channel and layouts. Everything else is read from the device
 * at runtime (docs/bose-models.md §0, §E): `[31.2]` mode counts and feature flags, the `[31.6]`
 * STATUS length, `[1.5]` steps, `[2.2]` battery records, and which reads answer at all. Reads
 * the firmware lacks answer FuncNotSupp, which the refresh tolerates.
 *
 * Sources: the Bose app 13.3.4 (`DeviceTypeCapabilities`, product ids, `originalName`s) and
 * bosectl's per-device configs (`docs/reference/bosectl/pybmap/devices/`). [verified] says
 * whether the layout was confirmed on real hardware (by bosectl or by us).
 *
 * Soft-case variants share the hardware and the product id of their base model: the QC SC is a
 * QC Headphones (prince), the QC SE a QC45 (duran).
 */
enum class BoseModel(
    val productIds: Set<Int>,
    /** Marketing name, including the generation where it has one. */
    val displayName: String,
    val codename: String,
    /** The product line without the generation (docs/bose-models.md §D13). */
    val family: String,
    /** "2nd Gen" only where the marketing name carries it (§D13); null otherwise. */
    val generation: String?,
    val formFactor: ProductForm,
    /**
     * First guess for the RFCOMM channel of the BMAP control socket. Only a guess: the app probes
     * the learned channel, this one, 2 / 8 / 9 and SDP, and keeps whichever answers BMAP.
     */
    val rfcommChannel: Int,
    /** `[31.6]` layout guess until a STATUS frame arrives ([ModeLayout.ofStatus]). */
    val modeLayout: ModeLayout,
    /** `[31.10]` AudioSettings is worth asking. Without it, noise settings live in the mode slots or `[1.5]`. */
    val hasLiveAudioSettings: Boolean,
    val hasSpatialAudio: Boolean,
    /** False for open-ear designs: no ANC, no CNC level, no wind block. */
    val hasNoiseCancelling: Boolean,
    /** Wind Block in the modes (first guess; `[31.2]` bit 3 wins). */
    val hasWindBlock: Boolean,
    /**
     * The noise level is written directly with `[1.5]` SET_GET `[step, enabled]` (bosectl's
     * `qc_earbuds.py`; docs/bose-models.md §A6, §A10). Models without block 31 try it whatever
     * this says; a refusal makes the level read-only for the connection.
     */
    val directCnc: Boolean,
    override val presetNames: List<String>,
    /** Built-in modes (SET_GET answers an error); `[31.2]` byte 0 wins. */
    override val presetSlots: IntRange,
    /** Slots that accept the `[31.6]` SET_GET; `[31.2]` byte 1 and the STATUS `editable` byte win. */
    override val editableSlots: IntRange,
    /** Mode slots when neither `[31.8]` nor `[31.2]` can be read. */
    override val slotCount: Int,
    /**
     * Functions this model is known not to have. The refresh skips them instead of spending a
     * request each (or a timeout, on firmware that stays silent rather than answering FuncNotSupp).
     */
    val knownAbsent: Set<FunctionAddress>,
    val verified: Boolean,
    val tier: SupportTier = SupportTier.FULL,
    /** User-visible note for [SupportTier.BASIC] and [SupportTier.UNSUPPORTED] products. */
    val supportNote: String? = null,
    /**
     * Earshot itself has run on this model's real hardware, so the app labels it Tested. Stronger
     * than [verified], which bosectl's captures also earn. Owners' reports add models here.
     */
    val tested: Boolean = false,
) : ModeSlotPlan {
    QC_ULTRA_2(
        setOf(QcUltra2.PRODUCT_ID), "QuietComfort Ultra Headphones (2nd Gen)", "wolverine",
        family = "QuietComfort Ultra Headphones", generation = "2nd Gen", formFactor = ProductForm.HEADPHONES,
        rfcommChannel = 2, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = true, hasSpatialAudio = true,
        hasNoiseCancelling = true, hasWindBlock = true, directCnc = false, presetNames = ULTRA_PRESETS,
        presetSlots = QcUltra2.PRESET_MODE_SLOTS, editableSlots = QcUltra2.EDITABLE_MODE_SLOTS,
        slotCount = QcUltra2.MODE_SLOT_COUNT, knownAbsent = emptySet(), verified = true,
        // The user's own pair, firmware 10.12.12 (2026-10-16).
        tested = true,
    ),

    /**
     * mariah, 0x4083, `[0.15]` "Bose QC 2 HP" (docs/bose-models.md §A3, §D1). Launched August 2026,
     * pre-release in the Bose app 13.3.4. Wind Block and ANC off are in the app copy; bosectl's
     * catalogue says wolverine layout on channel 2. Physical buttons, so `[1.34]` is probably
     * absent (the read simply answers FuncNotSupp).
     */
    QC_HEADPHONES_2(
        setOf(QcUltra2.PRODUCT_ID_QC_HEADPHONES_2ND_GEN), "QuietComfort Headphones (2nd Gen)", "mariah",
        family = "QuietComfort Headphones", generation = "2nd Gen", formFactor = ProductForm.HEADPHONES,
        rfcommChannel = 2, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = true, hasSpatialAudio = true,
        hasNoiseCancelling = true, hasWindBlock = true, directCnc = false, presetNames = ULTRA_PRESETS,
        presetSlots = QcUltra2.PRESET_MODE_SLOTS, editableSlots = QcUltra2.EDITABLE_MODE_SLOTS,
        slotCount = QcUltra2.MODE_SLOT_COUNT, knownAbsent = emptySet(), verified = false,
    ),

    /**
     * lonestarr. No bosectl config or capture; users reported it "not working right" since 0.2.0.
     * Same app generation as wolverine, so its layout is the *first guess*, which the connection
     * corrects (`[31.2]`, `[31.6]` length, a refused `[31.10]`). Bose says "up to seven custom
     * modes" with Quiet / Aware / Immersion built in (no Cinema on the 1st Gen), so 10 slots;
     * Wind Block exists in custom modes (docs/bose-models.md §A2, §D6).
     */
    QC_ULTRA_1(
        setOf(PRODUCT_ID_QC_ULTRA_1), "QuietComfort Ultra Headphones", "lonestarr",
        family = "QuietComfort Ultra Headphones", generation = null, formFactor = ProductForm.HEADPHONES,
        rfcommChannel = 2, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = true, hasSpatialAudio = true,
        hasNoiseCancelling = true, hasWindBlock = true, directCnc = false, presetNames = ULTRA_PRESETS.take(3),
        presetSlots = 0..2, editableSlots = 3..9, slotCount = 10, knownAbsent = emptySet(), verified = false,
    ),

    /**
     * prince, verified by bosectl on fw 1.0.6: 47/39 ModeConfig, no ANC toggle, no `[31.10]`;
     * editable slots 2-3 observed. `[31.3]` START acks PROCESSING and the STATUS comes late (the
     * switch is then verified with a GET). The channel is **not** fixed: the SPP UUID resolved to 8
     * on one unit and 9 on another (bosectl #22/#26), so 8 is only the first guess. Also the QC SC.
     */
    QC_HEADPHONES(
        setOf(PRODUCT_ID_QC_HEADPHONES), "QuietComfort Headphones / QC SC", "prince",
        family = "QuietComfort Headphones", generation = null, formFactor = ProductForm.HEADPHONES,
        rfcommChannel = 8, modeLayout = ModeLayout.COMPACT, hasLiveAudioSettings = false, hasSpatialAudio = false,
        hasNoiseCancelling = true, hasWindBlock = true, directCnc = false, presetNames = ULTRA_PRESETS.take(2),
        presetSlots = 0..1, editableSlots = 2..3, slotCount = 4, knownAbsent = LEGACY_ABSENT, verified = true,
    ),

    /**
     * duran, verified by bosectl on fw 4.0.4: channel 8, needs a GET `[0.1]` before it answers
     * anything (the connect probe sends exactly that), 47/39 ModeConfig, `[31.10]` FuncNotSupp,
     * editable slots 2-3. Older firmware reports `userModes = 0` in `[31.2]` (the Bose app special-
     * cases it), which then wins over the 2..3 here (docs/bose-models.md §D7). Also the QC SE.
     */
    QC45(
        setOf(PRODUCT_ID_QC45), "QuietComfort 45 / QC SE", "duran",
        family = "QuietComfort 45", generation = null, formFactor = ProductForm.HEADPHONES,
        rfcommChannel = 8, modeLayout = ModeLayout.COMPACT, hasLiveAudioSettings = false, hasSpatialAudio = false,
        hasNoiseCancelling = true, hasWindBlock = true, directCnc = false, presetNames = ULTRA_PRESETS.take(2),
        presetSlots = 0..1, editableSlots = 2..3, slotCount = 4,
        // [1.24] / [1.27] answer FuncNotSupp on hardware (bosectl).
        knownAbsent = LEGACY_ABSENT + setOf(QcUltra2.AUTO_PLAY_PAUSE, QcUltra2.AUTO_ANSWER), verified = true,
    ),

    /**
     * goodyear, 0x4024 (docs/bose-models.md §A6). bosectl #44 is a unit with this product id
     * (labelled "QC Ultra 2nd Gen" by its reporter) that refused all of block 31 while Settings,
     * EQ, battery and `[4.x]` worked: no listening modes. Noise is 11 levels; the `[1.5]` SET_GET
     * is the Bose app's own path for it but untested on this model.
     */
    NC700(
        setOf(PRODUCT_ID_NC700), "Noise Cancelling Headphones 700", "goodyear",
        family = "Noise Cancelling Headphones 700", generation = null, formFactor = ProductForm.HEADPHONES,
        rfcommChannel = 8, modeLayout = ModeLayout.BASIC, hasLiveAudioSettings = false, hasSpatialAudio = false,
        hasNoiseCancelling = true, hasWindBlock = false, directCnc = true, presetNames = emptyList(),
        presetSlots = IntRange.EMPTY, editableSlots = IntRange.EMPTY, slotCount = 0,
        knownAbsent = LEGACY_ABSENT, verified = false, tier = SupportTier.BASIC, supportNote = NC700_NOTE,
    ),

    /**
     * edith, verified by bosectl (`qc_ultra2_earbuds.py`): the wolverine feature layout on channel
     * 2, with several `[2.2]` battery records (1 right, 2 left, 3 case, 4 combined). Cinema came
     * as an update (docs/bose-models.md §A7).
     */
    QC_ULTRA_EARBUDS_2(
        setOf(QcUltra2.PRODUCT_ID_EARBUDS), "QuietComfort Ultra Earbuds (2nd Gen)", "edith",
        family = "QuietComfort Ultra Earbuds", generation = "2nd Gen", formFactor = ProductForm.EARBUDS,
        rfcommChannel = 2, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = true, hasSpatialAudio = true,
        hasNoiseCancelling = true, hasWindBlock = true, directCnc = false, presetNames = ULTRA_PRESETS,
        presetSlots = QcUltra2.PRESET_MODE_SLOTS, editableSlots = QcUltra2.EDITABLE_MODE_SLOTS,
        slotCount = QcUltra2.MODE_SLOT_COUNT, knownAbsent = emptySet(), verified = true,
    ),

    /**
     * scotty (no config anywhere): **unverified**, same guesses as the QC Ultra 1st Gen headphones
     * (wolverine layout, Quiet / Aware / Immersion built in, 10 slots), all checked at runtime.
     */
    QC_ULTRA_EARBUDS(
        setOf(PRODUCT_ID_QC_ULTRA_EARBUDS_1), "QuietComfort Ultra Earbuds", "scotty",
        family = "QuietComfort Ultra Earbuds", generation = null, formFactor = ProductForm.EARBUDS,
        rfcommChannel = 2, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = true, hasSpatialAudio = true,
        hasNoiseCancelling = true, hasWindBlock = true, directCnc = false, presetNames = ULTRA_PRESETS.take(3),
        presetSlots = 0..2, editableSlots = 3..9, slotCount = 10, knownAbsent = emptySet(), verified = false,
    ),

    /**
     * smalls, 0x4064, "Bose QC Earbuds II" (docs/bose-models.md §A9): Quiet and Aware plus two
     * custom modes, no spatial audio. Channel, `[31.6]` length and `[31.10]` are unknown; the
     * prince-era guesses (channel 8, slot rewrites, 44-byte layout) are corrected at runtime.
     */
    QC_EARBUDS_2(
        setOf(PRODUCT_ID_QC_EARBUDS_2), "QuietComfort Earbuds II", "smalls",
        family = "QuietComfort Earbuds II", generation = null, formFactor = ProductForm.EARBUDS,
        rfcommChannel = 8, modeLayout = ModeLayout.BASIC, hasLiveAudioSettings = false, hasSpatialAudio = false,
        hasNoiseCancelling = true, hasWindBlock = false, directCnc = false, presetNames = ULTRA_PRESETS.take(2),
        presetSlots = 0..1, editableSlots = 2..3, slotCount = 4, knownAbsent = EARBUDS_LEGACY_ABSENT, verified = false,
    ),

    /**
     * lando, 0x402F, verified by bosectl on fw 2.0.7 (`qc_earbuds.py`; docs/bose-models.md §A10):
     * channel 8, 44-byte `[31.6]` STATUS, no `[31.10]`, noise written directly with `[1.5]` SET_GET
     * `[step, enabled]`. Slots 0-3; the app copy says two custom modes. The 37-byte `[31.6]` write
     * the Bose app would send is **untested** (bosectl's 44-byte echo was refused).
     */
    QC_EARBUDS(
        setOf(PRODUCT_ID_QC_EARBUDS), "QuietComfort Earbuds", "lando",
        family = "QuietComfort Earbuds", generation = null, formFactor = ProductForm.EARBUDS,
        rfcommChannel = 8, modeLayout = ModeLayout.BASIC, hasLiveAudioSettings = false, hasSpatialAudio = false,
        hasNoiseCancelling = true, hasWindBlock = false, directCnc = true, presetNames = ULTRA_PRESETS.take(2),
        presetSlots = 0..1, editableSlots = 2..3, slotCount = 4,
        knownAbsent = LEGACY_ABSENT + setOf(QcUltra2.AUTO_PLAY_PAUSE, QcUltra2.AUTO_ANSWER), verified = true,
        tier = SupportTier.BASIC, supportNote = LANDO_NOTE,
    ),

    /**
     * serena, 0x4068, from a bosectl device report on fw 4.0.22: channel 2, battery / EQ /
     * Settings writes and `[31.3]` switching work; open-ear, so no noise cancelling. Presets Stereo
     * and Immersion; `[31.10]` is not in bosectl's config, so Immersive Audio is a slot setting.
     * The `[31.6]` length was never captured: the 45-byte guess (spatial, no wind/ANC) is replaced
     * by the first STATUS frame (docs/bose-models.md §A11, §D8).
     */
    ULTRA_OPEN_EARBUDS(
        setOf(PRODUCT_ID_ULTRA_OPEN), "Ultra Open Earbuds", "serena",
        family = "Ultra Open Earbuds", generation = null, formFactor = ProductForm.OPEN_EARBUDS,
        rfcommChannel = 2, modeLayout = ModeLayout.SPATIAL, hasLiveAudioSettings = false, hasSpatialAudio = true,
        hasNoiseCancelling = false, hasWindBlock = false, directCnc = false, presetNames = OPEN_PRESETS.take(2),
        presetSlots = 0..1, editableSlots = IntRange.EMPTY, slotCount = QcUltra2.MODE_SLOT_COUNT,
        knownAbsent = OPEN_ABSENT, verified = false,
    ),

    /** aubrey, 0x4089 (case 0x4090), "Bose Ultra Open 2 Earbuds": assumed to behave like serena, plus Cinema (§A12). */
    ULTRA_OPEN_EARBUDS_2(
        setOf(PRODUCT_ID_ULTRA_OPEN_2), "Ultra Open Earbuds (2nd Gen)", "aubrey",
        family = "Ultra Open Earbuds", generation = "2nd Gen", formFactor = ProductForm.OPEN_EARBUDS,
        rfcommChannel = 2, modeLayout = ModeLayout.SPATIAL, hasLiveAudioSettings = false, hasSpatialAudio = true,
        hasNoiseCancelling = false, hasWindBlock = false, directCnc = false, presetNames = OPEN_PRESETS,
        presetSlots = 0..2, editableSlots = IntRange.EMPTY, slotCount = QcUltra2.MODE_SLOT_COUNT,
        knownAbsent = OPEN_ABSENT, verified = false,
    ),

    /**
     * wolfcastle, 0x400C (docs/bose-models.md §B): Bose Connect era, not in the Bose app's BMAP
     * whitelist. bosectl's qc35 config speaks BMAP on channel 8 after a GET `[0.1]`, with only
     * `[1.6]` ANR for noise; Earshot reads the battery and nothing else.
     */
    QC35(
        setOf(PRODUCT_ID_QC35), "QuietComfort 35", "wolfcastle",
        family = "QuietComfort 35", generation = null, formFactor = ProductForm.HEADPHONES,
        rfcommChannel = 8, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = false, hasSpatialAudio = false,
        hasNoiseCancelling = false, hasWindBlock = false, directCnc = false, presetNames = emptyList(),
        presetSlots = IntRange.EMPTY, editableSlots = IntRange.EMPTY, slotCount = 0, knownAbsent = emptySet(),
        verified = false, tier = SupportTier.UNSUPPORTED, supportNote = QC35_NOTE,
    ),

    /** baywolf, 0x4020: same as the QC35 (bosectl uses one config for both). */
    QC35_II(
        setOf(PRODUCT_ID_QC35_II), "QuietComfort 35 II", "baywolf",
        family = "QuietComfort 35 II", generation = null, formFactor = ProductForm.HEADPHONES,
        rfcommChannel = 8, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = false, hasSpatialAudio = false,
        hasNoiseCancelling = false, hasWindBlock = false, directCnc = false, presetNames = emptyList(),
        presetSlots = IntRange.EMPTY, editableSlots = IntRange.EMPTY, slotCount = 0, knownAbsent = emptySet(),
        verified = false, tier = SupportTier.UNSUPPORTED, supportNote = QC35_NOTE,
    ),

    /**
     * brussels, the 2024 "QuietComfort Earbuds" (docs/bose-models.md §A13, §D12): the Bose app sends
     * users to the separate "Bose QC Earbuds" app; the earbuds advertise in the Bragi format and
     * are not BMAP. Listed so a product id or a report can name them; never driven.
     */
    QC_EARBUDS_2024(
        setOf(PRODUCT_ID_QC_EARBUDS_2024), "QuietComfort Earbuds (2024)", "brussels",
        family = "QuietComfort Earbuds", generation = null, formFactor = ProductForm.EARBUDS,
        rfcommChannel = 2, modeLayout = ModeLayout.STANDARD, hasLiveAudioSettings = false, hasSpatialAudio = false,
        hasNoiseCancelling = false, hasWindBlock = false, directCnc = false, presetNames = emptyList(),
        presetSlots = IntRange.EMPTY, editableSlots = IntRange.EMPTY, slotCount = 0, knownAbsent = emptySet(),
        verified = false, tier = SupportTier.UNSUPPORTED, supportNote = BRUSSELS_NOTE,
    ),
    ;

    /** True for the products Earshot recognises but does not drive (battery only). */
    val isUnsupported: Boolean get() = tier == SupportTier.UNSUPPORTED

    /**
     * bosectl dumps every model but wolverine's family with `[31.1]` GetAll; `[31.6]` START is
     * untested there, so it is the fallback.
     */
    val dumpsModesWithGetAll: Boolean get() = modeLayout != ModeLayout.STANDARD

    /** Wind block and a cancelling level cannot be combined: with wind on, the firmware stores CNC 0 (QC45, bosectl). */
    val windExcludesCnc: Boolean get() = modeLayout == ModeLayout.COMPACT

    companion object {
        /** What Earshot assumes until `[0.3]` says otherwise: the model it was built and tested on. */
        val DEFAULT = QC_ULTRA_2

        fun fromProductId(productId: Int?): BoseModel? = entries.firstOrNull { productId in it.productIds }

        private val GEN_2 = Regex("\\b(2nd|gen ?2)\\b")

        /**
         * Model from a name: the Bluetooth name before connecting (the product id needs a working
         * channel to read), or `[0.15]` OriginalProductName for a product id Earshot does not know.
         * The app's `originalName`s (docs/bose-models.md §D5, §D13) are "Bose QC Ultra 2 HP",
         * "Bose QC 2 HP", "Bose QC Ultra 2 Earbuds", "Bose QC Ultra Headphones", "Bose QC Ultra
         * Earbuds", "Bose QC Earbuds II", "Bose QuietComfort Headphones", "Bose QC 45", "Bose NC 700
         * Headphones", "Bose QuietComfort Earbuds", "Bose Ultra Open Earbuds" and "Bose Ultra Open 2
         * Earbuds". Null when the name says nothing useful, e.g. the user renamed the headphones.
         * "Bose QuietComfort Earbuds" is lando's name; the 2024 earbuds (brussels) may carry it too,
         * but they never answer BMAP, so `[0.3]` cannot contradict the guess.
         */
        fun fromName(name: String?): BoseModel? {
            // "LE-" is how Android names the LE Audio identity of the same headphones.
            val n = name?.lowercase()?.trim()?.removePrefix("le-")?.replace("quietcomfort", "qc")
                ?.replace(Regex("\\s+"), " ") ?: return null
            val gen2 = GEN_2.containsMatchIn(n)
            return when {
                ("ultra open" in n || "open earbuds" in n) && "sport" !in n ->
                    if (gen2 || Regex("\\bopen (2|ii)\\b").containsMatchIn(n)) ULTRA_OPEN_EARBUDS_2 else ULTRA_OPEN_EARBUDS
                // Before the headphone branch: "QC Ultra Earbuds" also contains "qc ultra".
                "qc ultra" in n && "earbud" in n ->
                    if (gen2 || Regex("\\b(earbuds?|ultra) (2|ii)\\b").containsMatchIn(n)) QC_ULTRA_EARBUDS_2 else QC_ULTRA_EARBUDS
                "qc ultra" in n -> if (gen2 || Regex("\\bultra (2|ii)\\b").containsMatchIn(n)) QC_ULTRA_2 else QC_ULTRA_1
                Regex("qc ?45").containsMatchIn(n) || Regex("qc ?se\\b").containsMatchIn(n) -> QC45
                Regex("qc ?35 ?(ii|2)\\b").containsMatchIn(n) -> QC35_II
                Regex("qc ?35\\b").containsMatchIn(n) -> QC35
                Regex("qc ?sc\\b").containsMatchIn(n) -> QC_HEADPHONES
                // "Bose QC 2 HP" is mariah's [0.15]; "Bose QC Headphones 2" the setup copy.
                Regex("\\bqc (2|ii) hp\\b").containsMatchIn(n) -> QC_HEADPHONES_2
                "qc headphones" in n ->
                    if (gen2 || Regex("\\bheadphones (2|ii)\\b").containsMatchIn(n)) QC_HEADPHONES_2 else QC_HEADPHONES
                Regex("\\bnc ?700\\b").containsMatchIn(n) || "noise cancelling headphones 700" in n -> NC700
                "qc earbuds" in n -> if (gen2 || Regex("\\bearbuds (2|ii)\\b").containsMatchIn(n)) QC_EARBUDS_2 else QC_EARBUDS
                else -> null
            }
        }

        /**
         * A product line for a name no entry matches, so an unknown product is still named in the
         * header: the `[0.15]` name without the "Bose " prefix. Null for a blank name.
         */
        fun familyFromName(name: String?): String? =
            name?.trim()?.removePrefix("LE-")?.removePrefix("Bose ")?.trim()?.ifBlank { null }

        /** Form factor from a name, for a product the catalogue does not know; headphones unless it says otherwise. */
        fun formFromName(name: String?): ProductForm {
            val n = name?.lowercase().orEmpty()
            return when {
                "open" in n && "bud" in n -> ProductForm.OPEN_EARBUDS
                "bud" in n -> ProductForm.EARBUDS
                else -> ProductForm.HEADPHONES
            }
        }
    }
}

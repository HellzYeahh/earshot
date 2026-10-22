package dev.earshot.protocol.soundcore

/** Which header the `01 01` state starts with (research notes §3, "State headers"). */
enum class SoundcoreHeader {
    /** Sleep earbuds: host, linked, battery L/R at 2..3, firmware at 4 and 9, serial at 14. No charging bytes. */
    SLEEP,

    /** Gen-2 earbuds: battery L/R at 2..3, charging L/R at 4..5, firmware at 6 and 11, serial at 16. */
    G2T,

    /** Gen-2 headphones: one battery at 0, charging at 1, firmware at 2, serial at 7. */
    G2H,

    /** 2025-26 earbuds: `[tag, length, value]` entries. Read-only here, the write ids are unknown. */
    TLV,

    /** A model seen only by serial prefix: the reader tries every fixed header and keeps the plausible one. */
    UNKNOWN,
}

/** How a battery byte maps to a percentage (notes §3, "Battery scales"). 255 = part absent on every scale. */
enum class SoundcoreBattery {
    /** 0..5 in steps of 20 %: most 2021-23 models. */
    FIVE,

    /** 0..9 meaning (n+1) × 10 %: Space One Pro, Liberty 5, P31i, Sleep A20/A30, Space 2 and others. */
    NINE,

    /** 0..10 in steps of 10 %: the R50i NC. */
    TEN,

    /** 0..100 directly: Liberty 4 Pro and the TLV generation. */
    PERCENT,
    ;

    fun percent(raw: Int): Int? = when (this) {
        FIVE -> if (raw in 0..5) raw * 20 else null
        NINE -> if (raw in 0..9) (raw + 1) * 10 else null
        TEN -> if (raw in 0..10) raw * 10 else null
        PERCENT -> if (raw in 0..100) raw else null
    }
}

/**
 * A name pattern: "soundcore" (any case, the usual spelling in Bluetooth names) and then [model].
 * Top-level on purpose: the enum entries call it while the enum class initialises, before its
 * companion object exists.
 */
private fun named(model: String): Regex = Regex("""soundcore[\s_-]*$model""", RegexOption.IGNORE_CASE)

/**
 * The Soundcore models on the vendor RFCOMM service (`0cf12d31-fac3-4553-bd80-d6832e7` + five hex
 * digits). Gen-1 models that only expose SPP (Life Q30/Q35, Liberty Air 2 Pro, Dot 2 NC and the
 * like) are not in the table: Earshot never opens SPP for them. Only [SLEEP_A30] is tested on a
 * real pair; everything else follows the research notes (OpenSCQ30, Gadgetbridge and
 * soundcorebridge, facts only) and is told apart in the app as unverified.
 *
 * Identification, strongest last-word first: the serial in the state (its first four characters
 * are the model digits, [serialPrefix]) confirms before any write; the vendor UUID suffix says
 * which model published the service; the Bluetooth name is a first guess for routing only.
 */
enum class SoundcoreModel(
    /** The product code the Soundcore app uses: `A3947`, `D1301`. */
    val code: String,
    val displayName: String,
    private val nameHint: Regex,
    val header: SoundcoreHeader,
    val battery: SoundcoreBattery,
    val earbuds: Boolean = true,
    /** Known mode block and settings; null = battery, firmware and identity only. */
    val layout: SoundcoreLayout? = null,
    /** UUID suffixes seen on captures that the derivation rule would not give. */
    private val extraSuffixes: List<String> = emptyList(),
) {
    SLEEP_A30("D1301", "Soundcore Sleep A30", Regex("""sleep\s*a30""", RegexOption.IGNORE_CASE), SoundcoreHeader.SLEEP, SoundcoreBattery.NINE),
    SLEEP_A20("A6611", "Soundcore Sleep A20", named("""sleep\s*a20"""), SoundcoreHeader.SLEEP, SoundcoreBattery.NINE),

    // Headphones.
    SPACE_ONE("A3035", "Soundcore Space One", named("""space\s*one(?![\s_-]*pro)"""), SoundcoreHeader.G2H, SoundcoreBattery.FIVE, earbuds = false, layout = SoundcoreLayouts.SPACE_ONE),
    SPACE_ONE_PRO("A3062", "Soundcore Space One Pro", named("""space\s*one[\s_-]*pro"""), SoundcoreHeader.G2H, SoundcoreBattery.NINE, earbuds = false, layout = SoundcoreLayouts.SPACE_ONE_PRO),
    SPACE_Q45("A3040", "Soundcore Space Q45", named("""space\s*q\s*45"""), SoundcoreHeader.G2H, SoundcoreBattery.FIVE, earbuds = false, layout = SoundcoreLayouts.SPACE_Q45),
    SPACE_2("D1402", "Soundcore Space 2", named("""space\s*2\b"""), SoundcoreHeader.G2H, SoundcoreBattery.NINE, earbuds = false, layout = SoundcoreLayouts.SPACE_2),

    // Gen-2 earbuds with a documented mode block.
    LIBERTY_4_NC("A3947", "Soundcore Liberty 4 NC", named("""liberty\s*4[\s_-]*nc"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE, layout = SoundcoreLayouts.LIBERTY_4_NC),
    LIBERTY_5("A3957", "Soundcore Liberty 5", named("""liberty\s*5(?![\s_-]*pro)"""), SoundcoreHeader.G2T, SoundcoreBattery.NINE, layout = SoundcoreLayouts.LIBERTY_5),
    P40I("A3955", "Soundcore P40i", named("""p40i"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE, layout = SoundcoreLayouts.P40I),
    R50I_NC("A3959", "Soundcore R50i NC", named("""(?:r50i[\s_-]*nc|p30i)"""), SoundcoreHeader.G2T, SoundcoreBattery.TEN, layout = SoundcoreLayouts.R50I_NC, extraSuffixes = listOf("b395b")),
    A30I("A3958", "Soundcore A30i", named("""a30i"""), SoundcoreHeader.G2T, SoundcoreBattery.NINE, layout = SoundcoreLayouts.A30I),
    P31I("D1202", "Soundcore P31i", named("""p31i"""), SoundcoreHeader.G2T, SoundcoreBattery.NINE, layout = SoundcoreLayouts.P31I),
    R60I_NC("D1202C", "Soundcore R60i NC", named("""r60i"""), SoundcoreHeader.G2T, SoundcoreBattery.NINE, layout = SoundcoreLayouts.P31I, extraSuffixes = listOf("1202c")),
    SPACE_A40("A3936", "Soundcore Space A40", named("""space\s*a40"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE, layout = SoundcoreLayouts.SPACE_A40),
    LIBERTY_3_PRO("A3952", "Soundcore Liberty 3 Pro", named("""liberty\s*3[\s_-]*pro"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE, layout = SoundcoreLayouts.LIBERTY_3_PRO),

    // Gen-2 earbuds whose mode shows but is not written (layout size unknown, or the sources disagree).
    LIBERTY_4("A3953", "Soundcore Liberty 4", named("""liberty\s*4(?![\s_-]*(?:nc|pro))"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE, layout = SoundcoreLayouts.LIBERTY_4),
    LIBERTY_4_PRO("A3954", "Soundcore Liberty 4 Pro", named("""liberty\s*4[\s_-]*pro"""), SoundcoreHeader.G2T, SoundcoreBattery.PERCENT, layout = SoundcoreLayouts.LIBERTY_4_PRO),

    // Gen-2 earbuds read for battery and firmware only.
    SPORT_X20("A3968", "Soundcore Sport X20", named("""sport\s*x20"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE),
    LIFE_NOTE_3("A3933", "Soundcore Life Note 3", named("""life\s*note\s*3(?![\s_-]*s\b)"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE),
    LIFE_NOTE_3S("A3945", "Soundcore Life Note 3S", named("""life\s*note\s*3[\s_-]*s\b"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE),
    LIFE_P3("A3939", "Soundcore Life P3", named("""life\s*p3"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE),
    LIFE_P2_MINI("A3944", "Soundcore Life P2 Mini", named("""life\s*p2"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE),
    A20I("A3948", "Soundcore A20i", named("""a20i"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE),
    P20I("A3949", "Soundcore P20i / R50i", named("""(?:p20i|p25i|r50i(?![\s_-]*nc))"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE),
    C30I("A3330", "Soundcore C30i", named("""c30i"""), SoundcoreHeader.G2T, SoundcoreBattery.FIVE),
    AEROCLIP("A3388", "Soundcore AeroClip", named("""aeroclip"""), SoundcoreHeader.G2T, SoundcoreBattery.NINE),
    V20I("A3876", "Soundcore V20i", named("""v20i"""), SoundcoreHeader.G2T, SoundcoreBattery.NINE),
    AEROFIT_2("A3874", "Soundcore AeroFit 2", named("""aerofit\s*2(?![\s_-]*pro)"""), SoundcoreHeader.G2T, SoundcoreBattery.NINE),
    C50I("D1101", "Soundcore C50i", named("""c50i"""), SoundcoreHeader.G2T, SoundcoreBattery.NINE),

    // The tag-length-value generation (2025-26).
    LIBERTY_5_PRO("D1203", "Soundcore Liberty 5 Pro", named("""liberty\s*5[\s_-]*pro(?![\s_-]*max)"""), SoundcoreHeader.TLV, SoundcoreBattery.PERCENT),
    LIBERTY_5_PRO_MAX("D1204", "Soundcore Liberty 5 Pro Max", named("""liberty\s*5[\s_-]*pro[\s_-]*max"""), SoundcoreHeader.TLV, SoundcoreBattery.PERCENT),
    LIBERTY_BUDS_2("D1206", "Soundcore Liberty Buds 2", named("""liberty\s*buds\s*2"""), SoundcoreHeader.TLV, SoundcoreBattery.PERCENT),

    // Seen only as a serial prefix in feature requests: layout not captured.
    P42I("D1205", "Soundcore P42i", named("""p42i"""), SoundcoreHeader.UNKNOWN, SoundcoreBattery.NINE),
    AEROFIT_2_PRO("A3875", "Soundcore AeroFit 2 Pro", named("""aerofit\s*2[\s_-]*pro"""), SoundcoreHeader.UNKNOWN, SoundcoreBattery.NINE),
    V40I("A3878", "Soundcore V40i", named("""v40i"""), SoundcoreHeader.UNKNOWN, SoundcoreBattery.NINE),
    ;

    /**
     * The five hex digits that end this model's vendor service UUID: the code read as hex with
     * bit 16 set (`A3947` → `b3947`, `D1301` → `d1301`; Gadgetbridge's rule, confirmed by captures),
     * unless a capture shows otherwise ([extraSuffixes]).
     */
    val uuidSuffix: String get() = derivedSuffix(code)

    /** Every UUID suffix this model is known to publish. */
    val uuidSuffixes: List<String> get() = listOf(uuidSuffix) + extraSuffixes

    /** Same text as before the table grew: lower-case suffix of the vendor service. */
    val modelId: String get() = uuidSuffix

    /** The first four characters of the serial in the state: the model digits. */
    val serialPrefix: String get() = code.drop(1).take(4)

    /** Earshot may change this model's sound modes and settings (once the state confirms the layout). */
    val canWrite: Boolean get() = layout?.writable == true

    companion object {
        /** The vendor service: `0cf12d31-fac3-4553-bd80-d6832e7` + five hex digits (`…d6832e7d1301`). */
        const val SERVICE_UUID_PREFIX = "0cf12d31-fac3-4553-bd80-d6832e7"

        /**
         * JBL publishes the same `0cf12d31` base (`…b3850`) for its own protocol. Whatever UUID it
         * shows, a name like this is never opened as a Soundcore.
         */
        private val OTHER_BRAND = Regex("""\bjbl\b|harman""", RegexOption.IGNORE_CASE)

        private fun derivedSuffix(code: String): String = "%05x".format(code.take(5).toInt(16) or 0x10000)

        fun fromName(name: String?): SoundcoreModel? =
            name?.takeUnless { OTHER_BRAND.containsMatchIn(it) }?.let { n -> entries.firstOrNull { it.nameHint.containsMatchIn(n) } }

        /** The model a vendor service UUID belongs to, or null for another Soundcore model (not driven) or another brand. */
        fun fromServiceUuid(uuid: String): SoundcoreModel? {
            val u = uuid.lowercase()
            if (!u.startsWith(SERVICE_UUID_PREFIX) || u.length != SERVICE_UUID_PREFIX.length + 5) return null
            val suffix = u.substring(SERVICE_UUID_PREFIX.length)
            return entries.firstOrNull { suffix in it.uuidSuffixes }
        }

        /**
         * The first cached service UUID that belongs to a driven model, unless the Bluetooth [name]
         * is another brand's. Returns the model and the UUID to open.
         */
        fun identify(uuids: List<String>, name: String?): Pair<SoundcoreModel, String>? {
            if (name != null && OTHER_BRAND.containsMatchIn(name)) return null
            for (uuid in uuids) {
                val model = fromServiceUuid(uuid) ?: continue
                return model to uuid.lowercase()
            }
            return null
        }

        /** The model whose digits start [serial] (`3957F49D8AC20334` → Liberty 5), or null. */
        fun fromSerial(serial: String?): SoundcoreModel? {
            val prefix = serial?.take(4) ?: return null
            return entries.firstOrNull { it.serialPrefix == prefix }
        }
    }
}

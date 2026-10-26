package dev.earshot.protocol.jbl

/**
 * The JBL models that speak H-AA, the protocol Earshot drives: the 2021–24 BES models JBL's app
 * runs through its `u3.bes` and `u3.protocol3` classes (JBL notes §1, `product_list.json` of JBL
 * Headphones 5.37.10). Global and Chinese product ids, as the app lists them.
 *
 * Not here, so they stay generic: the 2025+ models on JBL's newer Protocol 4 ([NEWER_PROTOCOL_PIDS])
 * and the Airoha models on RACE ([AIROHA_PIDS]), which need other drivers; the pre-2020 Avnera line.
 * Names collide across stacks ("Tune 770NC" is Airoha, "Live 770NC" is BES), so a name must match a
 * model exactly ([fromName]), never as a substring, and the product id the headset reports wins.
 */
enum class JblModel(val displayName: String, val pids: Set<Int>, val earbuds: Boolean?) {
    LIVE_460NC("Live 460NC", setOf(0x2031, 0x2032), false),
    LIVE_660NC("Live 660NC", setOf(0x2033, 0x2034), false),
    LIVE_PRO_PLUS_TWS("Live Pro+ TWS", setOf(0x2043, 0x203B, 0x2060), true),
    TOUR_PRO_PLUS_TWS("Tour Pro+ TWS", setOf(0x2020, 0x202C), true),
    TOUR_ONE("Tour One", setOf(0x2021, 0x2028), false),
    UA_PROJECT_ROCK("UA Project Rock", setOf(0x1F60, 0x2044), true),
    REFLECT_FLOW_PRO("Reflect Flow Pro", setOf(0x202D, 0x202E), true),
    TUNE_130NC_TWS("Tune 130NC TWS", setOf(0x2049, 0x204A), true),
    TUNE_230NC_TWS("Tune 230NC TWS", setOf(0x204B, 0x204C), true),
    TUNE_235NC_TWS("Tune 235NC TWS", setOf(0x20E5), true),
    LIVE_PRO_2_TWS("Live Pro 2 TWS", setOf(0x2058, 0x2059), true),
    TUNE_FLEX("Tune Flex", setOf(0x207F, 0x2080), true),
    TOUR_ONE_M2("Tour One M2", setOf(0x2085, 0x2086), false),
    LIVE_FLEX("Live Flex", setOf(0x2099, 0x209A), true),
    TOUR_PRO_2("Tour Pro 2", setOf(0x2093, 0x2094, 0x20E8), true),
    TUNE_BEAM("Tune Beam", setOf(0x20BB, 0x20BC), true),
    TUNE_BUDS("Tune Buds", setOf(0x20B9, 0x20BA), true),
    TUNE_245NC("Tune 245NC", setOf(0x211E), null),
    LIVE_670NC("Live 670NC", setOf(0x20CC), false),
    LIVE_675NC("Live 675NC", setOf(0x20CD), false),
    LIVE_770NC("Live 770NC", setOf(0x20CE, 0x20CF), false),
    LIVE_775NC("Live 775NC", setOf(0x2124), false),
    LIVE_BEAM_3("Live Beam 3", setOf(0x20EF), true),
    LIVE_FLEX_3("Live Flex 3", setOf(0x20F0), true),
    WAVE_BUDS_2("Wave Buds 2", setOf(0x2100), true),
    WAVE_FLEX_2("Wave Flex 2", setOf(0x20FF), true),
    VIBE_BUDS_2("Vibe Buds 2", setOf(0x2104), true),
    VIBE_FLEX_2("Vibe Flex 2", setOf(0x2103), true),
    TUNE_BUDS_2("Tune Buds 2", setOf(0x210C), true),
    TUNE_FLEX_2("Tune Flex 2", setOf(0x210E), true),
    TUNE_BEAM_2("Tune Beam 2", setOf(0x210D), true),
    TUNE_255NC("Tune 255NC", setOf(0x2164), null),
    JUNIOR_470NC("Junior 470NC", setOf(0x2105), false),
    ;

    /** "JBL Wave Buds 2". */
    val fullName: String get() = "JBL $displayName"

    private val key: String = normaliseJblName(displayName)

    companion object {
        /** JBL's Protocol 4 models (2025+, BES and Realtek): framed `00 DD`, not H-AA. */
        val NEWER_PROTOCOL_PIDS: Set<Int> = setOf(
            0x2101, 0x2160, 0x2161, 0x215E, 0x215F, 0x21A4, 0x2153, 0x2133, 0x2134, 0x2150, 0x214F, 0x2151, 0x2152,
            0x2179, 0x217B, 0x217D, 0x21B7, 0x21B8, 0x21B9, 0x21B6, 0x21AB, 0x21AC, 0x21AD, 0x21B2, 0x21B3, 0x21B4,
            0x21AE, 0x2193,
        )

        /** JBL's Airoha models: RACE with Harman ids, not H-AA. */
        val AIROHA_PIDS: Set<Int> = setOf(
            0x1F65, 0x202B, 0x201C, 0x2027, 0x201F, 0x2024, 0x2067, 0x2068, 0x207B, 0x207D, 0x207E, 0x20BD,
            0x20B5, 0x20B6, 0x20B7, 0x20B8, 0x2123, 0x215D, 0x20F1, 0x20F2, 0x2110, 0x20FE, 0x2102,
        )

        fun fromPid(pid: Int?): JblModel? = pid?.let { p -> entries.firstOrNull { p in it.pids } }

        /**
         * The model a Bluetooth name names exactly: "JBL Wave Buds 2", "JBL LIVE660NC", "LE-JBL Tour
         * One M2" (case, spaces and punctuation ignored, an `LE` twin prefix or suffix dropped, "TWS"
         * optional). Null for any other name, including other JBL models.
         */
        fun fromName(name: String?): JblModel? {
            val n = normaliseJblName(name ?: return null)
            if (!n.startsWith("jbl")) return null
            val model = n.removePrefix("jbl")
            return entries.firstOrNull { it.key == model || it.key.removeSuffix("tws") == model.removeSuffix("tws") }
        }

        /** Why Earshot doesn't drive this product id, or null when it does (or the id is unknown). */
        fun refusal(pid: Int?): String? = when (pid) {
            in NEWER_PROTOCOL_PIDS -> "This JBL model uses JBL's newer protocol, which Earshot doesn't speak yet"
            in AIROHA_PIDS -> "This JBL model uses Airoha's protocol, which Earshot's JBL driver doesn't speak yet"
            else -> null
        }
    }
}

/** Top level, not in the companion: the entries call it while the enum class initialises. */
private fun normaliseJblName(name: String): String = name.trim()
    .replace(Regex("""^le[-_ ]""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""[-_ ]le$""", RegexOption.IGNORE_CASE), "")
    .lowercase()
    .filter { it.isLetterOrDigit() }

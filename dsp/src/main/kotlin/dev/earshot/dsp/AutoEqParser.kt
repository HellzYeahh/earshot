package dev.earshot.dsp

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads and writes AutoEq's `ParametricEQ.txt` format (the same text Equalizer APO, Wavelet,
 * Poweramp and most PC tools accept):
 *
 * ```
 * Preamp: -6.4 dB
 * Filter 1: ON PK Fc 105 Hz Gain -3.2 dB Q 0.70
 * Filter 2: ON LSC Fc 105 Hz Gain -3.0 dB Q 0.70
 * Filter 3: ON HSC Fc 10000 Hz Gain 2.7 dB Q 0.70
 * ```
 *
 * `PK` = peaking, `LSC` = low shelf, `HSC` = high shelf. AutoEq's shelves are the RBJ Q-form
 * shelves, which is what [Biquad] implements, so a parsed preset draws the same curve AutoEq shows.
 * Lines we don't understand are ignored rather than failing the import: real files sometimes
 * carry comments or trailing blank lines, and some tools write `LS`/`HS` or `OFF` filters.
 */
object AutoEqParser {
    class ParseException(message: String) : IllegalArgumentException(message)

    private val preampRegex = Regex("""^\s*Preamp:\s*([-+]?\d+(?:\.\d+)?)\s*dB\s*$""", RegexOption.IGNORE_CASE)
    private val filterRegex = Regex(
        """^\s*Filter\s*\d+\s*:\s*(ON|OFF)\s+(PK|LSC|HSC|LS|HS|PEQ)\s+Fc\s+([-+]?\d+(?:\.\d+)?)\s*Hz\s+Gain\s+([-+]?\d+(?:\.\d+)?)\s*dB(?:\s+Q\s+([-+]?\d+(?:\.\d+)?))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parse [text] into a preset. Throws [ParseException] if there is not a single usable filter
     * line, so the UI can tell the user the file is not an AutoEq export.
     *
     * Values outside the model's limits are clamped rather than rejected: a correction curve
     * with one −16 dB band is still far more useful at −15 dB than not at all.
     */
    fun parse(text: String, id: String, name: String, description: String? = null, isBuiltIn: Boolean = false): EqPreset {
        var preamp = 0.0
        val bands = ArrayList<ParametricBand>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            preampRegex.matchEntire(line)?.let {
                preamp = it.groupValues[1].toDouble()
                return@let
            }
            val m = filterRegex.matchEntire(line) ?: continue
            val enabled = m.groupValues[1].equals("ON", ignoreCase = true)
            val type = when (m.groupValues[2].uppercase(Locale.ROOT)) {
                "LSC", "LS" -> FilterType.LOW_SHELF
                "HSC", "HS" -> FilterType.HIGH_SHELF
                else -> FilterType.PEAK
            }
            val fc = m.groupValues[3].toDouble()
            val gain = m.groupValues[4].toDouble()
            // A shelf line without Q (older exports) means the default maximally-flat shelf.
            val q = m.groupValues[5].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.707
            if (fc <= 0.0) continue
            bands += ParametricBand(
                frequencyHz = fc,
                gainDb = gain.coerceIn(ParametricBand.MIN_GAIN_DB, ParametricBand.MAX_GAIN_DB),
                q = q.coerceIn(ParametricBand.MIN_Q, ParametricBand.MAX_Q),
                type = type,
                enabled = enabled,
            )
        }
        if (bands.isEmpty()) throw ParseException("No 'Filter N: ON PK Fc … Hz Gain … dB Q …' lines found")
        return EqPreset(
            id = id,
            name = name,
            bands = bands,
            preampDb = preamp.coerceIn(-30.0, 0.0),
            isBuiltIn = isBuiltIn,
            description = description,
        )
    }

    /** Export in AutoEq's format; [parse] of the result gives back the same bands. */
    fun export(preset: EqPreset): String {
        val sb = StringBuilder()
        sb.append("Preamp: ").append(fmt1(preset.preampDb)).append(" dB\n")
        preset.bands.forEachIndexed { index, band ->
            val type = when (band.type) {
                FilterType.PEAK -> "PK"
                FilterType.LOW_SHELF -> "LSC"
                FilterType.HIGH_SHELF -> "HSC"
            }
            sb.append("Filter ").append(index + 1).append(": ")
                .append(if (band.enabled) "ON" else "OFF").append(' ')
                .append(type)
                .append(" Fc ").append(fmtFreq(band.frequencyHz)).append(" Hz")
                .append(" Gain ").append(fmt1(band.gainDb)).append(" dB")
                .append(" Q ").append(fmt2(band.q))
                .append('\n')
        }
        return sb.toString()
    }

    private fun fmt1(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun fmt2(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    /** AutoEq writes whole-number frequencies; keep one decimal only when the value needs it. */
    private fun fmtFreq(v: Double): String {
        val r = v.roundToInt()
        return if (abs(v - r) < 0.05) r.toString() else fmt1(v)
    }
}

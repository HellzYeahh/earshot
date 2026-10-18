package dev.earshot.ui.sound

/** The headphone EQ sliders' range, from `[1.7]`'s reported min..max. Pure, for `HeadphoneEqRangeTest`. */
object HeadphoneEqRange {
    /** What every BMAP model reports today (bosectl-verified on five models). */
    val DEFAULT: IntRange = -10..10

    /**
     * [reported] clipped to -10..10: `HeadphoneEq` (a core contract) only holds that range, so a
     * wider report can't be represented, and an empty or inverted one falls back to the default.
     */
    fun of(reported: IntRange?): IntRange {
        if (reported == null || reported.isEmpty()) return DEFAULT
        val lo = maxOf(reported.first, DEFAULT.first)
        val hi = minOf(reported.last, DEFAULT.last)
        return if (lo < hi) lo..hi else DEFAULT
    }

    /** Slider `steps` for one stop per whole dB: the positions between the two ends. */
    fun steps(range: IntRange): Int = (range.last - range.first - 1).coerceAtLeast(0)
}

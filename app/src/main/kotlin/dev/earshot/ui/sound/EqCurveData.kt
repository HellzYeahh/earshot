package dev.earshot.ui.sound

import androidx.compose.runtime.Immutable
import dev.earshot.dsp.EqResponse
import dev.earshot.dsp.Headroom
import dev.earshot.dsp.ParametricBand
import kotlin.math.ceil
import kotlin.math.max

/**
 * Everything the Pro EQ shows about one set of [bands] and [preampDb], worked out in one pass:
 * the drawn curve, the dots on it, and the band peak behind the "recommended preamp" line and the
 * boost card's overshoot note.
 *
 * Why one object: the curve (128 points), [Headroom.recommendedPreampDb] (a 512-point peak search)
 * and [Headroom.worstCaseOvershootDb] (the same 512-point search again) used to run separately in
 * composition, on the main thread, on every frame of a slider drag, and the canvas built one more
 * [EqResponse] per band dot on every draw. [SoundViewModel] now builds this once per change, off
 * the main thread. The numbers come from the same [EqResponse] the engine and [Headroom] use;
 * `EqCurveDataTest` holds them equal to [Headroom]'s.
 *
 * The work happens in the constructor. Equality covers only the inputs, so the same bands and
 * preamp computed twice compare equal: the StateFlow drops the repeat and Compose skips.
 */
@Immutable
data class EqCurveData(val bands: List<ParametricBand>, val preampDb: Double) {
    /** [CURVE_POINTS] log-spaced (hz, dB) points over 20 Hz..20 kHz, preamp included. */
    val samples: List<Pair<Double, Double>>

    /** (hz, dB) of each enabled band's centre on the curve, preamp included: the dots. */
    val markers: List<Pair<Double, Double>>

    /** Highest point of the bands alone (no preamp) over the audible range. */
    val bandPeakDb: Double

    init {
        val response = EqResponse(bands, preampDb)
        samples = response.sample(CURVE_POINTS, EqCurve.MIN_HZ, EqCurve.MAX_HZ).map { it.frequencyHz to it.gainDb }
        markers = bands.filter { it.enabled }.map { it.frequencyHz to response.magnitudeDb(it.frequencyHz) }
        // The bands-only peak ignores the preamp, so this response gives Headroom's number as it is.
        bandPeakDb = response.peakBandsOnlyDb()
    }

    /** [Headroom.recommendedPreampDb] for [bands], from the peak already found. */
    val recommendedPreampDb: Double = if (bandPeakDb <= 0.0) 0.0 else -(ceil(bandPeakDb * 10.0) / 10.0)

    /** [Headroom.worstCaseOvershootDb] for [bands] at this preamp and boost. */
    fun overshootDb(preampDb: Double, boostDb: Double): Double = max(0.0, bandPeakDb + preampDb + boostDb)

    companion object {
        /** One point per few pixels across the card is smooth enough and cheap to draw. */
        const val CURVE_POINTS = 128
    }
}

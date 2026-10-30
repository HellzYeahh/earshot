package dev.earshot.ui.sound

import dev.earshot.dsp.EqResponse
import dev.earshot.dsp.ParametricBand
import kotlin.math.ln

/**
 * Frequency-response numbers for the EQ curve on the Sound tab. Thin wrapper over the DSP
 * module's exact [EqResponse] (the same biquads the engine runs), kept so the UI has one place
 * for its axis conventions (20 Hz..20 kHz, log x). The drawn curve itself is [EqCurveData].
 */
object EqCurve {
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20_000.0

    /** Total gain in dB at [frequencyHz] for the enabled [bands] plus [preampDb]. */
    fun magnitudeDb(bands: List<ParametricBand>, preampDb: Double, frequencyHz: Double): Double =
        EqResponse(bands, preampDb).magnitudeDb(frequencyHz)

    /** 0..1 horizontal position of [hz] on the log axis. */
    fun xFraction(hz: Double): Float = ((ln(hz) - ln(MIN_HZ)) / (ln(MAX_HZ) - ln(MIN_HZ))).toFloat().coerceIn(0f, 1f)
}

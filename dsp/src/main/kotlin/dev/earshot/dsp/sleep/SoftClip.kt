package dev.earshot.dsp.sleep

import kotlin.math.abs

/**
 * A soft saturator: straight up to a knee, then a rational curve that bends towards a ceiling it
 * never reaches. The curve's slope is 1 at the knee, so a signal that brushes it is not audibly
 * distorted, and unlike a hard clip it adds no harsh odd harmonics when something does overshoot.
 *
 * `u / (1 + u)` instead of `tanh`: no exponential per sample, and it is exactly linear below the
 * knee, so normal levels pass bit-identical.
 */
internal object SoftClip {
    /** For a single sound: untouched below 0.85, never beyond 0.99. */
    fun source(x: Double): Double = apply(x, SOURCE_KNEE, SOURCE_CEILING)

    fun apply(x: Double, knee: Double, ceiling: Double): Double {
        val a = abs(x)
        if (a <= knee) return x
        val range = ceiling - knee
        val u = (a - knee) / range
        val y = knee + range * u / (1.0 + u)
        return if (x < 0.0) -y else y
    }

    private const val SOURCE_KNEE = 0.85
    private const val SOURCE_CEILING = 0.99
}

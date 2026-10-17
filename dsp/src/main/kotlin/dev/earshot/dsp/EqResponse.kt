package dev.earshot.dsp

import kotlin.math.ln
import kotlin.math.exp

/** One point of a magnitude curve, for drawing. */
data class ResponsePoint(val frequencyHz: Double, val gainDb: Double)

/**
 * Combined magnitude response of a set of [ParametricBand]s plus a flat preamp.
 *
 * Biquads multiply, so in dB the sections simply add; the preamp is a constant offset. The
 * biquads are configured once in the constructor and reused for every query, which keeps a
 * 256-point UI sweep at a few microseconds and free of garbage.
 *
 * Disabled bands are skipped entirely (they contribute 0 dB).
 */
class EqResponse(
    bands: List<ParametricBand>,
    val preampDb: Double = 0.0,
    val sampleRate: Double = Biquad.DEFAULT_SAMPLE_RATE,
) {
    private val sections: Array<Biquad> = bands
        .filter { it.enabled }
        .map { Biquad().configure(it, sampleRate) }
        .toTypedArray()

    val bandCount: Int get() = sections.size

    /** Response in dB at [freqHz], preamp included. */
    fun magnitudeDb(freqHz: Double): Double {
        var sum = preampDb
        for (s in sections) sum += s.magnitudeDb(freqHz)
        return sum
    }

    /** Response in dB at [freqHz] *without* the preamp, i.e. only what the bands do. */
    fun bandsOnlyDb(freqHz: Double): Double {
        var sum = 0.0
        for (s in sections) sum += s.magnitudeDb(freqHz)
        return sum
    }

    /**
     * Sample the curve at [nPoints] log-spaced frequencies from [fMin] to [fMax] inclusive.
     * Log spacing matches how the UI draws the frequency axis, so every pixel column gets a
     * point and the bass region is not starved.
     */
    fun sample(nPoints: Int = 256, fMin: Double = 20.0, fMax: Double = 20000.0): List<ResponsePoint> {
        val freqs = logSpaced(nPoints, fMin, fMax)
        return freqs.map { f -> ResponsePoint(f, magnitudeDb(f)) }
    }

    /** Largest value of the bands-only response over the audible range (preamp excluded). */
    fun peakBandsOnlyDb(nPoints: Int = 512, fMin: Double = 20.0, fMax: Double = 20000.0): Double {
        var peak = Double.NEGATIVE_INFINITY
        val freqs = logSpaced(nPoints, fMin, fMax)
        for (f in freqs) {
            val g = bandsOnlyDb(f)
            if (g > peak) peak = g
        }
        return if (peak == Double.NEGATIVE_INFINITY) 0.0 else peak
    }

    companion object {
        /** [n] frequencies spaced evenly in log-frequency between [fMin] and [fMax] inclusive. */
        fun logSpaced(n: Int, fMin: Double, fMax: Double): DoubleArray {
            require(n >= 1) { "need at least one point" }
            require(fMin > 0.0 && fMax > fMin) { "need 0 < fMin < fMax" }
            if (n == 1) return doubleArrayOf(fMin)
            val lnMin = ln(fMin)
            val step = (ln(fMax) - lnMin) / (n - 1)
            return DoubleArray(n) { i -> exp(lnMin + step * i) }
        }
    }
}

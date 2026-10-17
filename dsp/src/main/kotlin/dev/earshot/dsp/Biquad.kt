package dev.earshot.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One second-order IIR section with coefficients from the RBJ Audio EQ Cookbook
 * (Robert Bristow-Johnson, "Cookbook formulae for audio EQ biquad filter coefficients").
 *
 * The transfer function is
 * `H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)` with `a0` already normalised away.
 *
 * We never run audio through this class ourselves (Android's DynamicsProcessing does the actual
 * filtering); it exists so the UI curve, the headroom maths and the band plan all agree on what
 * a [ParametricBand] *means*. The instance is mutable and [configure] is allocation-free so a
 * response sweep can reuse one object per band.
 */
class Biquad {
    var b0: Double = 1.0; private set
    var b1: Double = 0.0; private set
    var b2: Double = 0.0; private set
    var a1: Double = 0.0; private set
    var a2: Double = 0.0; private set

    private var sampleRate: Double = DEFAULT_SAMPLE_RATE

    /** Convenience for [configure] from the shared model. */
    fun configure(band: ParametricBand, sampleRate: Double = DEFAULT_SAMPLE_RATE): Biquad =
        configure(band.type, sampleRate, band.frequencyHz, band.gainDb, band.q)

    /**
     * Compute coefficients for [type] at centre/corner frequency [f0] with [gainDb] and [q].
     *
     * Shelves use the cookbook's Q form (not the slope `S` form) so that AutoEq's `LSC … Q 0.70`
     * lines and Equalizer APO behave identically here. With Q = 0.707 a shelf is maximally flat;
     * higher Q adds a resonant bump at the corner, exactly as in those tools.
     *
     * `f0` is clamped just below Nyquist: the bilinear transform blows up at fs/2 and a preset
     * written for 96 kHz may carry a 20 kHz band that the phone plays back at 44.1 kHz.
     */
    fun configure(type: FilterType, sampleRate: Double, f0: Double, gainDb: Double, q: Double): Biquad {
        this.sampleRate = sampleRate
        val fc = f0.coerceIn(1.0, sampleRate * 0.499)
        val qq = q.coerceAtLeast(1e-3)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * fc / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * qq)

        val nb0: Double
        val nb1: Double
        val nb2: Double
        val na0: Double
        val na1: Double
        val na2: Double
        when (type) {
            FilterType.PEAK -> {
                nb0 = 1.0 + alpha * a
                nb1 = -2.0 * cosW0
                nb2 = 1.0 - alpha * a
                na0 = 1.0 + alpha / a
                na1 = -2.0 * cosW0
                na2 = 1.0 - alpha / a
            }
            FilterType.LOW_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                nb0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
                nb1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                nb2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
                na0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
                na1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                na2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
            }
            FilterType.HIGH_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                nb0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
                nb1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                nb2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
                na0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
                na1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                na2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
            }
        }
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
        return this
    }

    /** Reset to a unity pass-through. */
    fun identity(): Biquad {
        b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
        return this
    }

    /**
     * Magnitude of `H(e^{jω})` in dB at [freqHz], using the sample rate given to [configure].
     * Evaluates the polynomial directly (no complex-number objects) so sweeps stay allocation-free.
     */
    fun magnitudeDb(freqHz: Double): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        val cos1 = cos(w)
        val sin1 = sin(w)
        val cos2 = cos(2.0 * w)
        val sin2 = sin(2.0 * w)
        val nr = b0 + b1 * cos1 + b2 * cos2
        val ni = -(b1 * sin1 + b2 * sin2)
        val dr = 1.0 + a1 * cos1 + a2 * cos2
        val di = -(a1 * sin1 + a2 * sin2)
        val num = nr * nr + ni * ni
        val den = dr * dr + di * di
        if (den <= 0.0 || num <= 0.0) return if (num <= 0.0) -200.0 else 200.0
        return 10.0 * log10(num / den)
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48000.0

        fun peaking(sampleRate: Double, f0: Double, gainDb: Double, q: Double): Biquad =
            Biquad().configure(FilterType.PEAK, sampleRate, f0, gainDb, q)

        fun lowShelf(sampleRate: Double, f0: Double, gainDb: Double, q: Double): Biquad =
            Biquad().configure(FilterType.LOW_SHELF, sampleRate, f0, gainDb, q)

        fun highShelf(sampleRate: Double, f0: Double, gainDb: Double, q: Double): Biquad =
            Biquad().configure(FilterType.HIGH_SHELF, sampleRate, f0, gainDb, q)
    }
}

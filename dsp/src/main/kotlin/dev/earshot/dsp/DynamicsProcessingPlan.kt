package dev.earshot.dsp

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Translates our parametric model into what Android's `android.media.audiofx.DynamicsProcessing`
 * can actually be told.
 *
 * DynamicsProcessing is *band*-based, not biquad-based: its post-EQ stage is a bank of N
 * contiguous bands, each defined only by an upper `cutoffFrequency` and a `gain`, with the
 * framework cross-fading neighbours. There is no Q, no filter type and no way to place a true
 * peaking filter. So we approximate:
 *
 *  1. Split 20 Hz–20 kHz into [bandCount] log-spaced bands (equal width in octaves; with the
 *     default 32 bands each is ~0.31 octave, i.e. slightly finer than a 1/3-octave graphic EQ).
 *     Band 0 implicitly also covers 0–20 Hz and the last band runs on to Nyquist, which is what
 *     the framework does with the first/last cutoffs anyway.
 *  2. Compute the exact combined biquad response ([EqResponse]) of the enabled bands, *without*
 *     the preamp, and sample it at each band's geometric centre. That sample becomes the band gain.
 *  3. Preamp and loudness boost are flat gains, so they go into the effect's input gain stage
 *     instead of being baked into every band ([Plan.inputGainDb]).
 *  4. The limiter stage runs after the EQ with fixed, fast "brickwall-ish" parameters
 *     ([LimiterParams]); the plan only toggles it.
 *
 * What this loses: features narrower than one band (a Q=5 notch at 6 kHz is ~0.3 octave wide,
 * the same as the band, so it survives but shallower); the ±0.3 dB ripple of the framework's
 * cross-fades; and anything the hardware DSP rounds. For headphone correction curves and
 * graphic-EQ-style tweaks the result is audibly the same as the parametric curve.
 */
object DynamicsProcessingPlan {
    const val DEFAULT_BAND_COUNT = 32
    const val DEFAULT_SAMPLE_RATE = 48000
    const val MIN_FREQ_HZ = 20.0
    const val MAX_FREQ_HZ = 20000.0

    /** One post-EQ band: everything below [cutoffHz] (down to the previous band) gets [gainDb]. */
    data class Band(val cutoffHz: Double, val gainDb: Double)

    /**
     * Limiter stage parameters. The defaults are a near-brick-wall peak limiter that reacts within
     * a millisecond, recovers in 60 ms (fast enough not to pump on music, slow enough not to
     * distort bass), squashes 50:1 above −2 dBFS (a 10:1 ratio let a +12 dB boost through at
     * +10 dB, which the fixed-point Bluetooth stream then hard-clipped; 50:1 with 1 dB extra
     * margin keeps the worst case under full scale), and adds no make-up gain (loudness comes from
     * the input stage, so the limiter is purely protective).
     */
    data class LimiterParams(
        val enabled: Boolean,
        val attackMs: Double = 1.0,
        val releaseMs: Double = 60.0,
        val ratio: Double = 50.0,
        val thresholdDb: Double = -2.0,
        val postGainDb: Double = 0.0,
    )

    data class Plan(
        val bands: List<Band>,
        /** Preamp + boost, applied before the EQ bands. */
        val inputGainDb: Double,
        val limiter: LimiterParams,
        val sampleRate: Int,
    ) {
        val bandCount: Int get() = bands.size
    }

    /**
     * Build a [Plan]. [boostDb] is used as given; callers clamp it with [Headroom.clampBoost]
     * first (the repository does), this layer stays pure.
     */
    fun plan(
        bands: List<ParametricBand>,
        preampDb: Double,
        boostDb: Double,
        limiterEnabled: Boolean,
        bandCount: Int = DEFAULT_BAND_COUNT,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
    ): Plan {
        require(bandCount >= 1) { "bandCount must be >= 1" }
        val response = EqResponse(bands, preampDb = 0.0, sampleRate = sampleRate.toDouble())
        val edges = bandEdges(bandCount)
        val planned = ArrayList<Band>(bandCount)
        for (i in 0 until bandCount) {
            val lower = edges[i]
            val upper = edges[i + 1]
            val centre = sqrt(lower * upper)
            planned += Band(cutoffHz = upper, gainDb = response.bandsOnlyDb(centre))
        }
        return Plan(
            bands = planned,
            inputGainDb = preampDb + boostDb,
            limiter = LimiterParams(enabled = limiterEnabled),
            sampleRate = sampleRate,
        )
    }

    /**
     * `bandCount + 1` log-spaced edges from [MIN_FREQ_HZ] to [MAX_FREQ_HZ]. Band `i` spans
     * `edges[i]..edges[i+1]`; its cutoff reported to Android is `edges[i+1]`.
     */
    fun bandEdges(bandCount: Int): DoubleArray {
        val ratio = MAX_FREQ_HZ / MIN_FREQ_HZ
        return DoubleArray(bandCount + 1) { i -> MIN_FREQ_HZ * ratio.pow(i.toDouble() / bandCount) }
    }

    /** Geometric centre of band [index] for a [bandCount]-band plan; handy for tests and the UI. */
    fun bandCentreHz(index: Int, bandCount: Int): Double {
        val edges = bandEdges(bandCount)
        return sqrt(edges[index] * edges[index + 1])
    }
}

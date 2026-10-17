package dev.earshot.dsp

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Gain-staging safety maths.
 *
 * A boosted EQ band raises peaks above 0 dBFS; the effect chain is floating point so nothing
 * clips *inside* DynamicsProcessing, but the output is converted back to the fixed-point stream
 * that goes to the Bluetooth encoder, and that *does* hard-clip. Two defences:
 *
 * 1. A negative preamp that cancels the largest positive combined gain ([recommendedPreampDb]).
 * 2. The limiter stage. When it is on we allow a larger loudness boost because the limiter
 *    catches the peaks; when the user turns it off we cap the boost at a small value so the
 *    worst case (preamp 0, full boost, loud master) is only mild clipping rather than a square wave.
 */
object Headroom {
    /** Most boost we hand out with the limiter switched off. */
    const val BOOST_CAP_WITHOUT_LIMITER_DB = 3.0

    /**
     * Preamp (≤ 0) that brings the highest point of the combined band response back to 0 dB.
     * Uses the *combined* response rather than the largest single band gain because overlapping
     * boosts add (two +6 dB bands an octave apart peak well above +6 dB between them).
     * Rounded up to 0.1 dB so the UI shows a tidy number and never under-compensates.
     */
    fun recommendedPreampDb(bands: List<ParametricBand>, sampleRate: Double = Biquad.DEFAULT_SAMPLE_RATE): Double {
        val peak = EqResponse(bands, preampDb = 0.0, sampleRate = sampleRate).peakBandsOnlyDb()
        if (peak <= 0.0) return 0.0
        return -(ceil(peak * 10.0) / 10.0)
    }

    /** Upper bound for [TuningSettings.boostDb] given the limiter state. */
    fun effectiveBoostCapDb(limiterEnabled: Boolean): Double =
        if (limiterEnabled) TuningSettings.MAX_BOOST_DB else BOOST_CAP_WITHOUT_LIMITER_DB

    /** [boostDb] clamped to `0..effectiveBoostCapDb(limiterEnabled)`. */
    fun clampBoost(boostDb: Double, limiterEnabled: Boolean): Double =
        boostDb.coerceIn(0.0, effectiveBoostCapDb(limiterEnabled))

    /**
     * The preamp the engine actually runs. With the limiter on, the user's value as given; with it
     * off there is nothing left to catch peaks, so the preamp can never sit above
     * [recommendedPreampDb] — bands alone must not push a full-scale input over 0 dBFS.
     */
    fun effectivePreampDb(
        preampDb: Double,
        bands: List<ParametricBand>,
        limiterEnabled: Boolean,
        sampleRate: Double = Biquad.DEFAULT_SAMPLE_RATE,
    ): Double = if (limiterEnabled) preampDb else min(preampDb, recommendedPreampDb(bands, sampleRate))

    /**
     * How far above 0 dBFS the chain can push a full-scale signal: the largest positive combined
     * band gain plus preamp plus boost. Positive means "relying on the limiter"; useful for a UI hint.
     */
    fun worstCaseOvershootDb(bands: List<ParametricBand>, preampDb: Double, boostDb: Double): Double {
        val peak = EqResponse(bands, preampDb = 0.0).peakBandsOnlyDb()
        return max(0.0, peak + preampDb + boostDb)
    }
}

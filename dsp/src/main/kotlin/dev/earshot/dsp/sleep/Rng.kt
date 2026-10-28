package dev.earshot.dsp.sleep

import kotlin.math.exp
import kotlin.math.ln

/**
 * Fast seeded random numbers for the synths: xoroshiro128+ (Blackman and Vigna, 2018 constants
 * 24/16/37) seeded through SplitMix64.
 *
 * A minute of audio needs millions of draws on a phone, and the tests compare runs sample for
 * sample, so this has to be cheap and deterministic per seed. `java.util.Random` is synchronised
 * and slower, and its 48-bit LCG is a poor noise source. The low bits of xoroshiro128+ are weak,
 * which does not matter here: every conversion below keeps only the top 53 bits.
 *
 * [stream] selects an independent sequence for the same [seed], so each part of a sound (noise,
 * drop timing, drifts) has its own and retuning one part never reshuffles the others.
 */
internal class Rng(seed: Long, stream: Long = 0L) {
    private var s0: Long
    private var s1: Long

    init {
        var x = seed + stream * STREAM_STEP
        x += GOLDEN
        s0 = splitMix(x)
        x += GOLDEN
        s1 = splitMix(x)
        if (s0 == 0L && s1 == 0L) s1 = GOLDEN
    }

    fun nextLong(): Long {
        val a = s0
        var b = s1
        val result = a + b
        b = b xor a
        s0 = java.lang.Long.rotateLeft(a, 24) xor b xor (b shl 16)
        s1 = java.lang.Long.rotateLeft(b, 37)
        return result
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11) * UNIT

    /** Uniform in [-1, 1): white noise with an RMS of 1/√3 and no peaks beyond 1. */
    fun bipolar(): Double = (nextLong() shr 11) * BIPOLAR_UNIT

    fun range(lo: Double, hi: Double): Double = lo + (hi - lo) * nextDouble()

    /** Log-uniform in [lo, hi): even odds per octave, the natural spread for pitches and loudness. */
    fun logRange(lo: Double, hi: Double): Double = lo * exp(ln(hi / lo) * nextDouble())

    fun chance(probability: Double): Boolean = nextDouble() < probability

    /** Uniform in 0 until [bound] (the modulo bias is far below anything audible). */
    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()

    /**
     * Frames until the next event of a Poisson process with [meanFrames] between events: the
     * exponential distribution, which is what makes drops and crackles sound random rather than
     * metronomic. At least one frame, capped so a near-zero rate cannot overflow.
     */
    fun exponentialFrames(meanFrames: Double): Int {
        val frames = -ln(1.0 - nextDouble()) * meanFrames
        return when {
            frames >= MAX_WAIT_FRAMES -> MAX_WAIT_FRAMES.toInt()
            frames < 1.0 -> 1
            else -> frames.toInt()
        }
    }

    private companion object {
        /** 0x9E3779B97F4A7C15, the 64-bit golden ratio increment of SplitMix64. */
        const val GOLDEN = -0x61c8864680b583ebL
        const val STREAM_STEP = 0x632be59bd9b4e019L
        const val UNIT = 1.0 / 9007199254740992.0 // 2^-53
        const val BIPOLAR_UNIT = 1.0 / 4503599627370496.0 // 2^-52
        const val MAX_WAIT_FRAMES = 1.0e9

        fun splitMix(input: Long): Long {
            var z = input
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }
    }
}

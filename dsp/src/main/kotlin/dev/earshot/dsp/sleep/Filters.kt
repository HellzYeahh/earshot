package dev.earshot.dsp.sleep

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.tan

/**
 * A two-pole state-variable filter in the "topology-preserving transform" form of Andrew Simper
 * (Cytomic, "Linear trapezoidal integrated SVF", 2013).
 *
 * Chosen over a direct-form biquad because the sounds sweep their filters all night (wind, waves):
 * this form stays stable and click-free while its cutoff moves, and retuning is one `tan`. The
 * band-pass output is scaled by `k = 1/Q`, so its peak gain is 1 whatever the Q.
 */
internal class Svf(private val sampleRate: Int) {
    private var a1 = 0.0
    private var a2 = 0.0
    private var a3 = 0.0
    private var k = 1.0
    private var mixInput = 0.0
    private var mixBand = 0.0
    private var mixLow = 1.0
    private var ic1 = 0.0
    private var ic2 = 0.0

    fun lowPass(cutoffHz: Double, q: Double = BUTTERWORTH_Q): Svf {
        tune(cutoffHz, q)
        mixInput = 0.0; mixBand = 0.0; mixLow = 1.0
        return this
    }

    fun bandPass(centreHz: Double, q: Double): Svf {
        tune(centreHz, q)
        mixInput = 0.0; mixBand = k; mixLow = 0.0
        return this
    }

    fun highPass(cutoffHz: Double, q: Double = BUTTERWORTH_Q): Svf {
        tune(cutoffHz, q)
        mixInput = 1.0; mixBand = -k; mixLow = -1.0
        return this
    }

    fun process(x: Double): Double {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2.0 * v1 - ic1
        ic2 = 2.0 * v2 - ic2
        return mixInput * x + mixBand * v1 + mixLow * v2
    }

    fun reset() {
        ic1 = 0.0
        ic2 = 0.0
    }

    private fun tune(frequencyHz: Double, q: Double) {
        val fc = frequencyHz.coerceIn(MIN_HZ, sampleRate * MAX_FRACTION)
        val g = tan(PI * fc / sampleRate)
        k = 1.0 / q.coerceAtLeast(MIN_Q)
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    companion object {
        const val BUTTERWORTH_Q = 0.7071
        private const val MIN_HZ = 5.0
        private const val MAX_FRACTION = 0.45
        private const val MIN_Q = 0.1
    }
}

/** A one-pole low-pass (and its complementary high-pass): 6 dB/octave, one multiply per sample. */
internal class OnePole(sampleRate: Int, cutoffHz: Double) {
    private val a = 1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)
    private var y = 0.0

    fun lowPass(x: Double): Double {
        y += a * (x - y)
        return y
    }

    /** `x − lowPass(x)`: a true first-order high-pass with its zero at DC. */
    fun highPass(x: Double): Double = x - lowPass(x)
}

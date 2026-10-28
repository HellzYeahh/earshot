package dev.earshot.dsp.sleep

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A pool of short, individually filtered noise bursts: raindrops on leaves, fire crackles, pops,
 * steam hisses. Each burst is white noise under a linear attack and an exponential decay, through
 * its own band-pass, panned with equal power.
 *
 * The excitation is scaled by `√(fs / (π·B))` (B = centre / Q, the band-pass's bandwidth), which
 * undoes the band-pass's loss on noise: a narrow, ringing pop and a broad crackle given the same
 * [trigger] amplitude come out at a comparable level, so the recipes can be tuned in plain levels.
 *
 * The pool is fixed, so a trigger never allocates. When every voice is busy the quietest decaying
 * one is taken over; it is nearly finished by then.
 */
internal class NoiseBursts(private val size: Int, private val sampleRate: Int, private val rng: Rng) {
    private val active = BooleanArray(size)
    private val rising = BooleanArray(size)
    private val level = DoubleArray(size)
    private val peak = DoubleArray(size)
    private val attackStep = DoubleArray(size)
    private val decay = DoubleArray(size)
    private val ring = IntArray(size)
    private val gainL = DoubleArray(size)
    private val gainR = DoubleArray(size)
    private val filters = Array(size) { Svf(sampleRate) }

    var left = 0.0
        private set
    var right = 0.0
        private set

    /**
     * Starts a burst. [attackSeconds] 0 means a one-sample attack (a click); [decaySeconds] is the
     * envelope's time constant; [pan] is −1..1.
     */
    fun trigger(amplitude: Double, attackSeconds: Double, decaySeconds: Double, centreHz: Double, q: Double, pan: Double) {
        val v = pickVoice()
        val filter = filters[v]
        filter.reset()
        filter.bandPass(centreHz, q)
        val bandwidth = max(1.0, centreHz / q)
        val excitation = amplitude * min(MAX_NORMALISE, sqrt(sampleRate / (PI * bandwidth)))
        val attackFrames = max(1.0, attackSeconds * sampleRate)
        peak[v] = excitation
        attackStep[v] = excitation / attackFrames
        level[v] = 0.0
        rising[v] = true
        decay[v] = exp(-1.0 / max(1.0, decaySeconds * sampleRate))
        // A band-pass of quality q rings with time constant q / (π·fc): keep it running for seven
        // of those (−60 dB) after the excitation ends, so the tail is never cut off.
        ring[v] = (RING_TIME_CONSTANTS * q / (PI * centreHz) * sampleRate).toInt() + 1
        gainL[v] = Pan.left(pan)
        gainR[v] = Pan.right(pan)
        active[v] = true
    }

    /** Advances every voice one frame; the mix is then in [left] and [right]. */
    fun tick() {
        var l = 0.0
        var r = 0.0
        for (v in 0 until size) {
            if (!active[v]) continue
            var e = level[v]
            if (rising[v]) {
                e += attackStep[v]
                if (e >= peak[v]) {
                    e = peak[v]
                    rising[v] = false
                }
            } else if (e > 0.0) {
                e *= decay[v]
                if (e < peak[v] * END_LEVEL) e = 0.0
            } else if (--ring[v] <= 0) {
                active[v] = false
                continue
            }
            level[v] = e
            val y = filters[v].process(if (e > 0.0) rng.bipolar() * e else 0.0)
            l += y * gainL[v]
            r += y * gainR[v]
        }
        left = l
        right = r
    }

    private fun pickVoice(): Int {
        var best = 0
        var bestScore = Double.MAX_VALUE
        for (v in 0 until size) {
            if (!active[v]) return v
            val score = if (rising[v]) peak[v] else level[v]
            if (score < bestScore) {
                bestScore = score
                best = v
            }
        }
        return best
    }

    private companion object {
        const val END_LEVEL = 1e-3
        const val RING_TIME_CONSTANTS = 7.0
        const val MAX_NORMALISE = 30.0
    }
}

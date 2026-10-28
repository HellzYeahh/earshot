package dev.earshot.dsp.sleep

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A pool of bubble "plinks": a sine whose pitch rises while it decays.
 *
 * After Kees van den Doel, "Physically-based models for liquid sounds" (ICAD 2004): a bubble with
 * resonant frequency f0 rings with damping `d = 0.043·f0 + 0.0014·f0^1.5` per second, and its
 * pitch climbs as `f(t) = f0·(1 + ξ·d·t)` as it nears the surface (ξ ≈ 0.1). Small bubbles are
 * high and short (a raindrop on a puddle), big ones low and long (the "bloop" of a brook).
 * The rising pitch is what makes it read as water rather than as a synthesiser blip.
 */
internal class Bubbles(private val size: Int, private val sampleRate: Int) {
    private val active = BooleanArray(size)
    private val rising = BooleanArray(size)
    private val phase = DoubleArray(size)
    private val increment = DoubleArray(size)
    private val incrementStep = DoubleArray(size)
    private val level = DoubleArray(size)
    private val peak = DoubleArray(size)
    private val attackStep = DoubleArray(size)
    private val decay = DoubleArray(size)
    private val gainL = DoubleArray(size)
    private val gainR = DoubleArray(size)

    var left = 0.0
        private set
    var right = 0.0
        private set

    /** Starts a bubble at [frequencyHz] with peak [amplitude]; [rise] is ξ (0 = steady pitch). */
    fun trigger(frequencyHz: Double, amplitude: Double, pan: Double, rise: Double = 0.1) {
        val v = pickVoice()
        val f0 = frequencyHz.coerceIn(MIN_HZ, sampleRate * MAX_FRACTION)
        val damping = 0.043 * f0 + 0.0014 * f0 * sqrt(f0)
        val fs = sampleRate.toDouble()
        phase[v] = 0.0
        increment[v] = f0 / fs
        incrementStep[v] = f0 * rise * damping / (fs * fs)
        peak[v] = amplitude
        attackStep[v] = amplitude / max(1.0, ATTACK_SECONDS * fs)
        level[v] = 0.0
        rising[v] = true
        decay[v] = exp(-damping / fs)
        gainL[v] = Pan.left(pan)
        gainR[v] = Pan.right(pan)
        active[v] = true
    }

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
            } else {
                e *= decay[v]
                if (e < peak[v] * END_LEVEL) {
                    active[v] = false
                    continue
                }
            }
            level[v] = e
            val y = SineTable.cycle(phase[v]) * e
            var p = phase[v] + increment[v]
            if (p >= 1.0) p -= 1.0
            phase[v] = p
            val next = increment[v] + incrementStep[v]
            if (next < MAX_INCREMENT) increment[v] = next
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
        const val MIN_HZ = 40.0
        const val MAX_FRACTION = 0.4
        const val MAX_INCREMENT = 0.45
        /** Long enough to round the onset, short enough to keep the "plip". */
        const val ATTACK_SECONDS = 0.0004
        const val END_LEVEL = 1e-3
    }
}

package dev.earshot.dsp.sleep

import kotlin.math.max

/**
 * A smooth random control signal: it wanders between random targets in [lo, hi], taking a random
 * time between [minSeconds] and [maxSeconds] for each move, along a smoothstep curve (no corners,
 * so a level or a cutoff driven by it never lurches).
 *
 * Everything natural in these sounds breathes this way: rain intensity, gusts, the flicker of a
 * fire, a brook's babble (with very short moves). Two ways to read it, never both on one instance:
 * [next] once per frame (the curve is evaluated every [CONTROL_FRAMES] and linearly interpolated,
 * one add per sample) or [nextControl] once per control period.
 */
internal class Drift(
    private val rng: Rng,
    private val lo: Double,
    private val hi: Double,
    minSeconds: Double,
    maxSeconds: Double,
    sampleRate: Int,
) {
    private val minFrames = max(CONTROL_FRAMES.toDouble(), minSeconds * sampleRate)
    private val maxFrames = max(minFrames, maxSeconds * sampleRate)
    private var from = rng.range(lo, hi)
    private var to = rng.range(lo, hi)
    private var length = newLength()
    private var position = 0
    private var step = 0.0
    private var countdown = 0

    var value: Double = from
        private set

    fun next(): Double {
        if (countdown == 0) {
            step = (advance() - value) / CONTROL_FRAMES
            countdown = CONTROL_FRAMES
        }
        countdown--
        value += step
        return value
    }

    fun nextControl(): Double {
        value = advance()
        return value
    }

    private fun advance(): Double {
        var p = position + CONTROL_FRAMES
        if (p >= length) {
            p -= length
            from = to
            to = rng.range(lo, hi)
            length = newLength()
        }
        position = p
        val t = p.toDouble() / length
        return from + (to - from) * t * t * (3.0 - 2.0 * t)
    }

    private fun newLength(): Int = rng.range(minFrames, maxFrames).toInt()
}

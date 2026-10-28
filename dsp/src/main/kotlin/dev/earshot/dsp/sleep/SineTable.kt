package dev.earshot.dsp.sleep

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 4096-point sine table with linear interpolation, for the oscillators (bubbles, birds, chimes,
 * fan hum). The worst-case error is about 3e-7 (−130 dB), far below anything a 16-bit or
 * Bluetooth path can carry, at a fraction of the cost of `sin` per sample.
 */
internal object SineTable {
    private const val SIZE = 4096
    private val values = DoubleArray(SIZE + 1) { sin(2.0 * PI * it / SIZE) }

    /** `sin(2π·phase)` for a phase in [0, 1) (one cycle per unit). */
    fun cycle(phase: Double): Double {
        val x = phase * SIZE
        val i = x.toInt()
        val frac = x - i
        val j = i and (SIZE - 1)
        val a = values[j]
        return a + (values[j + 1] - a) * frac
    }
}

/** Equal-power panning: −3 dB on each side in the centre, so a sound keeps its loudness as it moves. */
internal object Pan {
    /** Left gain for [pan] from −1 (hard left) to 1 (hard right). */
    fun left(pan: Double): Double = cos((pan.coerceIn(-1.0, 1.0) + 1.0) * PI / 4.0)

    /** Right gain for [pan] from −1 (hard left) to 1 (hard right). */
    fun right(pan: Double): Double = sin((pan.coerceIn(-1.0, 1.0) + 1.0) * PI / 4.0)
}

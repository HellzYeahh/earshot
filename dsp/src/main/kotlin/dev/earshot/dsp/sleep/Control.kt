package dev.earshot.dsp.sleep

/**
 * Frames between control updates (0.67 ms at 48 kHz).
 *
 * Slow things (filter tuning, drifts, wave envelopes) are recomputed this often and ramped per
 * sample in between. That keeps `tan`, `exp` and `pow` out of the per-sample path without letting
 * a modulation move in audible steps ("zipper" noise). The count runs across render calls, so a
 * sound never depends on the block size the app happens to pull.
 */
internal const val CONTROL_FRAMES = 32

/**
 * A value that moves in a straight line to a new target over one control period. [target] is
 * called once per control period and [next] once per frame, exactly [CONTROL_FRAMES] times in
 * between, which [StereoSynth] guarantees.
 */
internal class ControlRamp(initial: Double = 0.0) {
    var value: Double = initial
        private set
    private var step = 0.0

    fun target(next: Double) {
        step = (next - value) / CONTROL_FRAMES
    }

    fun next(): Double {
        value += step
        return value
    }
}

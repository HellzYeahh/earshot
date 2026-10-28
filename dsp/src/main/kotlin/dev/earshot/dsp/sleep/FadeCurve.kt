package dev.earshot.dsp.sleep

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Gain curves for the app's fades, as linear amplitudes for [SoundMixer.masterGain]. */
object FadeCurve {
    /**
     * 0 at the start, 1 at the end, equal-power shape (`sin`): it rises quickly at first, so a
     * fade-in is heard to begin, then eases into full level. Clamped outside [0, durationMs]; a
     * non-positive duration is already finished (1).
     */
    fun fadeIn(elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || elapsedMs >= durationMs) return 1f
        if (elapsedMs <= 0L) return 0f
        val t = elapsedMs.toDouble() / durationMs
        return sin(t * PI / 2.0).toFloat()
    }

    /**
     * 1 at the start, 0 at the end, a raised cosine (`cos²`, i.e. `(1 + cos πt) / 2`).
     *
     * It is very slow at first (−0.2 dB after 10 % of the time, −1.4 dB after 25 %), so someone
     * drifting off does not notice the sound start to go, and speeds up only once it is already
     * quiet. Clamped outside [0, durationMs]; a non-positive duration is already finished (0).
     */
    fun fadeOut(elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || elapsedMs >= durationMs) return 0f
        if (elapsedMs <= 0L) return 1f
        val t = elapsedMs.toDouble() / durationMs
        val c = cos(t * PI / 2.0)
        return (c * c).toFloat()
    }
}

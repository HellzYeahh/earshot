package dev.earshot.find

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Which earbud plays the tone. The other channel is silent, so the one that rings is the one to look for. */
enum class FindSide { LEFT, RIGHT, BOTH }

/**
 * The "find my earbuds" sound: a two-note chirp (2.9 kHz then 3.7 kHz, 200 ms each), repeated.
 * High notes carry through a sofa cushion and stand out from room noise; two of them read as an
 * alert rather than music. Stereo 16-bit PCM with the other side zeroed (research "Universal
 * headset control accelerators", §5: one-sided `AudioTrack`; Android's Mono audio and balance
 * accessibility settings can still defeat it, which the screen says).
 *
 * The volume starts low and rises over [RAMP_MS]: someone may still be wearing the buds.
 * Pure, so the shape is unit-tested.
 */
object FindTone {
    const val SAMPLE_RATE = 48_000
    private const val NOTE_A_HZ = 2_900.0
    private const val NOTE_B_HZ = 3_700.0
    private const val NOTE_MS = 200
    private const val GAP_MS = 60
    private const val REST_MS = 400

    /** One period: note, gap, note, rest. */
    const val PERIOD_MS = NOTE_MS + GAP_MS + NOTE_MS + REST_MS

    /** Ramp from [START_GAIN] to full over this long. */
    const val RAMP_MS = 6_000L
    const val START_GAIN = 0.2

    /** A 5 ms fade at each note edge, so the notes don't click. */
    private const val FADE_MS = 5

    /** Headroom below full scale: loud enough, never clipping after resampling. */
    private const val PEAK = 0.85

    fun gainAt(elapsedMs: Long): Double =
        START_GAIN + (1.0 - START_GAIN) * min(1.0, elapsedMs.coerceAtLeast(0).toDouble() / RAMP_MS)

    /** Frames (one sample per channel) in [ms] at [SAMPLE_RATE]. */
    private fun frames(ms: Int): Int = SAMPLE_RATE * ms / 1000

    /** One period of interleaved stereo PCM (left, right, left, …) at [gain] (0..1) for [side]. */
    fun period(side: FindSide, gain: Double): ShortArray {
        val g = gain.coerceIn(0.0, 1.0) * PEAK
        val total = frames(PERIOD_MS)
        val out = ShortArray(total * 2)
        writeNote(out, startFrame = 0, hz = NOTE_A_HZ, side = side, gain = g)
        writeNote(out, startFrame = frames(NOTE_MS + GAP_MS), hz = NOTE_B_HZ, side = side, gain = g)
        return out
    }

    private fun writeNote(out: ShortArray, startFrame: Int, hz: Double, side: FindSide, gain: Double) {
        val length = frames(NOTE_MS)
        val fade = frames(FADE_MS)
        for (i in 0 until length) {
            val edge = min(1.0, min(i, length - 1 - i).toDouble() / fade)
            val sample = (sin(2 * PI * hz * i / SAMPLE_RATE) * gain * edge * Short.MAX_VALUE).roundToInt().toShort()
            val frame = (startFrame + i) * 2
            if (side != FindSide.RIGHT) out[frame] = sample
            if (side != FindSide.LEFT) out[frame + 1] = sample
        }
    }
}

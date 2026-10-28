package dev.earshot.dsp.sleep

/**
 * The loud last resort: four 140 ms beeps alternating B5 and E6 (988 / 1319 Hz, a fourth apart,
 * the "bee-dah" of a clock radio), then half a second of rest, every 1.3 s.
 *
 * The waveform is a sine with a little of its 3rd and 5th harmonics: rounder than a square wave,
 * but with enough upper energy to cut through sleep and through cheap earbuds that roll off the
 * fundamental. 5 ms raised-cosine edges keep the beeps free of clicks. The pattern is the same for
 * every seed; only the oscillator's start phase follows it.
 */
internal class ClassicTone(seed: Long, sampleRate: Int) : StereoSynth(sampleRate) {
    private val slotFrames = (SLOT_SECONDS * sampleRate).toInt()
    private val beepFrames = (BEEP_SECONDS * sampleRate).toInt()
    private val edgeFrames = (EDGE_SECONDS * sampleRate).toInt().coerceAtLeast(1)
    private val cycleFrames = (CYCLE_SECONDS * sampleRate).toInt()
    private val incrementLow = LOW_HZ / sampleRate
    private val incrementHigh = HIGH_HZ / sampleRate
    private var phase = Rng(seed).nextDouble()
    private var position = 0

    override fun control() = Unit

    override fun frame() {
        val slot = position / slotFrames
        val within = position - slot * slotFrames
        var y = 0.0
        if (slot < BEEPS && within < beepFrames) {
            val edge = when {
                within < edgeFrames -> ramp(within)
                beepFrames - 1 - within < edgeFrames -> ramp(beepFrames - 1 - within)
                else -> 1.0
            }
            y = waveform(phase) * edge * LEVEL
            phase += if (slot % 2 == 0) incrementLow else incrementHigh
            if (phase >= 1.0) phase -= 1.0
        }
        if (++position >= cycleFrames) position = 0
        left = y
        right = y
    }

    private fun ramp(frames: Int): Double {
        val s = SineTable.cycle(0.25 * frames / edgeFrames)
        return s * s
    }

    private fun waveform(p: Double): Double {
        var p3 = 3.0 * p
        p3 -= p3.toInt()
        var p5 = 5.0 * p
        p5 -= p5.toInt()
        return SineTable.cycle(p) + 0.3 * SineTable.cycle(p3) + 0.12 * SineTable.cycle(p5)
    }

    private companion object {
        const val BEEPS = 4
        const val SLOT_SECONDS = 0.2
        const val BEEP_SECONDS = 0.14
        const val EDGE_SECONDS = 0.005
        const val CYCLE_SECONDS = 1.3
        const val LOW_HZ = 987.77
        const val HIGH_HZ = 1318.51

        /** The waveform peaks at 0.874; this puts the beeps at 0.8 (−1.9 dBFS). */
        const val LEVEL = 0.8 / 0.874
    }
}

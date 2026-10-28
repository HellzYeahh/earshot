package dev.earshot.dsp.sleep

import kotlin.math.exp
import kotlin.math.ln

/**
 * A soft bell arpeggio: four notes of a C major pentatonic, 0.3 s apart, every four seconds. The
 * order changes from one arpeggio to the next (up, down, broken) and the timing and touch vary a
 * little, so it sounds played rather than looped.
 *
 * Each note is a struck metal bar: partials at the free-bar ratios 1 : 2.756 : 5.404 (the modes of
 * a glockenspiel or vibraphone bar), the upper ones dying much faster, plus a copy of the
 * fundamental detuned by 0.15 % whose slow beating gives a bell's shimmer. A 4 ms onset keeps the
 * strike soft rather than clicky.
 */
internal class ChimeTone(seed: Long, sampleRate: Int) : StereoSynth(sampleRate) {
    private val dice = Rng(seed)

    private val phase = DoubleArray(VOICES * PARTIALS)
    private val increment = DoubleArray(VOICES * PARTIALS)
    private val level = DoubleArray(VOICES * PARTIALS)
    private val decay = DoubleArray(VOICES * PARTIALS)
    private val onset = DoubleArray(VOICES)
    private val active = BooleanArray(VOICES)
    private val gainL = DoubleArray(VOICES)
    private val gainR = DoubleArray(VOICES)
    private val onsetStep = 1.0 / (ATTACK_SECONDS * sampleRate)

    private var pattern = PATTERNS[dice.nextInt(PATTERNS.size)]
    private var noteIndex = 0
    private var wait = 1

    override fun control() = Unit

    override fun frame() {
        if (--wait <= 0) {
            if (noteIndex >= pattern.size) {
                pattern = PATTERNS[dice.nextInt(PATTERNS.size)]
                noteIndex = 0
            }
            strike(pattern[noteIndex])
            noteIndex++
            wait = if (noteIndex < pattern.size) {
                ((NOTE_SPACING_SECONDS + dice.range(-HUMANISE_SECONDS, HUMANISE_SECONDS)) * sampleRate).toInt()
            } else {
                ((CYCLE_SECONDS - (pattern.size - 1) * NOTE_SPACING_SECONDS) * sampleRate).toInt()
            }
        }

        var l = 0.0
        var r = 0.0
        for (v in 0 until VOICES) {
            if (!active[v]) continue
            var o = onset[v]
            if (o < 1.0) {
                o = (o + onsetStep).coerceAtMost(1.0)
                onset[v] = o
            }
            val base = v * PARTIALS
            var sum = 0.0
            for (i in base until base + PARTIALS) {
                val e = level[i]
                sum += SineTable.cycle(phase[i]) * e
                level[i] = e * decay[i]
                var p = phase[i] + increment[i]
                if (p >= 1.0) p -= 1.0
                phase[i] = p
            }
            if (level[base] < END_LEVEL) active[v] = false
            val y = sum * o
            l += y * gainL[v]
            r += y * gainR[v]
        }
        left = l
        right = r
    }

    private fun strike(note: Int) {
        val v = pickVoice()
        val f = SCALE[note]
        val velocity = dice.range(0.8, 1.0)
        // Higher bars ring a little shorter, as real ones do.
        val lengthScale = exp(0.3 * ln(SCALE[0] / f))
        for (p in 0 until PARTIALS) {
            val i = v * PARTIALS + p
            phase[i] = 0.0
            increment[i] = f * RATIOS[p] / sampleRate
            level[i] = AMPLITUDES[p] * velocity * NOTE_LEVEL
            decay[i] = exp(-1.0 / (TIME_CONSTANTS[p] * lengthScale * sampleRate))
        }
        onset[v] = 0.0
        val pan = -0.35 + 0.7 * note / (SCALE.size - 1)
        gainL[v] = Pan.left(pan)
        gainR[v] = Pan.right(pan)
        active[v] = true
    }

    private fun pickVoice(): Int {
        var best = 0
        for (v in 0 until VOICES) {
            if (!active[v]) return v
            if (level[v * PARTIALS] < level[best * PARTIALS]) best = v
        }
        return best
    }

    private companion object {
        const val VOICES = 12
        const val PARTIALS = 4
        const val ATTACK_SECONDS = 0.004
        const val NOTE_SPACING_SECONDS = 0.3
        const val HUMANISE_SECONDS = 0.012
        const val CYCLE_SECONDS = 4.0
        const val NOTE_LEVEL = 0.28
        const val END_LEVEL = 0.0015

        /** C5 D5 E5 G5 A5 C6. */
        val SCALE = doubleArrayOf(523.25, 587.33, 659.26, 783.99, 880.0, 1046.5)
        val PATTERNS = arrayOf(
            intArrayOf(0, 2, 3, 5),
            intArrayOf(5, 3, 2, 0),
            intArrayOf(0, 3, 2, 5),
            intArrayOf(2, 3, 4, 5),
        )
        val RATIOS = doubleArrayOf(1.0, 2.756, 5.404, 1.0015)
        val AMPLITUDES = doubleArrayOf(1.0, 0.22, 0.07, 0.3)
        val TIME_CONSTANTS = doubleArrayOf(1.6, 0.45, 0.16, 1.2)
    }
}

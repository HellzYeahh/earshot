package dev.earshot.dsp.sleep

/**
 * Birdsong at dawn: three synthetic birds, each in its own place, over a quiet outdoor bed (soft
 * band-passed noise that breathes like a light breeze).
 *
 * A syllable is a sine (plus a little second harmonic) swept in frequency under a `sin²` envelope,
 * with a fast vibrato for the warbler: the classic FM-chirp recipe. Birds sing in phrases (a run
 * of syllables, then a pause of seconds), and each bird keeps its own timing, so they overlap and
 * answer each other irregularly:
 *
 * - a **warbler**: 3–6 warbled 70–130 ms notes around 3 kHz;
 * - a **chirper**: quick trills of 4–9 rising 30–45 ms chirps;
 * - a **whistler**: a slow two-note "tee-oo" falling from 2.3 to 1.5 kHz.
 */
internal class BirdsTone(seed: Long, sampleRate: Int) : StereoSynth(sampleRate) {
    private val noise = Rng(seed)
    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val bedL = Svf(sampleRate).bandPass(1600.0, 0.5)
    private val bedR = Svf(sampleRate).bandPass(1600.0, 0.5)
    private val breeze = Drift(Rng(seed, 1), 0.5, 1.0, 3.0, 9.0, sampleRate)
    private val birds = arrayOf(
        Bird(Song.WARBLER, -0.55, Rng(seed, 2)),
        Bird(Song.CHIRPER, 0.6, Rng(seed, 3)),
        Bird(Song.WHISTLER, 0.1, Rng(seed, 4)),
    )

    override fun control() = Unit

    override fun frame() {
        val b = breeze.next() * BED
        var l = bedL.process(pinkL.process(noise.bipolar())) * b
        var r = bedR.process(pinkR.process(noise.bipolar())) * b
        for (bird in birds) {
            val y = bird.tick()
            l += y * bird.gainL
            r += y * bird.gainR
        }
        left = l
        right = r
    }

    private enum class Song { WARBLER, CHIRPER, WHISTLER }

    private inner class Bird(private val song: Song, pan: Double, private val dice: Rng) {
        val gainL = Pan.left(pan)
        val gainR = Pan.right(pan)

        private var wait = (dice.range(0.2, 2.0) * sampleRate).toInt()
        private var syllablesLeft = 0
        private var syllableIndex = 0
        private var phraseScale = 1.0
        private var phraseLevel = 1.0

        private var sounding = false
        private var position = 0
        private var length = 1
        private var inverseLength = 1.0
        private var phase = 0.0
        private var startIncrement = 0.0
        private var endIncrement = 0.0
        private var vibratoPhase = 0.0
        private var vibratoIncrement = 0.0
        private var vibratoDepth = 0.0
        private var amplitude = 0.0

        fun tick(): Double {
            if (!sounding) {
                if (--wait > 0) return 0.0
                startSyllable()
            }
            val x = position * inverseLength
            val s = SineTable.cycle(0.5 * x)
            val envelope = s * s
            val vibrato = SineTable.cycle(vibratoPhase) * vibratoDepth
            vibratoPhase += vibratoIncrement
            if (vibratoPhase >= 1.0) vibratoPhase -= 1.0
            phase += startIncrement + (endIncrement - startIncrement) * x + vibrato
            if (phase >= 1.0) phase -= 1.0
            var twice = 2.0 * phase
            if (twice >= 1.0) twice -= 1.0
            val y = (SineTable.cycle(phase) + HARMONIC * SineTable.cycle(twice)) * envelope * amplitude
            if (++position >= length) {
                sounding = false
                wait = if (syllablesLeft > 0) gapFrames() else phrasePauseFrames()
            }
            return y
        }

        private fun startSyllable() {
            if (syllablesLeft == 0) {
                syllablesLeft = when (song) {
                    Song.WARBLER -> 3 + dice.nextInt(4)
                    Song.CHIRPER -> 4 + dice.nextInt(6)
                    Song.WHISTLER -> 2
                }
                syllableIndex = 0
                phraseScale = dice.range(0.92, 1.08)
                phraseLevel = dice.range(0.6, 1.0)
            }
            val seconds: Double
            val from: Double
            val to: Double
            var vibratoHz = 0.0
            var vibratoHzDepth = 0.0
            val loudness: Double
            when (song) {
                Song.WARBLER -> {
                    seconds = dice.range(0.07, 0.13)
                    from = dice.range(2800.0, 3600.0)
                    to = from * dice.range(0.75, 1.3)
                    vibratoHz = dice.range(28.0, 40.0)
                    vibratoHzDepth = dice.range(120.0, 250.0)
                    loudness = WARBLER_LEVEL
                }
                Song.CHIRPER -> {
                    seconds = dice.range(0.028, 0.045)
                    from = dice.range(3000.0, 3400.0)
                    to = from * dice.range(1.6, 1.8)
                    loudness = CHIRPER_LEVEL
                }
                Song.WHISTLER -> {
                    seconds = dice.range(0.18, 0.28)
                    if (syllableIndex == 0) {
                        from = 2300.0
                        to = 2200.0
                    } else {
                        from = 1750.0
                        to = 1500.0
                    }
                    vibratoHz = 6.0
                    vibratoHzDepth = 15.0
                    loudness = WHISTLER_LEVEL
                }
            }
            val fs = sampleRate.toDouble()
            length = (seconds * fs).toInt().coerceAtLeast(1)
            inverseLength = 1.0 / length
            position = 0
            startIncrement = from * phraseScale / fs
            endIncrement = to * phraseScale / fs
            vibratoIncrement = vibratoHz / fs
            vibratoDepth = vibratoHzDepth / fs
            vibratoPhase = 0.0
            amplitude = loudness * phraseLevel
            syllablesLeft--
            syllableIndex++
            sounding = true
        }

        private fun gapFrames(): Int = (
            when (song) {
                Song.WARBLER -> dice.range(0.04, 0.09)
                Song.CHIRPER -> dice.range(0.035, 0.06)
                Song.WHISTLER -> dice.range(0.09, 0.14)
            } * sampleRate
            ).toInt().coerceAtLeast(1)

        private fun phrasePauseFrames(): Int = (
            when (song) {
                Song.WARBLER -> dice.range(2.5, 6.0)
                Song.CHIRPER -> dice.range(3.0, 7.0)
                Song.WHISTLER -> dice.range(4.0, 9.0)
            } * sampleRate
            ).toInt()
    }

    private companion object {
        const val BED = 0.08
        const val HARMONIC = 0.12
        const val WARBLER_LEVEL = 0.64
        const val CHIRPER_LEVEL = 0.44
        const val WHISTLER_LEVEL = 0.56
    }
}

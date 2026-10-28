package dev.earshot.dsp.sleep

/**
 * White, pink and brown noise, with independent noise per channel (a wide, diffuse image rather
 * than a sound in the middle of the head).
 *
 * The three are matched by ear-weighted loudness rather than RMS: at equal RMS white sounds far
 * louder than brown, whose energy sits where hearing is least sensitive. So white is the quietest
 * in RMS and brown the loudest, and switching between them does not jump in level.
 */
internal class ColouredNoise(private val colour: Colour, seed: Long, sampleRate: Int) : StereoSynth(sampleRate) {
    enum class Colour { WHITE, PINK, BROWN }

    private val noise = Rng(seed)
    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val brownL = BrownFilter(sampleRate)
    private val brownR = BrownFilter(sampleRate)

    init {
        outputGain = when (colour) {
            Colour.WHITE -> WHITE_GAIN
            Colour.PINK -> PINK_GAIN
            Colour.BROWN -> BROWN_GAIN
        }
    }

    override fun control() = Unit

    override fun frame() {
        val wl = noise.bipolar()
        val wr = noise.bipolar()
        when (colour) {
            Colour.WHITE -> {
                left = wl
                right = wr
            }
            Colour.PINK -> {
                left = pinkL.process(wl)
                right = pinkR.process(wr)
            }
            Colour.BROWN -> {
                left = brownL.process(wl)
                right = brownR.process(wr)
            }
        }
    }

    private companion object {
        // Input RMS is 1/√3 (uniform noise). These land white at −24 dBFS RMS, pink at −21 and
        // brown at −16.5, which measure −18, −18 and −17.5 LUFS (ITU-R BS.1770 K-weighting).
        // Brown gets the extra half decibel because at bedtime listening levels the ear hears
        // bass more weakly than that weighting assumes.
        const val WHITE_GAIN = 0.107
        const val PINK_GAIN = 0.162
        const val BROWN_GAIN = 0.34
    }
}

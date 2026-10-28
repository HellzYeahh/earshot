package dev.earshot.dsp.sleep

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Wind in gusts.
 *
 * One slow gust signal (2.5–9 s moves) drives everything, because in real wind loudness and pitch
 * rise together: a low roar (brown noise under 170 Hz), the main "whoosh" (pink noise through a
 * band-pass whose centre climbs from about 220 Hz to 1 kHz as the gust builds, a little different
 * per ear so the wind moves around you), a faint whistle only in the strongest gusts, and a rustle
 * of leaves ([Spatter]) that thickens with the gust.
 */
internal class WindSynth(seed: Long, sampleRate: Int) : StereoSynth(sampleRate) {
    private val noise = Rng(seed)

    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val brownL = BrownFilter(sampleRate)
    private val brownR = BrownFilter(sampleRate)
    private val roarL = Svf(sampleRate).lowPass(170.0, 0.6)
    private val roarR = Svf(sampleRate).lowPass(170.0, 0.6)
    private val whooshL = Svf(sampleRate)
    private val whooshR = Svf(sampleRate)
    private val whistleL = Svf(sampleRate)
    private val whistleR = Svf(sampleRate)
    private val rustle = Spatter(sampleRate, Rng(seed, 1), 3800.0, 0.7, 0.0009, 0.03, 0.5)

    private val gust = Drift(Rng(seed, 2), GUST_LOW, 1.0, 2.5, 9.0, sampleRate)
    private val leanL = Drift(Rng(seed, 3), 0.88, 1.12, 1.5, 5.0, sampleRate)
    private val leanR = Drift(Rng(seed, 4), 0.88, 1.12, 1.5, 5.0, sampleRate)
    private val focus = Drift(Rng(seed, 5), 0.8, 1.5, 3.0, 8.0, sampleRate)
    private val whistlePitch = Drift(Rng(seed, 6), 650.0, 1400.0, 4.0, 12.0, sampleRate)

    private val roarGain = ControlRamp()
    private val whooshGain = ControlRamp()
    private val whistleGain = ControlRamp()

    init {
        outputGain = OUTPUT_GAIN
    }

    override fun control() {
        val g = gust.nextControl()
        val q = focus.nextControl()
        val centre = 220.0 + 850.0 * g
        whooshL.bandPass(centre * leanL.nextControl(), q)
        whooshR.bandPass(centre * leanR.nextControl(), q)
        val pitch = whistlePitch.nextControl() * (0.85 + 0.3 * g)
        whistleL.bandPass(pitch, WHISTLE_Q)
        whistleR.bandPass(pitch * 1.03, WHISTLE_Q)
        roarGain.target(ROAR * (0.35 + 0.65 * g))
        whooshGain.target(WHOOSH * exp(GUST_CURVE * ln(g)))
        val strong = max(0.0, g - 0.6) / 0.4
        whistleGain.target(WHISTLE * strong * strong)
        rustle.rate = RUSTLE_RATE * g * g * g
    }

    override fun frame() {
        val wl = noise.bipolar()
        val wr = noise.bipolar()
        val rg = roarGain.next()
        val wg = whooshGain.next()
        val sg = whistleGain.next()
        rustle.tick()
        left = roarL.process(brownL.process(wl)) * rg +
            whooshL.process(pinkL.process(wl)) * wg +
            whistleL.process(wl) * sg +
            rustle.left * RUSTLE
        right = roarR.process(brownR.process(wr)) * rg +
            whooshR.process(pinkR.process(wr)) * wg +
            whistleR.process(wr) * sg +
            rustle.right * RUSTLE
    }

    private companion object {
        /** A lull never drops to stillness: a sleeper notices silence, then the next gust. */
        const val GUST_LOW = 0.3
        const val GUST_CURVE = 1.2
        const val WHISTLE_Q = 9.0
        const val RUSTLE_RATE = 900.0
        const val ROAR = 0.5
        const val WHOOSH = 1.6
        const val WHISTLE = 0.5
        const val RUSTLE = 0.5
        const val OUTPUT_GAIN = 0.32
    }
}

package dev.earshot.dsp.sleep

/**
 * A box fan on a medium setting.
 *
 * Moving air (pink noise under 1.5 kHz plus a brown-noise body) is gently chopped at the
 * blade-pass rate, three blades on a 17–20 Hz rotor, so about 55 Hz, with a weaker once-a-turn
 * wobble from a slightly unbalanced blade: that soft "whirr" is what makes it a fan rather than
 * noise. Under it a faint motor hum (100 Hz, the mains-doubled frequency of an induction motor,
 * with two harmonics). The speed wanders by ±0.6 % as a real motor's does; the rotor speed
 * differs per seed, as fans do. The image is fairly narrow: the fan stands in one place.
 */
internal class FanSynth(seed: Long, sampleRate: Int) : StereoSynth(sampleRate) {
    private val noise = Rng(seed)
    private val model = Rng(seed, 1)

    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val brownL = BrownFilter(sampleRate)
    private val brownR = BrownFilter(sampleRate)
    private val airL = Svf(sampleRate).lowPass(1500.0, 0.6)
    private val airR = Svf(sampleRate).lowPass(1500.0, 0.6)
    private val bodyL = Svf(sampleRate).lowPass(380.0, 0.7)
    private val bodyR = Svf(sampleRate).lowPass(380.0, 0.7)
    private val floorL = Svf(sampleRate).highPass(28.0)
    private val floorR = Svf(sampleRate).highPass(28.0)

    private val speed = Drift(Rng(seed, 2), 1.0 - SPEED_WANDER, 1.0 + SPEED_WANDER, 3.0, 10.0, sampleRate)
    private val rotorHz = model.range(17.0, 20.0)
    private var rotorPhase = 0.0
    private var bladePhase = 0.0
    private var humPhase = 0.0
    private var rotorIncrement = rotorHz / sampleRate
    private val humIncrement = HUM_HZ / sampleRate

    init {
        outputGain = OUTPUT_GAIN
        width = WIDTH
    }

    override fun control() {
        rotorIncrement = rotorHz * speed.nextControl() / sampleRate
    }

    override fun frame() {
        val blade = SineTable.cycle(bladePhase)
        val wobble = SineTable.cycle(rotorPhase)
        val chop = 1.0 + BLADE_DEPTH * blade + WOBBLE_DEPTH * wobble
        rotorPhase = wrap(rotorPhase + rotorIncrement)
        bladePhase = wrap(bladePhase + BLADES * rotorIncrement)

        val h = humPhase
        val hum = HUM_1 * SineTable.cycle(h) + HUM_2 * SineTable.cycle(wrap(2.0 * h)) + HUM_3 * SineTable.cycle(wrap(3.0 * h))
        humPhase = wrap(h + humIncrement)

        val wl = noise.bipolar()
        val wr = noise.bipolar()
        val l = airL.process(pinkL.process(wl)) * AIR * chop + bodyL.process(brownL.process(wl)) * BODY * (1.0 + 0.5 * BLADE_DEPTH * blade)
        val r = airR.process(pinkR.process(wr)) * AIR * chop + bodyR.process(brownR.process(wr)) * BODY * (1.0 + 0.5 * BLADE_DEPTH * blade)
        left = floorL.process(l + hum)
        right = floorR.process(r + hum)
    }

    private fun wrap(phase: Double): Double = if (phase >= 1.0) phase - phase.toInt() else phase

    private companion object {
        const val BLADES = 3.0
        const val SPEED_WANDER = 0.006
        const val BLADE_DEPTH = 0.07
        const val WOBBLE_DEPTH = 0.025
        const val HUM_HZ = 100.0
        const val HUM_1 = 0.010
        const val HUM_2 = 0.005
        const val HUM_3 = 0.0035
        const val WIDTH = 0.55
        const val AIR = 0.6
        const val BODY = 0.5
        const val OUTPUT_GAIN = 0.437
    }
}

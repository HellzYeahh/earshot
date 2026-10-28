package dev.earshot.dsp.sleep

/**
 * Rain, in layers, each covering what the one before cannot:
 *
 * 1. **Air**: a quiet bed of pink noise band-passed to the "shhh" of rain (about 450 Hz to
 *    5–7 kHz, the top drifting slowly) plus a soft low-mid body. Kept low on purpose: filtered
 *    noise on its own is a waterfall or static, and measured that way (its 10 ms energy barely
 *    moves) it was what made the first version sound like a detuned radio.
 * 2. **Speckle**: hundreds of tiny impacts a second ([Spatter]) in two bands, a lower "patter"
 *    (on leaves and ground, around 1.6 kHz) and a finer, brighter "fizz" (around 4.5 kHz), with
 *    a wide spread of loudness. This grain is what makes the ear hear *drops*.
 * 3. **Drops**: a few distinct drops a second ([NoiseBursts]), each a 1–12 ms band-passed noise
 *    burst with its own pitch, level and place, some closer and lower (off a gutter, a leaf).
 * 4. **Plinks**: now and then a drop into a puddle ([Bubbles]), kept quiet so it never turns into
 *    a dripping tap.
 *
 * Heavy rain is the same recipe denser and broader, with more low-mid body and a distant rumble
 * bed (brown noise under 110 Hz, slowly swelling). The whole downpour breathes over 4–18 s.
 */
internal class RainSynth(seed: Long, sampleRate: Int, private val heavy: Boolean) : StereoSynth(sampleRate) {
    private val recipe = if (heavy) HEAVY else LIGHT

    private val noise = Rng(seed)
    private val dice = Rng(seed, 1)

    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val bedHighL = Svf(sampleRate).highPass(recipe.bedLowHz, BED_Q)
    private val bedHighR = Svf(sampleRate).highPass(recipe.bedLowHz, BED_Q)
    private val bedLowL = Svf(sampleRate)
    private val bedLowR = Svf(sampleRate)
    private val bodyL = Svf(sampleRate).bandPass(recipe.bodyHz, BODY_Q)
    private val bodyR = Svf(sampleRate).bandPass(recipe.bodyHz, BODY_Q)
    private val brownL = BrownFilter(sampleRate)
    private val brownR = BrownFilter(sampleRate)
    private val rumbleL = Svf(sampleRate).lowPass(RUMBLE_HZ, BED_Q)
    private val rumbleR = Svf(sampleRate).lowPass(RUMBLE_HZ, BED_Q)

    private val intensity = Drift(Rng(seed, 2), recipe.calmest, 1.0, recipe.surgeMinSeconds, recipe.surgeMaxSeconds, sampleRate)
    private val swayL = Drift(Rng(seed, 3), SWAY_LOW, 1.0, 2.0, 6.0, sampleRate)
    private val swayR = Drift(Rng(seed, 4), SWAY_LOW, 1.0, 2.0, 6.0, sampleRate)
    private val brightness = Drift(Rng(seed, 5), recipe.darkestHz, recipe.brightestHz, 3.0, 10.0, sampleRate)
    private val rumbleSwell = Drift(Rng(seed, 6), 0.4, 1.0, 8.0, 25.0, sampleRate)
    private val patter = Spatter(
        sampleRate, Rng(seed, 7),
        centreHz = 1600.0, q = 0.9, decaySeconds = 0.0022, quietest = 0.015, loudest = 0.45,
    )
    private val fizz = Spatter(
        sampleRate, Rng(seed, 9),
        centreHz = 4500.0, q = 0.8, decaySeconds = 0.0009, quietest = 0.01, loudest = 0.5,
    )
    private val drops = NoiseBursts(DROP_VOICES, sampleRate, Rng(seed, 8))
    private val plinks = Bubbles(PLINK_VOICES, sampleRate)

    private var surge = intensity.value
    private var dropWait = dice.exponentialFrames(sampleRate / recipe.dropRate)
    private var plinkWait = dice.exponentialFrames(sampleRate / recipe.plinkRate)

    init {
        outputGain = recipe.outputGain
    }

    override fun control() {
        val cutoff = brightness.nextControl()
        bedLowL.lowPass(cutoff, BED_Q)
        bedLowR.lowPass(cutoff, BED_Q)
        // Speckle density follows the downpour faster than its loudness (squared).
        val density = surge * surge
        patter.rate = recipe.patterRate * density
        fizz.rate = recipe.fizzRate * density
    }

    override fun frame() {
        val s = intensity.next()
        surge = s
        val pl = pinkL.process(noise.bipolar())
        val pr = pinkR.process(noise.bipolar())
        var l = (bedLowL.process(bedHighL.process(pl)) * recipe.air + bodyL.process(pl) * recipe.body) * s * swayL.next()
        var r = (bedLowR.process(bedHighR.process(pr)) * recipe.air + bodyR.process(pr) * recipe.body) * s * swayR.next()

        patter.tick()
        fizz.tick()
        l += patter.left * PATTER + fizz.left * FIZZ
        r += patter.right * PATTER + fizz.right * FIZZ

        if (--dropWait <= 0) {
            drop()
            dropWait = dice.exponentialFrames(sampleRate / (recipe.dropRate * s))
        }
        if (--plinkWait <= 0) {
            plink()
            plinkWait = dice.exponentialFrames(sampleRate / recipe.plinkRate)
        }
        drops.tick()
        plinks.tick()
        l += drops.left + plinks.left
        r += drops.right + plinks.right

        if (heavy) {
            val swell = rumbleSwell.next() * recipe.rumble
            l += rumbleL.process(brownL.process(noise.bipolar())) * swell
            r += rumbleR.process(brownR.process(noise.bipolar())) * swell
        }
        left = l
        right = r
    }

    private fun drop() {
        if (dice.chance(CLOSE_DROP_CHANCE)) {
            drops.trigger(
                amplitude = dice.logRange(0.5, 1.1) * DROP,
                attackSeconds = 0.0002,
                decaySeconds = dice.range(0.004, 0.012),
                centreHz = dice.logRange(500.0, 1500.0),
                q = dice.range(1.5, 4.0),
                pan = dice.range(-0.8, 0.8),
            )
        } else {
            drops.trigger(
                amplitude = dice.logRange(0.12, 0.7) * DROP,
                attackSeconds = 0.00015,
                decaySeconds = dice.range(0.0015, 0.006),
                centreHz = dice.logRange(900.0, 4800.0),
                q = dice.range(0.8, 2.5),
                pan = dice.range(-0.95, 0.95),
            )
        }
    }

    private fun plink() {
        plinks.trigger(
            frequencyHz = dice.logRange(1100.0, 3200.0),
            amplitude = dice.logRange(0.03, 0.1) * PLINK,
            pan = dice.range(-0.8, 0.8),
            rise = dice.range(0.06, 0.14),
        )
    }

    /** What differs between light and heavy rain. */
    private class Recipe(
        val bedLowHz: Double,
        val darkestHz: Double,
        val brightestHz: Double,
        val air: Double,
        val bodyHz: Double,
        val body: Double,
        val patterRate: Double,
        val fizzRate: Double,
        val dropRate: Double,
        val plinkRate: Double,
        val calmest: Double,
        val surgeMinSeconds: Double,
        val surgeMaxSeconds: Double,
        val rumble: Double,
        val outputGain: Double,
    )

    private companion object {
        const val BED_Q = 0.6
        const val BODY_Q = 0.6
        const val SWAY_LOW = 0.82
        const val RUMBLE_HZ = 110.0
        const val PATTER = 1.0
        const val FIZZ = 0.8
        const val DROP = 0.8
        const val PLINK = 1.0
        const val DROP_VOICES = 20
        const val PLINK_VOICES = 8
        const val CLOSE_DROP_CHANCE = 0.08

        val LIGHT = Recipe(
            bedLowHz = 450.0, darkestHz = 4800.0, brightestHz = 6800.0, air = 0.22,
            bodyHz = 600.0, body = 0.15,
            patterRate = 420.0, fizzRate = 750.0, dropRate = 9.0, plinkRate = 0.6,
            calmest = 0.72, surgeMinSeconds = 4.0, surgeMaxSeconds = 14.0,
            rumble = 0.0, outputGain = 0.59,
        )
        val HEAVY = Recipe(
            bedLowHz = 260.0, darkestHz = 6000.0, brightestHz = 8500.0, air = 0.35,
            bodyHz = 420.0, body = 0.4,
            patterRate = 1300.0, fizzRate = 2200.0, dropRate = 30.0, plinkRate = 1.6,
            calmest = 0.65, surgeMinSeconds = 6.0, surgeMaxSeconds = 18.0,
            rumble = 0.35, outputGain = 0.35,
        )
    }
}

package dev.earshot.dsp.sleep

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Waves on a beach.
 *
 * A new wave arrives every 5.5–11 s (random, so the sea never ticks like a clock). Each wave is
 * pink noise through its own low-pass: as the swell builds the cutoff opens from a dull 160 Hz
 * towards 2.8 kHz and the level rises, it crests, then washes out over several seconds as both
 * fall back. Waves overlap, the next building under the last one's wash, as on a real beach.
 * At the crest the surf breaks: a hiss and a fizz of foam ([Spatter]) that rises just before the
 * crest and dies away more slowly than the wave, travelling a little sideways as the break runs
 * along the shore. Under it all, a distant roar (brown noise under 200 Hz) never stops.
 */
internal class OceanSynth(seed: Long, sampleRate: Int) : StereoSynth(sampleRate) {
    private val noise = Rng(seed)
    private val dice = Rng(seed, 1)

    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val brownL = BrownFilter(sampleRate)
    private val brownR = BrownFilter(sampleRate)
    private val roarL = Svf(sampleRate).lowPass(ROAR_HZ, 0.6)
    private val roarR = Svf(sampleRate).lowPass(ROAR_HZ, 0.6)
    private val hissL = Svf(sampleRate).highPass(HISS_HZ, 0.6)
    private val hissR = Svf(sampleRate).highPass(HISS_HZ, 0.6)
    private val roarSwell = Drift(Rng(seed, 2), 0.6, 1.0, 6.0, 18.0, sampleRate)
    private val foam = Spatter(sampleRate, Rng(seed, 3), FOAM_HZ, 0.6, FOAM_DECAY_SECONDS, 0.04, 0.55)
    private val hissGainL = ControlRamp()
    private val hissGainR = ControlRamp()
    private val waves = Array(WAVES) { Wave() }
    private var untilNextWave: Int

    init {
        outputGain = OUTPUT_GAIN
        // Open on a swell that is already building rather than on a flat sea.
        val first = dice.range(MIN_PERIOD, MAX_PERIOD)
        spawn(first, startFraction = 0.5)
        untilNextWave = (first * 0.6 * sampleRate).toInt()
    }

    override fun control() {
        untilNextWave -= CONTROL_FRAMES
        if (untilNextWave <= 0) {
            val period = dice.range(MIN_PERIOD, MAX_PERIOD)
            spawn(period, startFraction = 0.0)
            untilNextWave = (period * sampleRate).toInt()
        }
        var hl = 0.0
        var hr = 0.0
        var fizz = 0.0
        for (w in waves) {
            if (!w.active) continue
            w.control()
            hl += w.hiss * w.panL
            hr += w.hiss * w.panR
            fizz += w.hiss
        }
        hissGainL.target(HISS * hl)
        hissGainR.target(HISS * hr)
        foam.rate = FOAM_RATE * fizz
    }

    override fun frame() {
        val wl = noise.bipolar()
        val wr = noise.bipolar()
        val pl = pinkL.process(wl)
        val pr = pinkR.process(wr)
        val swell = roarSwell.next() * ROAR
        var l = roarL.process(brownL.process(wl)) * swell
        var r = roarR.process(brownR.process(wr)) * swell
        for (w in waves) {
            if (!w.active) continue
            l += w.lowL.process(pl) * w.gainL.next()
            r += w.lowR.process(pr) * w.gainR.next()
        }
        l += hissL.process(wl) * hissGainL.next()
        r += hissR.process(wr) * hissGainR.next()
        foam.tick()
        left = l + foam.left * FOAM
        right = r + foam.right * FOAM
    }

    private fun spawn(period: Double, startFraction: Double) {
        var pick = waves[0]
        for (w in waves) {
            if (!w.active) {
                pick = w
                break
            }
            if (w.age > pick.age) pick = w
        }
        pick.start(period, startFraction)
    }

    private inner class Wave {
        var active = false
        var age = 0
        var panL = 0.0
        var panR = 0.0
        var hiss = 0.0
        val lowL = Svf(sampleRate)
        val lowR = Svf(sampleRate)
        val gainL = ControlRamp()
        val gainR = ControlRamp()

        private var riseSeconds = 1.0
        private var washSeconds = 1.0
        private var hissSeconds = 1.0
        private var strength = 1.0
        private var pan = 0.0
        private var travel = 0.0
        private var endFrame = 0
        private var dying = false

        fun start(period: Double, startFraction: Double) {
            // A wave reused while its tail still plays keeps its filters and ramps, so the tail
            // fades out over one control period instead of being cut.
            if (!active) {
                lowL.reset()
                lowR.reset()
            }
            riseSeconds = period * dice.range(0.35, 0.5)
            washSeconds = dice.range(1.4, 2.6)
            hissSeconds = dice.range(0.9, 1.8)
            strength = if (dice.chance(BIG_WAVE_CHANCE)) dice.range(0.9, 1.0) else dice.range(0.55, 0.9)
            pan = dice.range(-0.45, 0.45)
            travel = dice.range(-0.4, 0.4)
            endFrame = ((riseSeconds + WASH_TIME_CONSTANTS * washSeconds) * sampleRate).toInt()
            age = (startFraction * riseSeconds * sampleRate).toInt()
            dying = false
            active = true
        }

        fun control() {
            if (dying) {
                active = false
                hiss = 0.0
                return
            }
            age += CONTROL_FRAMES
            if (age >= endFrame) {
                dying = true
                gainL.target(0.0)
                gainR.target(0.0)
                hiss = 0.0
                return
            }
            val t = age.toDouble() / sampleRate
            val crestAt = riseSeconds
            val e = if (t < crestAt) {
                val x = t / crestAt
                val s = x * x * (3.0 - 2.0 * x)
                s * sqrt(s) // builds slowly, then rears up into the crest
            } else {
                exp(-(t - crestAt) / washSeconds)
            }
            val level = strength * e
            val opening = if (level > 1e-6) exp(OPENING_CURVE * ln(level)) else 0.0
            val cutoff = CLOSED_HZ + OPEN_HZ * opening
            lowL.lowPass(cutoff, WAVE_Q)
            lowR.lowPass(cutoff, WAVE_Q)

            val run = ((t - crestAt + 0.6) / 3.0).coerceIn(0.0, 1.0)
            val p = pan + travel * run
            panL = Pan.left(p)
            panR = Pan.right(p)
            gainL.target(BODY * level * panL)
            gainR.target(BODY * level * panR)

            val onsetX = ((t - (crestAt - 0.35)) / 0.55).coerceIn(0.0, 1.0)
            val onset = onsetX * onsetX * (3.0 - 2.0 * onsetX)
            val tail = if (t > crestAt + 0.2) exp(-(t - crestAt - 0.2) / hissSeconds) else 1.0
            hiss = strength * onset * tail
        }
    }

    private companion object {
        const val WAVES = 5
        const val MIN_PERIOD = 5.5
        const val MAX_PERIOD = 11.0
        const val BIG_WAVE_CHANCE = 0.15
        const val WASH_TIME_CONSTANTS = 6.0
        const val CLOSED_HZ = 160.0
        const val OPEN_HZ = 2600.0
        const val OPENING_CURVE = 1.3
        const val WAVE_Q = 0.65
        const val ROAR_HZ = 200.0
        const val HISS_HZ = 2400.0
        const val FOAM_HZ = 5200.0
        const val FOAM_DECAY_SECONDS = 0.0007
        const val FOAM_RATE = 2500.0

        const val ROAR = 0.33
        const val BODY = 0.7
        const val HISS = 0.08
        const val FOAM = 1.0
        const val OUTPUT_GAIN = 0.52
    }
}

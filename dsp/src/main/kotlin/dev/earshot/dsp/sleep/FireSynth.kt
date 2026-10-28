package dev.earshot.dsp.sleep

/**
 * A fireplace.
 *
 * What separates a fire from static is the crackle *rhythm*: crackles come in clusters (a log
 * section catching: a burst of 40–240 clicks a second for 40–400 ms) between quieter spells, with
 * a few loose ones in between, and each crackle is tiny: a 0.06–1 ms noise burst, band-passed
 * high (1.8–7.5 kHz) with now and then a larger, woodier crack lower down. Every several seconds
 * a pop: a louder snap with a short resonant tail (a pocket of sap bursting, 220–750 Hz, Q 5–11),
 * sometimes followed by a hiss of steam or a fresh cluster.
 *
 * Under the crackles, the flames themselves: a soft low roar (brown noise, 70–320 Hz) that
 * breathes over 6–18 s and flickers quickly, "lapping" licks in the low mids, and a faint hiss.
 * The image is narrowed a little ([width]): a fireplace sits in front of you.
 */
internal class FireSynth(seed: Long, sampleRate: Int) : StereoSynth(sampleRate) {
    private val noise = Rng(seed)
    private val dice = Rng(seed, 1)

    private val brownL = BrownFilter(sampleRate)
    private val brownR = BrownFilter(sampleRate)
    // The roar is banded to 70–320 Hz: below that brown noise is mostly sub-bass that earbuds
    // barely play, yet its big slow swings set the peaks and would force the crackles down.
    private val roarHighL = Svf(sampleRate).highPass(70.0)
    private val roarHighR = Svf(sampleRate).highPass(70.0)
    private val roarL = Svf(sampleRate).lowPass(320.0, 0.6)
    private val roarR = Svf(sampleRate).lowPass(320.0, 0.6)
    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val lapL = Svf(sampleRate).bandPass(240.0, 1.0)
    private val lapR = Svf(sampleRate).bandPass(240.0, 1.0)
    private val hissL = Svf(sampleRate).highPass(3500.0, 0.7)
    private val hissR = Svf(sampleRate).highPass(3500.0, 0.7)

    private val breathe = Drift(Rng(seed, 2), 0.7, 1.0, 6.0, 18.0, sampleRate)
    private val flicker = Drift(Rng(seed, 3), 0.55, 1.0, 0.08, 0.35, sampleRate)
    private val lap = Drift(Rng(seed, 4), 0.0, 1.0, 0.06, 0.3, sampleRate)
    private val hissFlutter = Drift(Rng(seed, 5), 0.0, 1.0, 0.03, 0.15, sampleRate)
    private val activity = Drift(Rng(seed, 6), 0.6, 1.5, 4.0, 15.0, sampleRate)

    private val crackles = NoiseBursts(CRACKLE_VOICES, sampleRate, Rng(seed, 7))
    private val pops = NoiseBursts(POP_VOICES, sampleRate, Rng(seed, 8))
    private val steam = NoiseBursts(STEAM_VOICES, sampleRate, Rng(seed, 9))

    private var lively = activity.value
    private var clusterLeft = 0
    private var clusterWait = (dice.range(0.1, 0.6) * sampleRate).toInt()
    private var clusterRate = 100.0
    private var clusterAmp = 0.5
    private var clusterPan = 0.0
    private var crackleWait = 1
    private var looseWait = dice.exponentialFrames(sampleRate / LOOSE_RATE)
    private var popWait = (dice.range(1.0, 5.0) * sampleRate).toInt()

    init {
        outputGain = OUTPUT_GAIN
        width = WIDTH
    }

    override fun control() {
        lively = activity.nextControl()
    }

    override fun frame() {
        val wl = noise.bipolar()
        val wr = noise.bipolar()
        val flame = breathe.next() * flicker.next() * ROAR
        val lick = lap.next()
        val licks = lick * lick * LAP
        val h = hissFlutter.next()
        val sizzle = h * h * HISS
        var l = roarL.process(roarHighL.process(brownL.process(wl))) * flame +
            lapL.process(pinkL.process(wl)) * licks + hissL.process(wl) * sizzle
        var r = roarR.process(roarHighR.process(brownR.process(wr))) * flame +
            lapR.process(pinkR.process(wr)) * licks + hissR.process(wr) * sizzle

        if (clusterLeft > 0) {
            clusterLeft--
            if (--crackleWait <= 0) {
                crackle(clusterAmp, clusterPan)
                crackleWait = dice.exponentialFrames(sampleRate / clusterRate)
            }
        } else if (--clusterWait <= 0) {
            startCluster(dice.range(-0.7, 0.7))
        }
        if (--looseWait <= 0) {
            crackle(dice.logRange(0.1, 0.5), dice.range(-0.8, 0.8))
            looseWait = dice.exponentialFrames(sampleRate / (LOOSE_RATE * lively))
        }
        if (--popWait <= 0) {
            pop()
            popWait = (POP_MIN_GAP_SECONDS * sampleRate).toInt() +
                dice.exponentialFrames(POP_MEAN_EXTRA_SECONDS * sampleRate / lively)
        }

        crackles.tick()
        pops.tick()
        steam.tick()
        l += crackles.left + pops.left + steam.left
        r += crackles.right + pops.right + steam.right
        left = l
        right = r
    }

    private fun startCluster(pan: Double) {
        clusterLeft = (dice.logRange(0.04, 0.4) * sampleRate).toInt()
        clusterRate = dice.logRange(40.0, 240.0)
        clusterAmp = dice.logRange(0.15, 1.0)
        clusterPan = pan
        crackleWait = 1
        clusterWait = dice.exponentialFrames(sampleRate * CLUSTER_GAP_SECONDS / lively)
    }

    private fun crackle(amp: Double, pan: Double) {
        if (dice.chance(BIG_CRACK_CHANCE)) {
            crackles.trigger(
                amplitude = amp * dice.logRange(0.6, 1.2) * BIG_CRACK,
                attackSeconds = 0.0,
                decaySeconds = dice.range(0.0003, 0.0011),
                centreHz = dice.logRange(900.0, 2200.0),
                q = dice.range(1.2, 2.5),
                pan = pan + dice.range(-0.1, 0.1),
            )
        } else {
            crackles.trigger(
                amplitude = amp * dice.logRange(0.08, 1.0) * CRACKLE,
                attackSeconds = 0.0,
                decaySeconds = dice.range(0.00006, 0.0004),
                centreHz = dice.logRange(1800.0, 7500.0),
                q = dice.range(0.6, 1.4),
                pan = pan + dice.range(-0.15, 0.15),
            )
        }
    }

    private fun pop() {
        val pan = dice.range(-0.6, 0.6)
        pops.trigger(
            amplitude = dice.logRange(0.5, 1.0) * POP,
            attackSeconds = 0.0,
            decaySeconds = dice.range(0.0002, 0.0005),
            centreHz = dice.logRange(220.0, 750.0),
            q = dice.range(5.0, 11.0),
            pan = pan,
        )
        crackles.trigger(dice.range(0.5, 0.9) * SNAP, 0.0, 0.0004, dice.logRange(1500.0, 3000.0), 0.8, pan)
        if (dice.chance(STEAM_CHANCE)) {
            steam.trigger(
                amplitude = dice.logRange(0.03, 0.08) * STEAM,
                attackSeconds = dice.range(0.02, 0.05),
                decaySeconds = dice.range(0.12, 0.35),
                centreHz = dice.logRange(3500.0, 6000.0),
                q = 0.7,
                pan = pan,
            )
        }
        if (clusterLeft <= 0 && dice.chance(AFTER_POP_CLUSTER_CHANCE)) startCluster(pan)
    }

    private companion object {
        const val CRACKLE_VOICES = 24
        const val POP_VOICES = 4
        const val STEAM_VOICES = 3
        const val WIDTH = 0.75
        const val LOOSE_RATE = 3.0
        const val CLUSTER_GAP_SECONDS = 0.9
        const val BIG_CRACK_CHANCE = 0.12
        const val POP_MIN_GAP_SECONDS = 1.5
        const val POP_MEAN_EXTRA_SECONDS = 5.5
        const val STEAM_CHANCE = 0.35
        const val AFTER_POP_CLUSTER_CHANCE = 0.5

        const val ROAR = 0.28
        const val LAP = 0.36
        const val HISS = 0.015
        const val CRACKLE = 0.55
        const val BIG_CRACK = 0.45
        const val POP = 1.2
        const val SNAP = 0.45
        const val STEAM = 1.0
        const val OUTPUT_GAIN = 2.0
    }
}

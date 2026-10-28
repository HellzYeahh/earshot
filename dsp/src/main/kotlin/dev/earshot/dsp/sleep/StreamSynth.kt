package dev.earshot.dsp.sleep

import kotlin.math.exp
import kotlin.math.ln

/**
 * A babbling brook.
 *
 * Moving water over stones is mostly bubbles: dozens a second, each a short rising "bloop"
 * ([Bubbles], 380 Hz to 1.7 kHz, the bigger ones louder), over a band-passed noise bed whose
 * level flutters quickly (25–90 ms moves) the way churning water does, plus a faint sparkle of
 * splashes higher up. Every few seconds a gurgle: a quick cluster of big, low bubbles in one place.
 */
internal class StreamSynth(seed: Long, sampleRate: Int) : StereoSynth(sampleRate) {
    private val noise = Rng(seed)
    private val dice = Rng(seed, 1)

    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val bedHighL = Svf(sampleRate).highPass(280.0, 0.6)
    private val bedHighR = Svf(sampleRate).highPass(280.0, 0.6)
    private val bedLowL = Svf(sampleRate).lowPass(3000.0, 0.6)
    private val bedLowR = Svf(sampleRate).lowPass(3000.0, 0.6)
    private val sparkleL = Svf(sampleRate).bandPass(4300.0, 0.8)
    private val sparkleR = Svf(sampleRate).bandPass(4300.0, 0.8)

    private val flutterL = Drift(Rng(seed, 2), 0.5, 1.0, 0.025, 0.09, sampleRate)
    private val flutterR = Drift(Rng(seed, 3), 0.5, 1.0, 0.025, 0.09, sampleRate)
    private val glintL = Drift(Rng(seed, 4), 0.0, 1.0, 0.012, 0.05, sampleRate)
    private val glintR = Drift(Rng(seed, 5), 0.0, 1.0, 0.012, 0.05, sampleRate)
    private val flow = Drift(Rng(seed, 6), 0.8, 1.0, 4.0, 12.0, sampleRate)
    private val busy = Drift(Rng(seed, 7), 0.6, 1.4, 1.0, 4.0, sampleRate)
    private val bubbles = Bubbles(BUBBLE_VOICES, sampleRate)

    private var busyNow = busy.value
    private var bubbleWait = 1
    private var gurgleWait = (dice.range(1.0, 4.0) * sampleRate).toInt()
    private var gurgleLeft = 0
    private var gurgleNext = 0
    private var gurglePan = 0.0

    init {
        outputGain = OUTPUT_GAIN
    }

    override fun control() {
        busyNow = busy.nextControl()
    }

    override fun frame() {
        val wl = noise.bipolar()
        val wr = noise.bipolar()
        val f = flow.next()
        val gl = glintL.next()
        val gr = glintR.next()
        var l = bedLowL.process(bedHighL.process(pinkL.process(wl))) * BED * flutterL.next() * f
        var r = bedLowR.process(bedHighR.process(pinkR.process(wr))) * BED * flutterR.next() * f
        l += sparkleL.process(wl) * SPARKLE * gl * gl
        r += sparkleR.process(wr) * SPARKLE * gr * gr

        if (--bubbleWait <= 0) {
            bubble()
            bubbleWait = dice.exponentialFrames(sampleRate / (BUBBLE_RATE * busyNow))
        }
        if (gurgleLeft > 0) {
            if (--gurgleNext <= 0) {
                gurgleBubble()
                gurgleLeft--
                gurgleNext = (dice.range(0.02, 0.08) * sampleRate).toInt()
            }
        } else if (--gurgleWait <= 0) {
            gurgleLeft = 3 + dice.nextInt(5)
            gurglePan = dice.range(-0.6, 0.6)
            gurgleNext = 1
            gurgleWait = (dice.range(2.5, 8.0) * sampleRate).toInt()
        }
        bubbles.tick()
        left = l + bubbles.left
        right = r + bubbles.right
    }

    private fun bubble() {
        val f = dice.logRange(380.0, 1700.0)
        // Bigger bubbles (lower) carry more energy; a gentle tilt keeps the high ones from glittering.
        val size = exp(SIZE_TILT * ln(600.0 / f))
        bubbles.trigger(f, BUBBLE * dice.logRange(0.3, 1.0) * size, dice.range(-0.85, 0.85), dice.range(0.08, 0.2))
    }

    private fun gurgleBubble() {
        bubbles.trigger(
            dice.logRange(160.0, 420.0),
            GURGLE * dice.range(0.5, 1.0),
            gurglePan + dice.range(-0.12, 0.12),
            dice.range(0.1, 0.25),
        )
    }

    private companion object {
        const val BUBBLE_VOICES = 32
        const val BUBBLE_RATE = 38.0
        const val SIZE_TILT = 0.35
        const val BED = 0.5
        const val SPARKLE = 0.12
        const val BUBBLE = 0.07
        const val GURGLE = 0.1
        const val OUTPUT_GAIN = 0.97
    }
}

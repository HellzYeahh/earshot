package dev.earshot.dsp.sleep

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A dense, cheap texture of tiny impacts: the countless small drops that make rain sound grainy
 * instead of like static, the fizz of surf foam, the rustle of leaves in a gust.
 *
 * Hundreds of events a second are too many for a voice pool, so each channel has one shared
 * decaying envelope: every event adds a random (log-uniform) amount to it, white noise is
 * multiplied by it and the result goes through one band-pass. The events overlap freely.
 * Events are a per-sample coin toss rather than drawn intervals, so [rate] (events per second,
 * per channel) can follow a gust or a wave crest at once, even up from zero.
 */
internal class Spatter(
    private val sampleRate: Int,
    private val rng: Rng,
    centreHz: Double,
    q: Double,
    decaySeconds: Double,
    private val quietest: Double,
    loudest: Double,
) {
    var rate = 0.0
        set(value) {
            field = value
            chance = value.coerceAtLeast(0.0) / sampleRate
        }

    private var chance = 0.0
    private val decay = exp(-1.0 / (decaySeconds * sampleRate))
    private val logSpan = ln(loudest / quietest)
    private val normalise = min(MAX_NORMALISE, sqrt(sampleRate / (PI * centreHz / q)))
    private val filterL = Svf(sampleRate).bandPass(centreHz, q)
    private val filterR = Svf(sampleRate).bandPass(centreHz, q)
    private var envelopeL = 0.0
    private var envelopeR = 0.0

    var left = 0.0
        private set
    var right = 0.0
        private set

    fun tick() {
        if (rng.nextDouble() < chance) envelopeL += amplitude()
        if (rng.nextDouble() < chance) envelopeR += amplitude()
        envelopeL *= decay
        envelopeR *= decay
        // Between waves or in a lull there may be no events for seconds: without a floor the
        // envelope, then the filters, would sink into denormal floats, which are very slow on
        // some CPUs. The tiny constant keeps the band-pass states clear of them (it is DC, which
        // a band-pass blocks).
        if (envelopeL < FLOOR) envelopeL = 0.0
        if (envelopeR < FLOOR) envelopeR = 0.0
        left = filterL.process(rng.bipolar() * envelopeL + ANTI_DENORMAL)
        right = filterR.process(rng.bipolar() * envelopeR + ANTI_DENORMAL)
    }

    private fun amplitude(): Double = normalise * quietest * exp(logSpan * rng.nextDouble())

    private companion object {
        const val MAX_NORMALISE = 30.0
        const val FLOOR = 1e-9
        const val ANTI_DENORMAL = 1e-20
    }
}

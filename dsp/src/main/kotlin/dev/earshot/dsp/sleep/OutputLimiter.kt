package dev.earshot.dsp.sleep

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * The mixer's last stage: a stereo-linked peak limiter followed by a soft saturator.
 *
 * Three loud layers at full gain add up well past full scale. The limiter turns the whole mix down
 * smoothly (1.5 ms attack, 350 ms release) when its peaks pass [THRESHOLD], linked across both
 * channels so the image does not lean; whatever slips through the attack is bent under the
 * [CEILING] by [SoftClip] instead of being hard-clipped (which on noise sounds like crackling).
 * At sensible levels neither stage does anything: a single layer passes untouched.
 */
internal class OutputLimiter(sampleRate: Int) {
    private val attack = 1.0 - exp(-1.0 / (ATTACK_SECONDS * sampleRate))
    private val release = 1.0 - exp(-1.0 / (RELEASE_SECONDS * sampleRate))
    private var envelope = 0.0

    var left = 0.0
        private set
    var right = 0.0
        private set

    fun process(l: Double, r: Double) {
        val peak = max(abs(l), abs(r))
        envelope += (peak - envelope) * (if (peak > envelope) attack else release)
        // After a fade to silence the envelope would creep down into slow denormal floats.
        if (envelope < FLOOR) envelope = 0.0
        val gain = if (envelope > THRESHOLD) THRESHOLD / envelope else 1.0
        left = SoftClip.apply(l * gain, KNEE, CEILING)
        right = SoftClip.apply(r * gain, KNEE, CEILING)
    }

    companion object {
        /** No output sample ever reaches this. */
        const val CEILING = 0.98
        private const val THRESHOLD = 0.8
        private const val KNEE = 0.8
        private const val ATTACK_SECONDS = 0.0015
        private const val RELEASE_SECONDS = 0.35
        private const val FLOOR = 1e-9
    }
}

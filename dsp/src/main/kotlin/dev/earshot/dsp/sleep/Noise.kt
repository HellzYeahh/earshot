package dev.earshot.dsp.sleep

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Paul Kellet's "refined" pink filter (music-dsp mailing list, 1999): six one-pole sections plus a
 * direct and a one-sample-delayed tap, within ±0.05 dB of a true −3 dB/octave slope above 9 Hz.
 *
 * It was designed for 44.1 kHz. At 48 kHz its poles sit 9 % higher, which only moves the bottom
 * corner; the slope through the audible range is unchanged (the spectrum test checks it).
 * The raw filter has an RMS gain of 3.05 on white noise (worked out from its transfer function,
 * mostly from the bass sections), so the output is divided by that: pink out is as loud, in RMS,
 * as the white in.
 */
internal class PinkFilter {
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var b3 = 0.0
    private var b4 = 0.0
    private var b5 = 0.0
    private var b6 = 0.0

    fun process(white: Double): Double {
        b0 = 0.99886 * b0 + white * 0.0555179
        b1 = 0.99332 * b1 + white * 0.0750759
        b2 = 0.96900 * b2 + white * 0.1538520
        b3 = 0.86650 * b3 + white * 0.3104856
        b4 = 0.55000 * b4 + white * 0.5329522
        b5 = -0.7616 * b5 - white * 0.0168980
        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
        b6 = white * 0.115926
        return pink * NORMALISE
    }

    private companion object {
        const val NORMALISE = 1.0 / 3.0527
    }
}

/**
 * Brown (red) noise: white noise through a leaky integrator, −6 dB/octave above [cornerHz].
 *
 * A pure integrator wanders off to DC and clips; the leak (pole at `1 − 2π·fc/fs`) flattens the
 * spectrum below the corner instead, and a gentle high-pass under it removes the subsonic rumble
 * that earbuds cannot play anyway but that would eat the headroom. The leak's gain is normalised
 * analytically (`√(1 − a²)`), so the output RMS is about that of the white input.
 */
internal class BrownFilter(sampleRate: Int, cornerHz: Double = 25.0) {
    private val a = 1.0 - 2.0 * PI * cornerHz / sampleRate
    private val normalise = sqrt(1.0 - a * a)
    private val subsonic = OnePole(sampleRate, SUBSONIC_HZ)
    private var y = 0.0

    fun process(white: Double): Double {
        y = a * y + white
        return subsonic.highPass(y * normalise)
    }

    private companion object {
        const val SUBSONIC_HZ = 18.0
    }
}

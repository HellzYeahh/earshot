package dev.earshot.dsp.sleep

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The noise colours have the slopes their names promise. Spectral density (power per hertz) is
 * compared in two bands four octaves apart: white stays level, pink falls 3 dB per octave (−12 dB)
 * and brown 6 dB per octave (−24 dB).
 */
class NoiseSpectrumTest {
    @Test
    fun `white noise is flat`() {
        assertSlope(SleepSound.WHITE_NOISE, expectedDb = 0.0, toleranceDb = 1.0)
    }

    @Test
    fun `pink noise falls 3 dB per octave`() {
        assertSlope(SleepSound.PINK_NOISE, expectedDb = -12.0, toleranceDb = 1.5)
    }

    @Test
    fun `brown noise falls 6 dB per octave`() {
        assertSlope(SleepSound.BROWN_NOISE, expectedDb = -24.0, toleranceDb = 2.0)
    }

    private fun assertSlope(sound: SleepSound, expectedDb: Double, toleranceDb: Double) {
        val audio = Signals.render(SleepSounds.create(sound, 99L), 20.0)
        for (ch in 0..1) {
            val spectrum = Signals.powerSpectrum(Signals.channel(audio, ch))
            for ((low, high) in listOf(400.0 to 6400.0, 100.0 to 1600.0)) {
                val a = Signals.bandDensity(spectrum, low, low * 1.25)
                val b = Signals.bandDensity(spectrum, high, high * 1.25)
                val db = 10 * log10(b / a)
                println("$sound ch$ch ${low.toInt()} Hz -> ${high.toInt()} Hz: %.2f dB".format(db))
                assertTrue(abs(db - expectedDb) <= toleranceDb, "$sound: ${low.toInt()} Hz to ${high.toInt()} Hz is $db dB, expected $expectedDb")
            }
        }
    }
}

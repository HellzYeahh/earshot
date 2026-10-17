package dev.earshot.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiquadTest {
    private val fs = 48000.0

    private fun assertDb(expected: Double, actual: Double, tol: Double = 0.2, msg: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$msg expected $expected dB, got $actual dB")

    @Test
    fun `peaking filter hits requested gain at f0 for boosts and cuts`() {
        for (gain in listOf(-15.0, -6.0, -1.0, 1.0, 3.0, 6.0, 12.0, 15.0)) {
            for (f0 in listOf(31.0, 100.0, 1000.0, 4000.0, 12000.0)) {
                val bq = Biquad.peaking(fs, f0, gain, 1.41)
                assertDb(gain, bq.magnitudeDb(f0), msg = "peak f0=$f0 gain=$gain:")
            }
        }
    }

    @Test
    fun `peaking filter is flat far from f0`() {
        val bq = Biquad.peaking(fs, 1000.0, 10.0, 2.0)
        assertDb(0.0, bq.magnitudeDb(30.0), tol = 0.3)
        assertDb(0.0, bq.magnitudeDb(18000.0), tol = 0.3)
    }

    @Test
    fun `low shelf reaches full gain below the corner and half gain at it`() {
        for (gain in listOf(-9.0, -3.0, 4.0, 10.0)) {
            val bq = Biquad.lowShelf(fs, 200.0, gain, 0.707)
            assertDb(gain, bq.magnitudeDb(10.0), msg = "low shelf plateau gain=$gain:")
            assertDb(gain / 2.0, bq.magnitudeDb(200.0), msg = "low shelf corner gain=$gain:")
            assertDb(0.0, bq.magnitudeDb(10000.0), msg = "low shelf far above gain=$gain:")
        }
    }

    @Test
    fun `high shelf reaches full gain above the corner and half gain at it`() {
        for (gain in listOf(-9.0, -3.0, 4.0, 10.0)) {
            val bq = Biquad.highShelf(fs, 2000.0, gain, 0.707)
            assertDb(gain, bq.magnitudeDb(20000.0), msg = "high shelf plateau gain=$gain:")
            assertDb(gain / 2.0, bq.magnitudeDb(2000.0), msg = "high shelf corner gain=$gain:")
            assertDb(0.0, bq.magnitudeDb(40.0), msg = "high shelf far below gain=$gain:")
        }
    }

    @Test
    fun `zero gain is unity for every type`() {
        for (type in FilterType.entries) {
            val bq = Biquad().configure(type, fs, 1000.0, 0.0, 1.0)
            for (f in listOf(20.0, 500.0, 1000.0, 5000.0, 20000.0)) {
                assertDb(0.0, bq.magnitudeDb(f), tol = 1e-6, msg = "$type at $f:")
            }
        }
    }

    @Test
    fun `configure from band mirrors explicit configure`() {
        val band = ParametricBand(frequencyHz = 500.0, gainDb = -4.0, q = 2.0, type = FilterType.PEAK)
        val a = Biquad().configure(band, fs)
        val b = Biquad.peaking(fs, 500.0, -4.0, 2.0)
        assertEquals(b.b0, a.b0, 1e-12)
        assertEquals(b.a2, a.a2, 1e-12)
    }

    @Test
    fun `frequencies at or above nyquist do not produce NaN`() {
        val bq = Biquad.peaking(44100.0, 22050.0, 6.0, 1.0)
        val v = bq.magnitudeDb(22000.0)
        assertTrue(v.isFinite(), "got $v")
    }
}

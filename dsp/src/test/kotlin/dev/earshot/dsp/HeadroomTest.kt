package dev.earshot.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadroomTest {
    @Test
    fun `flat or cut-only bands need no preamp`() {
        assertEquals(0.0, Headroom.recommendedPreampDb(GraphicEq.bandsFromGains(List(10) { 0.0 })), 0.0)
        assertEquals(0.0, Headroom.recommendedPreampDb(listOf(ParametricBand(1000.0, -6.0, 1.0))), 0.0)
    }

    @Test
    fun `a single boost is cancelled by its own gain`() {
        val p = Headroom.recommendedPreampDb(listOf(ParametricBand(1000.0, 6.0, 1.0)))
        assertTrue(abs(p + 6.0) < 0.2, "got $p")
        assertTrue(p <= -6.0, "must never under-compensate: $p")
    }

    @Test
    fun `overlapping boosts add up`() {
        val bands = listOf(ParametricBand(800.0, 6.0, 0.7), ParametricBand(1250.0, 6.0, 0.7))
        val p = Headroom.recommendedPreampDb(bands)
        assertTrue(p < -8.0, "two overlapping +6 dB bands peak above +8 dB, preamp was $p")
    }

    @Test
    fun `preamp is rounded to a tenth`() {
        val p = Headroom.recommendedPreampDb(listOf(ParametricBand(1000.0, 3.33, 1.0)))
        assertEquals(p, Math.round(p * 10.0) / 10.0, 1e-9)
    }

    @Test
    fun `boost cap depends on the limiter`() {
        assertEquals(TuningSettings.MAX_BOOST_DB, Headroom.effectiveBoostCapDb(limiterEnabled = true), 0.0)
        assertEquals(3.0, Headroom.effectiveBoostCapDb(limiterEnabled = false), 0.0)
        assertEquals(3.0, Headroom.clampBoost(9.0, limiterEnabled = false), 0.0)
        assertEquals(9.0, Headroom.clampBoost(9.0, limiterEnabled = true), 0.0)
        assertEquals(TuningSettings.MAX_BOOST_DB, Headroom.clampBoost(40.0, limiterEnabled = true), 0.0)
        assertEquals(0.0, Headroom.clampBoost(-2.0, limiterEnabled = true), 0.0)
    }

    @Test
    fun `effective preamp is capped at the recommendation only while the limiter is off`() {
        val bands = listOf(ParametricBand(1000.0, 6.0, 1.0))
        val recommended = Headroom.recommendedPreampDb(bands)
        // Limiter on: the user's value stands, even at 0 dB.
        assertEquals(0.0, Headroom.effectivePreampDb(0.0, bands, limiterEnabled = true), 0.0)
        // Limiter off: pulled down to what cancels the band peak...
        assertEquals(recommended, Headroom.effectivePreampDb(0.0, bands, limiterEnabled = false), 0.0)
        // ...but a user who already went lower keeps their value.
        assertEquals(-12.0, Headroom.effectivePreampDb(-12.0, bands, limiterEnabled = false), 0.0)
        // Cut-only curves need nothing, so the user's value is untouched either way.
        val cuts = listOf(ParametricBand(1000.0, -6.0, 1.0))
        assertEquals(-1.5, Headroom.effectivePreampDb(-1.5, cuts, limiterEnabled = false), 0.0)
        // Full chain: bands + effective preamp never overshoot without the limiter.
        assertEquals(0.0, Headroom.worstCaseOvershootDb(bands, Headroom.effectivePreampDb(0.0, bands, false), 0.0), 0.0)
    }

    @Test
    fun `worst case overshoot combines bands preamp and boost`() {
        val bands = listOf(ParametricBand(1000.0, 6.0, 1.0))
        assertEquals(0.0, Headroom.worstCaseOvershootDb(bands, preampDb = -6.0, boostDb = 0.0), 0.2)
        assertTrue(abs(Headroom.worstCaseOvershootDb(bands, preampDb = 0.0, boostDb = 4.0) - 10.0) < 0.2)
    }
}

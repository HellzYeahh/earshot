package dev.earshot.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicsProcessingPlanTest {
    @Test
    fun `band edges are log spaced from 20 Hz to 20 kHz`() {
        val edges = DynamicsProcessingPlan.bandEdges(32)
        assertEquals(33, edges.size)
        assertEquals(20.0, edges.first(), 1e-9)
        assertEquals(20000.0, edges.last(), 1e-6)
        val ratio = edges[1] / edges[0]
        for (i in 1 until edges.size - 1) assertEquals(ratio, edges[i + 1] / edges[i], 1e-9)
    }

    @Test
    fun `flat bands plan to zero gains and input gain carries preamp plus boost`() {
        val plan = DynamicsProcessingPlan.plan(
            bands = GraphicEq.bandsFromGains(List(10) { 0.0 }),
            preampDb = -2.0,
            boostDb = 5.0,
            limiterEnabled = true,
        )
        assertEquals(32, plan.bandCount)
        plan.bands.forEach { assertEquals(0.0, it.gainDb, 1e-6) }
        assertEquals(3.0, plan.inputGainDb, 1e-9)
        assertTrue(plan.limiter.enabled)
        assertEquals(20000.0, plan.bands.last().cutoffHz, 1e-6)
    }

    @Test
    fun `band gains track the parametric response at each band centre`() {
        val bands = listOf(
            ParametricBand(100.0, 5.0, 0.7, FilterType.LOW_SHELF),
            ParametricBand(1000.0, -4.0, 1.0),
            ParametricBand(8000.0, 3.0, 0.7, FilterType.HIGH_SHELF),
        )
        val plan = DynamicsProcessingPlan.plan(bands, preampDb = -5.0, boostDb = 0.0, limiterEnabled = false)
        val response = EqResponse(bands)
        plan.bands.forEachIndexed { i, b ->
            val centre = DynamicsProcessingPlan.bandCentreHz(i, plan.bandCount)
            assertEquals(response.bandsOnlyDb(centre), b.gainDb, 1e-9, "band $i")
        }
        // and the plan carries the shape: bass up, 1 kHz down, treble up
        val at1k = plan.bands.first { it.cutoffHz >= 1000.0 }
        assertTrue(at1k.gainDb < -2.5, "1 kHz band should be cut, was ${at1k.gainDb}")
        assertTrue(plan.bands.first().gainDb > 4.0, "lowest band should carry the shelf")
        assertTrue(plan.bands.last().gainDb > 2.0, "highest band should carry the shelf")
        assertFalse(plan.limiter.enabled)
        assertEquals(-5.0, plan.inputGainDb, 1e-9)
    }

    @Test
    fun `a broad peak survives the band quantisation within a dB at the band edges`() {
        val bands = listOf(ParametricBand(1000.0, 6.0, 0.7))
        val plan = DynamicsProcessingPlan.plan(bands, 0.0, 0.0, true)
        val response = EqResponse(bands)
        // Compare at each band's *edges* too: the staircase should stay close to the curve.
        // A Q=0.7 peak slopes at ~4 dB/octave, and a 32-band plan's half-band is 0.156 octave,
        // so the worst edge error is ~0.6 dB; that is the documented approximation cost.
        val edges = DynamicsProcessingPlan.bandEdges(plan.bandCount)
        plan.bands.forEachIndexed { i, b ->
            val atLower = response.bandsOnlyDb(edges[i])
            val atUpper = response.bandsOnlyDb(edges[i + 1])
            assertTrue(abs(b.gainDb - atLower) < 1.0 && abs(b.gainDb - atUpper) < 1.0, "band $i drifts")
        }
        // The peak itself is preserved.
        assertTrue(plan.bands.maxOf { it.gainDb } > 5.5)
    }

    @Test
    fun `other band counts and sample rates are honoured`() {
        val plan = DynamicsProcessingPlan.plan(emptyList(), 0.0, 0.0, true, bandCount = 8, sampleRate = 44100)
        assertEquals(8, plan.bandCount)
        assertEquals(44100, plan.sampleRate)
    }

    @Test
    fun `limiter defaults are the documented protective values`() {
        val l = DynamicsProcessingPlan.plan(emptyList(), 0.0, 0.0, true).limiter
        assertEquals(1.0, l.attackMs, 1e-9)
        assertEquals(60.0, l.releaseMs, 1e-9)
        assertEquals(50.0, l.ratio, 1e-9)
        assertEquals(-2.0, l.thresholdDb, 1e-9)
        assertEquals(0.0, l.postGainDb, 1e-9)
    }
}

package dev.earshot.ui.sound

import dev.earshot.dsp.BuiltInPresets
import dev.earshot.dsp.EqResponse
import dev.earshot.dsp.FilterType
import dev.earshot.dsp.Headroom
import dev.earshot.dsp.ParametricBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The Pro EQ's numbers moved out of composition into [EqCurveData] (one sweep, off the main
 * thread). They must stay exactly what the screen showed before: [Headroom]'s preamp and
 * overshoot, and the engine's [EqResponse] for the curve and its dots.
 */
class EqCurveDataTest {

    private val bandSets: List<List<ParametricBand>> = BuiltInPresets.all.map { it.bands } + listOf(
        emptyList(),
        // Two boosts an octave apart add up between them.
        listOf(ParametricBand(1000.0, 6.0), ParametricBand(2000.0, 6.0)),
        // Cuts only: the peak stays at or below 0 dB, so no preamp is recommended.
        listOf(ParametricBand(100.0, -8.0, type = FilterType.LOW_SHELF), ParametricBand(4000.0, -3.0, q = 3.0)),
        // A switched-off band counts for nothing and gets no dot.
        listOf(ParametricBand(3000.0, 9.0, enabled = false), ParametricBand(60.0, 4.5, q = 0.7, type = FilterType.LOW_SHELF)),
        listOf(ParametricBand(12000.0, 12.0, q = 2.0, type = FilterType.HIGH_SHELF), ParametricBand(250.0, -3.5, q = 4.0), ParametricBand(31.0, 15.0, q = 0.1)),
    )

    @Test
    fun `the recommended preamp and the overshoot are Headroom's`() {
        for (bands in bandSets) {
            for (preamp in listOf(0.0, -4.0, -6.3)) {
                val curve = EqCurveData(bands, preamp)
                assertEquals(Headroom.recommendedPreampDb(bands), curve.recommendedPreampDb, "recommended preamp for $bands")
                for (boost in listOf(0.0, 3.0, 7.5)) {
                    assertEquals(
                        Headroom.worstCaseOvershootDb(bands, preamp, boost),
                        curve.overshootDb(preamp, boost),
                        "overshoot for $bands at $preamp dB preamp, $boost dB boost",
                    )
                }
            }
        }
    }

    @Test
    fun `the curve and its dots are the engine's response`() {
        for (bands in bandSets) {
            val curve = EqCurveData(bands, preampDb = -2.0)
            val expected = EqResponse(bands, -2.0).sample(EqCurveData.CURVE_POINTS, EqCurve.MIN_HZ, EqCurve.MAX_HZ)
            assertEquals(expected.map { it.frequencyHz to it.gainDb }, curve.samples)
            assertEquals(
                bands.filter { it.enabled }.map { it.frequencyHz to EqCurve.magnitudeDb(bands, -2.0, it.frequencyHz) },
                curve.markers,
            )
        }
    }

    @Test
    fun `equal inputs give equal curves, so a repeat is dropped`() {
        val bands = BuiltInPresets.all[1].bands
        // A copy of the list, as a new emission of the same bands would be.
        assertEquals(EqCurveData(bands, -3.0), EqCurveData(bands.toList(), -3.0))
        assertNotEquals(EqCurveData(bands, -3.0), EqCurveData(bands, -3.5))
        assertNotEquals(EqCurveData(bands, -3.0), EqCurveData(bands.withBand(0, bands[0].copy(gainDb = bands[0].gainDb + 1.0))!!, -3.0))
    }
}

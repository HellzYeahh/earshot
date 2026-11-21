package dev.earshot.dsp

import kotlin.test.Test
import kotlin.test.assertSame

/** Which bands each ear plays, including the older settings that have no per-ear fields. */
class TuningSettingsTest {

    private val shared = GraphicEq.bandsFromGains(List(GraphicEq.BAND_COUNT) { 0.0 })
    private val left = listOf(ParametricBand(200.0, 3.0))
    private val right = listOf(ParametricBand(2000.0, -3.0))

    @Test
    fun `linked settings give both ears the shared bands`() {
        val s = TuningSettings(bands = shared)
        assertSame(shared, s.bandsFor(Ear.LEFT))
        assertSame(shared, s.bandsFor(Ear.RIGHT))
        assertSame(shared, s.bandsFor(null))
    }

    @Test
    fun `per-ear settings give each ear its own bands`() {
        val s = TuningSettings(bands = shared, perEarEnabled = true, leftBands = left, rightBands = right)
        assertSame(left, s.bandsFor(Ear.LEFT))
        assertSame(right, s.bandsFor(Ear.RIGHT))
        assertSame(shared, s.bandsFor(null), "the shared curve still drives the editor when both are selected")
    }

    @Test
    fun `an ear without a saved curve falls back to the shared one`() {
        val s = TuningSettings(bands = shared, perEarEnabled = true)
        assertSame(shared, s.bandsFor(Ear.LEFT))
        assertSame(shared, s.bandsFor(Ear.RIGHT))
    }

    @Test
    fun `per-ear curves are ignored while linked`() {
        val s = TuningSettings(bands = shared, perEarEnabled = false, leftBands = left, rightBands = right)
        assertSame(shared, s.bandsFor(Ear.LEFT))
        assertSame(shared, s.bandsFor(Ear.RIGHT))
    }
}

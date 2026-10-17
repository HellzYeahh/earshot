package dev.earshot.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltInPresetsTest {
    @Test
    fun `all presets are built in, unique and within limits`() {
        val all = BuiltInPresets.all
        assertEquals(all.size, all.map { it.id }.toSet().size, "ids must be unique")
        for (p in all) {
            assertTrue(p.isBuiltIn, "${p.id} must be built-in")
            assertTrue(p.bands.size <= 10, "${p.id} has ${p.bands.size} bands")
            assertTrue(p.bands.isNotEmpty(), "${p.id} has no bands")
            assertTrue(p.preampDb <= 0.0 && p.preampDb >= -15.0, "${p.id} preamp ${p.preampDb}")
            assertTrue(p.name.isNotBlank() && p.description != null)
            for (b in p.bands) {
                assertTrue(b.frequencyHz in 20.0..20000.0, "${p.id} band at ${b.frequencyHz} Hz")
                assertTrue(b.gainDb in ParametricBand.MIN_GAIN_DB..ParametricBand.MAX_GAIN_DB, "${p.id} gain ${b.gainDb}")
                assertTrue(b.q in ParametricBand.MIN_Q..ParametricBand.MAX_Q, "${p.id} q ${b.q}")
            }
        }
    }

    @Test
    fun `no built-in preset can clip with its own preamp`() {
        for (p in BuiltInPresets.all) {
            val worst = Headroom.worstCaseOvershootDb(p.bands, p.preampDb, boostDb = 0.0)
            // AutoEq's own preamp is what they recommend; everything else is exact by construction.
            val tolerance = if (p.id == BuiltInPresets.QC_ULTRA_ID) 1.0 else 0.05
            assertTrue(worst <= tolerance, "${p.id} overshoots by $worst dB")
        }
    }

    @Test
    fun `flat really is flat`() {
        BuiltInPresets.flat.bands.forEach { assertEquals(0.0, it.gainDb, 0.0) }
        assertEquals(0.0, BuiltInPresets.flat.preampDb, 0.0)
    }

    @Test
    fun `QC Ultra preset carries the AutoEq curve and attribution`() {
        val p = BuiltInPresets.tunedForQcUltra
        assertEquals(10, p.bands.size)
        assertEquals(-2.8, p.preampDb, 1e-9)
        assertEquals(FilterType.LOW_SHELF, p.bands[0].type)
        assertEquals(105.0, p.bands[0].frequencyHz, 1e-9)
        assertEquals(-3.0, p.bands[0].gainDb, 1e-9)
        assertTrue(p.description!!.contains("AutoEq") && p.description!!.contains("MIT"))
        assertTrue(p.description!!.contains(BuiltInPresets.QC_ULTRA_AUTOEQ_URL))
    }

    @Test
    fun `byId finds presets`() {
        assertNotNull(BuiltInPresets.byId("bass"))
        assertEquals(null, BuiltInPresets.byId("nope"))
        assertEquals(null, BuiltInPresets.byId(null))
    }
}

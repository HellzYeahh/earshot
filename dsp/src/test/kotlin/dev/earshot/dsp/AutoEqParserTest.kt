package dev.earshot.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoEqParserTest {
    private val sample = """
        Preamp: -6.4 dB
        Filter 1: ON PK Fc 105 Hz Gain -3.2 dB Q 0.70
        Filter 2: ON LSC Fc 105 Hz Gain -3.0 dB Q 0.70
        Filter 3: ON HSC Fc 10000 Hz Gain 2.7 dB Q 0.70
        Filter 4: OFF PK Fc 2373 Hz Gain -2.8 dB Q 5.58
    """.trimIndent()

    @Test
    fun `parses preamp types gains q and on-off`() {
        val p = AutoEqParser.parse(sample, id = "x", name = "X")
        assertEquals(-6.4, p.preampDb, 1e-9)
        assertEquals(4, p.bands.size)
        assertEquals(ParametricBand(105.0, -3.2, 0.70, FilterType.PEAK, true), p.bands[0])
        assertEquals(FilterType.LOW_SHELF, p.bands[1].type)
        assertEquals(FilterType.HIGH_SHELF, p.bands[2].type)
        assertEquals(10000.0, p.bands[2].frequencyHz, 1e-9)
        assertEquals(2.7, p.bands[2].gainDb, 1e-9)
        assertFalse(p.bands[3].enabled)
        assertEquals(5.58, p.bands[3].q, 1e-9)
    }

    @Test
    fun `export then parse round-trips`() {
        val original = AutoEqParser.parse(sample, "x", "X")
        val text = AutoEqParser.export(original)
        val again = AutoEqParser.parse(text, "x", "X")
        assertEquals(original.preampDb, again.preampDb, 1e-9)
        assertEquals(original.bands, again.bands)
    }

    @Test
    fun `export matches AutoEq layout`() {
        val preset = EqPreset("a", "A", listOf(ParametricBand(105.0, -3.2, 0.7)), preampDb = -6.4)
        val lines = AutoEqParser.export(preset).trim().lines()
        assertEquals("Preamp: -6.4 dB", lines[0])
        assertEquals("Filter 1: ON PK Fc 105 Hz Gain -3.2 dB Q 0.70", lines[1])
    }

    @Test
    fun `clamps out-of-range values instead of failing`() {
        val p = AutoEqParser.parse("Filter 1: ON PK Fc 50 Hz Gain -22.0 dB Q 15.0", "x", "X")
        assertEquals(ParametricBand.MIN_GAIN_DB, p.bands[0].gainDb, 0.0)
        assertEquals(ParametricBand.MAX_Q, p.bands[0].q, 0.0)
        assertEquals(0.0, p.preampDb, 0.0)
    }

    @Test
    fun `ignores unknown lines and tolerates CRLF`() {
        val text = "# comment\r\nPreamp: -1.0 dB\r\nFilter 1: ON PK Fc 1000 Hz Gain 2.0 dB Q 1.00\r\n\r\n"
        val p = AutoEqParser.parse(text, "x", "X")
        assertEquals(1, p.bands.size)
        assertTrue(p.bands[0].enabled)
    }

    @Test
    fun `rejects text without filters`() {
        assertFailsWith<AutoEqParser.ParseException> { AutoEqParser.parse("hello\nworld", "x", "X") }
    }
}

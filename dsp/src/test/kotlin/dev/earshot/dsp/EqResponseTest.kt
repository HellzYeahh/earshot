package dev.earshot.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqResponseTest {
    @Test
    fun `flat bands give zero dB everywhere`() {
        val response = EqResponse(GraphicEq.bandsFromGains(List(10) { 0.0 }))
        for (p in response.sample(200)) {
            assertEquals(0.0, p.gainDb, 1e-6, "at ${p.frequencyHz} Hz")
        }
    }

    @Test
    fun `preamp offsets the whole curve`() {
        val response = EqResponse(GraphicEq.bandsFromGains(List(10) { 0.0 }), preampDb = -4.5)
        assertEquals(-4.5, response.magnitudeDb(1000.0), 1e-6)
        assertEquals(0.0, response.bandsOnlyDb(1000.0), 1e-6)
    }

    @Test
    fun `bands add in dB`() {
        val b1 = ParametricBand(1000.0, 3.0, 1.0)
        val b2 = ParametricBand(1000.0, 2.0, 1.0)
        val combined = EqResponse(listOf(b1, b2)).magnitudeDb(1000.0)
        val separate = EqResponse(listOf(b1)).magnitudeDb(1000.0) + EqResponse(listOf(b2)).magnitudeDb(1000.0)
        assertEquals(separate, combined, 1e-9)
        assertTrue(abs(combined - 5.0) < 0.2)
    }

    @Test
    fun `disabled bands are ignored`() {
        val bands = listOf(ParametricBand(1000.0, 12.0, 1.0, enabled = false))
        assertEquals(0.0, EqResponse(bands).magnitudeDb(1000.0), 1e-9)
        assertEquals(0, EqResponse(bands).bandCount)
    }

    @Test
    fun `sample is log spaced and inclusive`() {
        val pts = EqResponse(emptyList()).sample(nPoints = 4, fMin = 20.0, fMax = 20000.0)
        assertEquals(4, pts.size)
        assertEquals(20.0, pts.first().frequencyHz, 1e-9)
        assertEquals(20000.0, pts.last().frequencyHz, 1e-6)
        // ratio between neighbours constant → log spacing
        val r1 = pts[1].frequencyHz / pts[0].frequencyHz
        val r2 = pts[2].frequencyHz / pts[1].frequencyHz
        assertEquals(r1, r2, 1e-9)
        assertEquals(10.0, r1, 1e-9)
    }

    @Test
    fun `peak of bands only ignores preamp`() {
        val bands = listOf(ParametricBand(1000.0, 6.0, 1.0))
        val r = EqResponse(bands, preampDb = -6.0)
        assertTrue(abs(r.peakBandsOnlyDb() - 6.0) < 0.2)
    }
}

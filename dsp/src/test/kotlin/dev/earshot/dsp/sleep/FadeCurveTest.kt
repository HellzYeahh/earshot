package dev.earshot.dsp.sleep

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FadeCurveTest {
    @Test
    fun `fade in runs from 0 to 1 and clamps`() {
        assertEquals(0f, FadeCurve.fadeIn(0, 10_000))
        assertEquals(1f, FadeCurve.fadeIn(10_000, 10_000))
        assertEquals(0f, FadeCurve.fadeIn(-500, 10_000))
        assertEquals(1f, FadeCurve.fadeIn(50_000, 10_000))
        // Equal power: half-way is 1/√2.
        assertTrue(abs(FadeCurve.fadeIn(5_000, 10_000) - 0.70710677f) < 1e-5f)
    }

    @Test
    fun `fade out runs from 1 to 0, starts slowly and clamps`() {
        assertEquals(1f, FadeCurve.fadeOut(0, 600_000))
        assertEquals(0f, FadeCurve.fadeOut(600_000, 600_000))
        assertEquals(1f, FadeCurve.fadeOut(-1, 600_000))
        assertEquals(0f, FadeCurve.fadeOut(900_000, 600_000))
        assertTrue(abs(FadeCurve.fadeOut(300_000, 600_000) - 0.5f) < 1e-6f)
        // Slow at first: after a tenth of the time it has lost less than 3 % (−0.25 dB).
        assertTrue(FadeCurve.fadeOut(60_000, 600_000) > 0.97f)
    }

    @Test
    fun `both curves are monotonic`() {
        val duration = 30 * 60_000L
        var lastIn = -1f
        var lastOut = 2f
        for (t in -1_000L..duration + 1_000L step 997L) {
            val i = FadeCurve.fadeIn(t, duration)
            val o = FadeCurve.fadeOut(t, duration)
            assertTrue(i >= lastIn, "fadeIn went down at $t")
            assertTrue(o <= lastOut, "fadeOut went up at $t")
            assertTrue(i in 0f..1f && o in 0f..1f)
            lastIn = i
            lastOut = o
        }
    }

    @Test
    fun `a zero or negative duration is already finished`() {
        assertEquals(1f, FadeCurve.fadeIn(0, 0))
        assertEquals(0f, FadeCurve.fadeOut(0, 0))
        assertEquals(1f, FadeCurve.fadeIn(5, -10))
        assertEquals(0f, FadeCurve.fadeOut(5, -10))
    }
}

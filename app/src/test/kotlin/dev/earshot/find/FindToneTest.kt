package dev.earshot.find

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The find-my-earbuds chirp: one side really silent, the ramp, no clipping, no clicks. */
class FindToneTest {

    private fun left(pcm: ShortArray) = pcm.filterIndexed { i, _ -> i % 2 == 0 }
    private fun right(pcm: ShortArray) = pcm.filterIndexed { i, _ -> i % 2 == 1 }

    @Test fun `left only leaves the right channel silent`() {
        val pcm = FindTone.period(FindSide.LEFT, gain = 1.0)
        assertTrue(right(pcm).all { it.toInt() == 0 })
        assertTrue(left(pcm).any { abs(it.toInt()) > 20_000 }, "the ringing side is loud")
    }

    @Test fun `right only leaves the left channel silent`() {
        val pcm = FindTone.period(FindSide.RIGHT, gain = 1.0)
        assertTrue(left(pcm).all { it.toInt() == 0 })
        assertTrue(right(pcm).any { abs(it.toInt()) > 20_000 })
    }

    @Test fun `both plays the same samples on each side`() {
        val pcm = FindTone.period(FindSide.BOTH, gain = 0.5)
        assertEquals(left(pcm), right(pcm))
    }

    @Test fun `a period is note, gap, note and rest at 48 kHz`() {
        val pcm = FindTone.period(FindSide.BOTH, gain = 1.0)
        assertEquals(FindTone.SAMPLE_RATE * FindTone.PERIOD_MS / 1000 * 2, pcm.size)
        // The rest at the end is silent.
        assertTrue(pcm.takeLast(FindTone.SAMPLE_RATE * 300 / 1000 * 2).all { it.toInt() == 0 })
    }

    @Test fun `samples stay below full scale and the notes fade in`() {
        val pcm = FindTone.period(FindSide.BOTH, gain = 1.0)
        assertTrue(pcm.all { abs(it.toInt()) <= (Short.MAX_VALUE * 0.86).toInt() })
        assertTrue(abs(pcm[0].toInt()) < 100 && abs(pcm[2].toInt()) < 2_000, "no click at the first sample")
    }

    @Test fun `the gain ramps from low to full and stops there`() {
        assertEquals(FindTone.START_GAIN, FindTone.gainAt(0), 1e-9)
        assertTrue(FindTone.gainAt(3_000) in 0.55..0.65)
        assertEquals(1.0, FindTone.gainAt(FindTone.RAMP_MS), 1e-9)
        assertEquals(1.0, FindTone.gainAt(60_000), 1e-9)
        assertEquals(FindTone.START_GAIN, FindTone.gainAt(-5), 1e-9)
    }
}

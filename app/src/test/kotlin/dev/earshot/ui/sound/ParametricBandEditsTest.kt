package dev.earshot.ui.sound

import dev.earshot.dsp.ParametricBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The band cards' callbacks now read the newest list when they fire (so the cards whose band did
 * not change can skip recomposing). The list edits behind them must keep the other bands as the
 * same instances, and do nothing when the band is already gone.
 */
class ParametricBandEditsTest {

    private val bands = listOf(
        ParametricBand(100.0, 3.0),
        ParametricBand(1000.0, -2.0),
        ParametricBand(8000.0, 4.0),
    )

    @Test
    fun `changing one band keeps the others as they were`() {
        val updated = ParametricBand(1200.0, -2.0)
        val next = bands.withBand(1, updated)!!

        assertEquals(listOf(bands[0], updated, bands[2]), next)
        // Same instances, so Compose sees the other cards' bands as unchanged.
        assertSame(bands[0], next[0])
        assertSame(bands[2], next[2])
        assertEquals(1000.0, bands[1].frequencyHz, "the original list is left alone")
    }

    @Test
    fun `removing a band keeps the rest in order`() {
        val next = bands.withoutBand(0)!!
        assertEquals(listOf(bands[1], bands[2]), next)
        assertSame(bands[1], next[0])
    }

    @Test
    fun `a band that is gone already changes nothing`() {
        // A late callback from a card that a removal just took away.
        assertNull(bands.withBand(3, ParametricBand(500.0, 1.0)))
        assertNull(bands.withoutBand(3))
        assertNull(bands.withoutBand(-1))
    }
}

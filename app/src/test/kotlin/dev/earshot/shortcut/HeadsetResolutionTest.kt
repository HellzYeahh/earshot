package dev.earshot.shortcut

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadsetResolutionTest {
    private val qc = "AA:BB:CC:DD:00:0A"
    private val buds = "11:22:33:44:55:66"
    private val saved = "AA:BB:CC:DD:00:0A"

    @Test
    fun `a device in the intent wins`() {
        val r = HeadsetResolution.resolve(intentAddress = buds, activeHfpAddress = qc, connectedHfp = listOf(qc), savedAddress = saved)
        assertEquals(ResolvedHeadset(buds, HeadsetSource.INTENT), r)
    }

    @Test
    fun `then the active HFP device, which the stack made the presser`() {
        val r = HeadsetResolution.resolve(intentAddress = null, activeHfpAddress = buds.lowercase(), connectedHfp = listOf(qc, buds), savedAddress = saved)
        assertEquals(ResolvedHeadset(buds, HeadsetSource.ACTIVE_HFP), r)
    }

    @Test
    fun `then the only connected HFP device`() {
        val r = HeadsetResolution.resolve(intentAddress = null, activeHfpAddress = null, connectedHfp = listOf(buds), savedAddress = saved)
        assertEquals(ResolvedHeadset(buds, HeadsetSource.HFP_ONLY), r)
    }

    @Test
    fun `several connected prefer the saved headphones, else the first`() {
        assertEquals(
            ResolvedHeadset(qc, HeadsetSource.HFP_SAVED),
            HeadsetResolution.resolve(null, null, listOf(buds, qc), saved),
        )
        assertEquals(
            ResolvedHeadset(buds, HeadsetSource.HFP_FIRST),
            HeadsetResolution.resolve(null, null, listOf(buds, "22:22:22:22:22:22"), saved),
        )
    }

    @Test
    fun `nothing connected falls back to the saved headphones`() {
        assertEquals(ResolvedHeadset(saved, HeadsetSource.SAVED), HeadsetResolution.resolve(null, null, emptyList(), saved))
        assertNull(HeadsetResolution.resolve(null, null, emptyList(), null))
    }

    @Test
    fun `junk addresses are skipped at every step`() {
        val r = HeadsetResolution.resolve(intentAddress = "not-a-mac", activeHfpAddress = "", connectedHfp = listOf("00:00:00:00:00:00"), savedAddress = saved)
        assertEquals(ResolvedHeadset(saved, HeadsetSource.SAVED), r)
        assertTrue(HeadsetResolution.isAddress("aa:bb:cc:dd:ee:ff"))
        assertFalse(HeadsetResolution.isAddress("00:00:00:00:00:00"))
        assertFalse(HeadsetResolution.isAddress(null))
    }
}

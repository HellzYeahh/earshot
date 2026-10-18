package dev.earshot.bt

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** LE Audio identities ("LE-Bose …"), their classic twins, the PSM value and the candidate order. */
class LeAudioRouteTest {

    private val bonded = mapOf(
        "AA:AA:AA:00:00:01" to "Bose QC Ultra Earbuds",
        "BB:BB:BB:00:00:02" to "LE-Bose QC Ultra Earbuds",
        "CC:CC:CC:00:00:03" to "Bose QC Ultra Headphones",
        "DD:DD:DD:00:00:04" to "Pixel Buds",
    )

    @Test
    fun `the LE prefix is recognised and stripped`() {
        assertTrue(LeAudioTwins.isLeName("LE-Bose QC Ultra Earbuds"))
        assertTrue(LeAudioTwins.isLeName("le_Bose QC Ultra Earbuds"))
        assertFalse(LeAudioTwins.isLeName("Bose QC Ultra Earbuds"))
        assertFalse(LeAudioTwins.isLeName("Lenovo headset"), "a name that merely starts with Le is not an LE identity")
        assertEquals("Bose QC Ultra Earbuds", LeAudioTwins.baseName("LE-Bose QC Ultra Earbuds"))
        assertNull(LeAudioTwins.baseName("LE-"))
    }

    @Test
    fun `an LE identity and its classic device are twins of each other`() {
        assertEquals(listOf("AA:AA:AA:00:00:01"), LeAudioTwins.twinsOf("BB:BB:BB:00:00:02", bonded))
        assertEquals(listOf("BB:BB:BB:00:00:02"), LeAudioTwins.twinsOf("aa:aa:aa:00:00:01", bonded))
        assertTrue(LeAudioTwins.twinsOf("CC:CC:CC:00:00:03", bonded).isEmpty())
        assertTrue(LeAudioTwins.twinsOf("EE:EE:EE:00:00:05", bonded).isEmpty(), "not bonded")
    }

    @Test
    fun `two classic devices with the same name are not twins`() {
        val pair = mapOf("AA:00:00:00:00:01" to "Bose QC Earbuds", "AA:00:00:00:00:02" to "Bose QC Earbuds")
        assertTrue(LeAudioTwins.twinsOf("AA:00:00:00:00:01", pair).isEmpty())
    }

    @Test
    fun `the PSM is read in whichever byte order lands in the dynamic LE range`() {
        assertEquals(0x81, BmapPsmReader.parsePsm(byteArrayOf(0x81.toByte())))
        assertEquals(0x81, BmapPsmReader.parsePsm(byteArrayOf(0x81.toByte(), 0x00)))
        assertEquals(0x81, BmapPsmReader.parsePsm(byteArrayOf(0x00, 0x81.toByte())))
        assertNull(BmapPsmReader.parsePsm(byteArrayOf(0x05)), "fixed PSMs are not BMAP")
        assertNull(BmapPsmReader.parsePsm(byteArrayOf()))
        assertNull(BmapPsmReader.parsePsm(null))
    }

    @Test
    fun `on LE Audio the LE channel goes first and classic candidates follow on the twin`() {
        val order = BmapCandidates.order(learned = null, guessedChannel = 2, hasClassic = true, hasLe = true, preferLe = true)
        assertEquals(BmapCandidate.LeCoc, order.first())
        assertEquals(BmapCandidate.Channel(2), order[1])
    }

    @Test
    fun `an LE identity without a classic twin tries only the LE channel`() {
        assertEquals(listOf(BmapCandidate.LeCoc), BmapCandidates.order(null, 2, hasClassic = false, hasLe = true, preferLe = true))
    }

    @Test
    fun `on classic Bluetooth the LE channel is the last resort and a learned channel still leads`() {
        val order = BmapCandidates.order(BmapCandidate.Channel(8), 2, hasClassic = true, hasLe = true, preferLe = false)
        assertEquals(BmapCandidate.Channel(8), order.first())
        assertEquals(BmapCandidate.LeCoc, order.last())
    }

    @Test
    fun `a learned LE channel leads only when the phone is on LE Audio`() {
        val onLe = BmapCandidates.order(BmapCandidate.LeCoc, 2, hasClassic = true, hasLe = true, preferLe = true)
        assertEquals(BmapCandidate.LeCoc, onLe.first())
        val onClassic = BmapCandidates.order(BmapCandidate.LeCoc, 2, hasClassic = true, hasLe = true, preferLe = false)
        assertEquals(BmapCandidate.Channel(2), onClassic.first())
        assertEquals(1, onClassic.count { it == BmapCandidate.LeCoc })
    }

    @Test
    fun `the LE candidate survives a round trip through the preferences`() {
        assertEquals(BmapCandidate.LeCoc, BmapCandidate.fromPref(BmapCandidate.LeCoc.prefValue))
    }
}

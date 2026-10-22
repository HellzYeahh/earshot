package dev.earshot.bt

import dev.earshot.bt.BmapCandidate.Channel
import dev.earshot.bt.BmapCandidate.SdpBmap
import dev.earshot.bt.BmapCandidate.SdpSpp
import dev.earshot.protocol.qcultra2.BoseModel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The order the Bose link factory walks the BMAP channel candidates in. */
class BmapCandidatesTest {

    @Test
    fun `nothing learned, no name guess - 2, 8, 9, then the SDP lookups`() {
        assertEquals(listOf(Channel(2), Channel(8), Channel(9), SdpSpp, SdpBmap), BmapCandidates.order(learned = null, guessedChannel = null))
    }

    @Test
    fun `the learned channel goes first and duplicates are dropped`() {
        // A prince unit whose BMAP sits on 9 (bosectl #26), learned on an earlier connect.
        assertEquals(
            listOf(Channel(9), Channel(8), Channel(2), SdpSpp, SdpBmap),
            BmapCandidates.order(learned = Channel(9), guessedChannel = BoseModel.QC_HEADPHONES.rfcommChannel),
        )
        assertEquals(listOf(Channel(2), Channel(8), Channel(9), SdpSpp, SdpBmap), BmapCandidates.order(Channel(2), 2))
    }

    @Test
    fun `the model guess comes before the generic list`() {
        assertEquals(listOf(Channel(8), Channel(2), Channel(9), SdpSpp, SdpBmap), BmapCandidates.order(null, BoseModel.QC45.rfcommChannel))
        assertEquals(listOf(Channel(2), Channel(8), Channel(9), SdpSpp, SdpBmap), BmapCandidates.order(null, BoseModel.QC_ULTRA_1.rfcommChannel))
    }

    @Test
    fun `a learned SDP winner or an odd channel is tried first too`() {
        assertEquals(listOf(SdpSpp, Channel(2), Channel(8), Channel(9), SdpBmap), BmapCandidates.order(SdpSpp, null))
        assertEquals(listOf(Channel(11), Channel(2), Channel(8), Channel(9), SdpSpp, SdpBmap), BmapCandidates.order(Channel(11), null))
    }

    @Test
    fun `impossible channels are ignored`() {
        assertEquals(listOf(Channel(2), Channel(8), Channel(9), SdpSpp, SdpBmap), BmapCandidates.order(Channel(0), 31))
    }

    @Test
    fun `stored values round-trip through DevicePrefs codes`() {
        for (c in listOf(Channel(2), Channel(9), SdpSpp, SdpBmap)) assertEquals(c, BmapCandidate.fromPref(c.prefValue))
        assertNull(BmapCandidate.fromPref(null))
        assertNull(BmapCandidate.fromPref(0))
        assertNull(BmapCandidate.fromPref(-7))
    }

    @Test
    fun `a forced Bose type on a non-Bose device gets only the SPP lookup, never blind channels or deca-fade`() {
        val order = BmapCandidates.order(learned = null, guessedChannel = 8, fixedChannels = false)
        // deca-fade is probably Apple iAP2: a stray frame there powered a Sennheiser Accentum off.
        assertEquals(listOf<BmapCandidate>(BmapCandidate.SdpSpp), order)
        // A device that really answered on deca-fade before keeps it.
        assertEquals(BmapCandidate.SdpBmap, BmapCandidates.order(BmapCandidate.SdpBmap, null, fixedChannels = false).first())
        // A channel this device really answered on before is still tried first.
        assertEquals(BmapCandidate.Channel(2), BmapCandidates.order(BmapCandidate.Channel(2), null, fixedChannels = false).first())
    }
}

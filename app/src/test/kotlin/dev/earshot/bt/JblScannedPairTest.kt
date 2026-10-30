package dev.earshot.bt

import dev.earshot.protocol.jbl.JblDeviceInfo
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A JBL pair found by the `<name>-LE` scan is controlled only when its device info doesn't name
 * another headset than the paired one: the user's own buds may be off while an identical pair
 * nearby advertises the same name, even when that pair is the only one found.
 */
class JblScannedPairTest {

    @Before
    fun quiet() {
        JblLog.sink = { _, _, _ -> }
    }

    private val paired = "50:1B:6A:2F:2D:FF"

    private fun info(address: String?) = JblDeviceInfo("JBL Wave Buds 2", 0x2100, null, "2.7.0", address)

    @Test fun `the paired headset's own address passes, in either byte order`() {
        JblLinkFactory.checkScannedPair(info(paired), paired, "JBL Wave Buds 2")
        JblLinkFactory.checkScannedPair(info("FF:2D:2F:6A:1B:50"), paired, "JBL Wave Buds 2")
        JblLinkFactory.checkScannedPair(info(paired.lowercase()), paired, "JBL Wave Buds 2")
    }

    @Test fun `a single scanned pair that names another address is refused`() {
        val e = assertFailsWith<ExplainedConnectException> {
            JblLinkFactory.checkScannedPair(info("11:22:33:44:55:66"), paired, "JBL Wave Buds 2")
        }
        assertTrue(e.retry, "the user's own pair may turn up on the next try")
        assertTrue(e.message!!.contains("JBL Wave Buds 2"))
        // The user reads that sentence, not "Could not connect".
        assertEquals(e.message, SessionEnd.connectFailureMessage(e, linked = true))
    }

    @Test fun `no paired address to compare with is refused too`() {
        assertFailsWith<ExplainedConnectException> { JblLinkFactory.checkScannedPair(info("11:22:33:44:55:66"), null, null) }
    }

    @Test fun `a device info without an address can't tell, so the single pair is kept as before`() {
        JblLinkFactory.checkScannedPair(info(null), paired, "JBL Wave Buds 2")
    }
}

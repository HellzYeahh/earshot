package dev.earshot.bt

import dev.earshot.protocol.qcultra2.BoseModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Devices from GitHub issues, pinned so a later routing change cannot quietly bring their problem back. */
class ReportedDevicesTest {

    /**
     * Issue #1: the 2024 QuietComfort Earbuds, saved as "QCE (2024)", never answer BMAP (separate Bose
     * app, other protocol). Earshot 0.3.1 walked channels 2 / 8 / 9 and both SDP lookups on them in
     * a loop, 20 to 90 s per round, and ended with "another app is probably holding it". Since 0.6
     * such a name is a generic headset: shown as itself, nothing opened on it.
     */
    @Test
    fun `issue 1 - QCE 2024 earbuds are generic, so no Bose channel search runs on them`() {
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName("QCE (2024)"))
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.resolve(override = null, name = "QCE (2024)", learned = null))
        assertNull(BoseModel.fromName("QCE (2024)"), "not a catalogue match: no channel guess either")
    }
}

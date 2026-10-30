package dev.earshot.ui.tools

import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the Lab writes a scan file: once per scan. The screen's effect used to write one every
 * time it came back with results still in the ViewModel (rotation, Bluetooth history and back,
 * a tab switch), each an identical copy that was never cleaned up.
 */
class LabScanFileTest {

    /** Feeds the screen's effect the `scanning` values it sees, as the saved flag carries over. */
    private fun writes(vararg seen: Pair<Boolean, Boolean>): List<Boolean> {
        var pending = false
        return seen.map { (scanning, hasReadings) ->
            val step = LabScanFile.step(pending, scanning, hasReadings)
            pending = step.pending
            step.write
        }
    }

    @Test fun `a finished scan is written once`() {
        // Scan runs, ends; then a rotation and a trip to Bluetooth history re-run the effect.
        assertEquals(listOf(false, true, false, false), writes(true to false, false to true, false to true, false to true))
    }

    @Test fun `opening the screen with old results writes nothing`() {
        assertEquals(listOf(false), writes(false to true))
    }

    @Test fun `each new scan is written again, a stopped one too`() {
        assertEquals(listOf(false, true, false, true), writes(true to false, false to true, true to true, false to true))
    }

    @Test fun `a scan that found nothing writes no file and leaves nothing pending`() {
        assertEquals(listOf(false, false, false), writes(true to false, false to false, false to true))
    }

    @Test fun `a scan that ended while the screen was away is written when it returns`() {
        // The flag is saved with the screen: it saw the scan start, then comes back to it finished.
        val started = LabScanFile.step(pending = false, scanning = true, hasReadings = false)
        assertTrue(started.pending)
        val back = LabScanFile.step(pending = started.pending, scanning = false, hasReadings = true)
        assertTrue(back.write)
        assertFalse(back.pending)
    }

    @Test fun `the file name carries the local time to the second`() {
        val calendar = Calendar.getInstance(TimeZone.getDefault()).apply { clear(); set(2026, Calendar.OCTOBER, 28, 7, 5, 9) }
        assertEquals("lab-scan-20261028-070509.txt", LabScanFile.name(Date(calendar.timeInMillis)))
    }
}

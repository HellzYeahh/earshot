package dev.earshot.bt

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a BLE scan needs to return results on each Android version: BLUETOOTH_SCAN from 12, the
 * location permission and location turned on before that (a scan without them starts but finds
 * nothing, silently).
 */
class BleScanAccessTest {

    private fun missing(sdk: Int, scan: Boolean = false, fine: Boolean = false, coarse: Boolean = false, locationOn: Boolean = true) =
        BleScanAccess.missing(sdk, scanGranted = scan, fineGranted = fine, coarseGranted = coarse, locationOn = locationOn)

    @Test fun `Android 12 and later need only the Nearby devices permission`() {
        assertNull(missing(31, scan = true, locationOn = false), "neverForLocation: no location needed")
        assertNull(missing(36, scan = true))
        assertTrue(missing(31)!!.contains("Nearby devices"))
    }

    @Test fun `Android 10 and 11 need precise location and location on`() {
        for (sdk in listOf(29, 30)) {
            assertNotNull(missing(sdk), "the old BLUETOOTH permission alone delivers no results")
            assertNotNull(missing(sdk, coarse = true), "coarse is not enough for an app targeting 29+")
            assertNotNull(missing(sdk, fine = true, locationOn = false), "location must be on")
            assertNull(missing(sdk, fine = true))
        }
    }

    @Test fun `Android 9 accepts coarse location, with location on`() {
        assertNotNull(missing(28))
        assertNull(missing(28, coarse = true))
        assertNull(missing(28, fine = true))
        assertTrue(missing(28, coarse = true, locationOn = false)!!.contains("location turned on"))
    }
}

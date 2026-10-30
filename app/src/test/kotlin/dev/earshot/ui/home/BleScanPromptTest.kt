package dev.earshot.ui.home

import dev.earshot.bt.BleScanAccess
import dev.earshot.core.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When Home asks for BLE scan access on Android 9–11 (audit R36): only for the headsets whose
 * information comes from a scan, and with the fix that applies (the permission, or location on).
 */
class BleScanPromptTest {

    private val connected = ConnectionStatus.CONNECTED
    private val down = ConnectionStatus.DISCONNECTED

    @Test fun `AirPods and Beats earbuds ask while connected, on Android 11 and older only`() {
        for (sdk in 28..30) {
            assertEquals(BleScanPrompt.Reason.BUD_BATTERY, BleScanPrompt.reason(sdk, "Jo's AirPods Pro", connected, earbuds = true))
            // earbuds = false is what the name-derived form factor gives for these: no earbud rule
            // matches them, yet they are buds whose battery comes from adverts.
            assertEquals(BleScanPrompt.Reason.BUD_BATTERY, BleScanPrompt.reason(sdk, "Beats Fit Pro", connected, earbuds = false))
            assertEquals(BleScanPrompt.Reason.BUD_BATTERY, BleScanPrompt.reason(sdk, "Powerbeats Pro", connected, earbuds = false))
            assertEquals(BleScanPrompt.Reason.BUD_BATTERY, BleScanPrompt.reason(sdk, "Beats Studio Buds", connected, earbuds = true))
            assertNull(BleScanPrompt.reason(sdk, "Jo's AirPods Pro", down, earbuds = true), "nothing to show while they are away")
        }
        // Android 12+: the Nearby devices permission asked at start-up covers the scan.
        assertNull(BleScanPrompt.reason(31, "Jo's AirPods Pro", connected, earbuds = true))
        assertNull(BleScanPrompt.reason(36, "Beats Fit Pro", connected, earbuds = false))
    }

    @Test fun `over-ear Apple headphones and the demo headset never ask`() {
        // One battery figure, which Android's own broadcast already gives.
        assertNull(BleScanPrompt.reason(30, "AirPods Max", connected, earbuds = false))
        assertNull(BleScanPrompt.reason(30, "Beats Studio Pro", connected, earbuds = false))
        assertNull(BleScanPrompt.reason(30, "Beats Solo 4", connected, earbuds = false))
        // Canned adverts and a virtual link.
        assertNull(BleScanPrompt.reason(30, "AirPods Pro (demo)", connected, earbuds = true))
        assertNull(BleScanPrompt.reason(30, null, connected, earbuds = true))
    }

    @Test fun `a JBL headset asks while its control link is down`() {
        assertEquals(BleScanPrompt.Reason.CONTROL_LINK, BleScanPrompt.reason(29, "JBL Wave Buds 2", down, earbuds = true))
        assertEquals(BleScanPrompt.Reason.CONTROL_LINK, BleScanPrompt.reason(29, "JBL Wave Buds 2", ConnectionStatus.CONNECTING, earbuds = true))
        assertNull(BleScanPrompt.reason(29, "JBL Wave Buds 2", connected, earbuds = true), "connected: the scan already worked or wasn't needed")
        assertNull(BleScanPrompt.reason(31, "JBL Wave Buds 2", down, earbuds = true))
    }

    @Test fun `any other headset never asks`() {
        assertNull(BleScanPrompt.reason(28, "Bose QC Ultra Headphones", down, earbuds = false))
        assertNull(BleScanPrompt.reason(28, "Galaxy Buds3 Pro", connected, earbuds = true))
    }

    /** The need follows BleScanAccess's own answer for the same grants. */
    private fun need(sdk: Int, fine: Boolean = false, coarse: Boolean = false, locationOn: Boolean = true) = BleScanPrompt.need(
        sdk,
        missing = BleScanAccess.missing(sdk, scanGranted = false, fineGranted = fine, coarseGranted = coarse, locationOn = locationOn),
        fineGranted = fine,
        coarseGranted = coarse,
    )

    @Test fun `Android 10 and 11 ask for precise location, then for location on`() {
        for (sdk in listOf(29, 30)) {
            assertEquals(BleScanPrompt.Need.PERMISSION, need(sdk))
            assertEquals(BleScanPrompt.Need.PERMISSION, need(sdk, coarse = true), "coarse is not enough there")
            assertEquals(BleScanPrompt.Need.LOCATION_OFF, need(sdk, fine = true, coarse = true, locationOn = false))
            assertNull(need(sdk, fine = true, coarse = true))
        }
    }

    @Test fun `Android 9 is satisfied by approximate location`() {
        assertEquals(BleScanPrompt.Need.PERMISSION, need(28))
        assertNull(need(28, coarse = true))
        assertEquals(BleScanPrompt.Need.LOCATION_OFF, need(28, coarse = true, locationOn = false))
    }

    @Test fun `Android 12 and later are not this banner's`() {
        assertNull(BleScanPrompt.need(31, missing = "the Nearby devices permission", fineGranted = false, coarseGranted = false))
    }

    @Test fun `one request covers both location permissions`() {
        assertEquals(
            setOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
            BleScanPrompt.PERMISSIONS.toSet(),
        )
    }
}

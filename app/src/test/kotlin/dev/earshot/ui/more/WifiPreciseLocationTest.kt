package dev.earshot.ui.more

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Wi-Fi trigger's banner when Android 12+ granted only approximate location: the Wi-Fi name
 * stays hidden without precise location, so the banner must ask for precise by name rather than
 * read as if nothing had been granted.
 */
class WifiPreciseLocationTest {

    @Test fun `approximate without precise asks for precise`() {
        assertTrue(WifiLocationAsk.approximateOnly(precise = false, approximate = true))
    }

    @Test fun `nothing granted keeps the plain location ask`() {
        assertFalse(WifiLocationAsk.approximateOnly(precise = false, approximate = false))
    }

    @Test fun `precise granted needs no banner text at all`() {
        // Android grants approximate together with precise; either way nothing is missing.
        assertFalse(WifiLocationAsk.approximateOnly(precise = true, approximate = true))
        assertFalse(WifiLocationAsk.approximateOnly(precise = true, approximate = false))
    }
}

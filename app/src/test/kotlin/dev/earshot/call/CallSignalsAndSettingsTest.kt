package dev.earshot.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallSignalsAndSettingsTest {

    @Test fun `only in-call and in-communication modes are calls`() {
        assertTrue(CallSignals.isCallMode(2))  // MODE_IN_CALL
        assertTrue(CallSignals.isCallMode(3))  // MODE_IN_COMMUNICATION
        assertFalse(CallSignals.isCallMode(0)) // MODE_NORMAL
        assertFalse(CallSignals.isCallMode(1)) // MODE_RINGTONE
        assertFalse(CallSignals.isCallMode(4)) // MODE_CALL_SCREENING
        assertFalse(CallSignals.isCallMode(5)) // MODE_CALL_REDIRECT
    }

    @Test fun `device types map to call routes`() {
        assertEquals(CallRoute.BLUETOOTH_SCO, CallSignals.routeFor(7))
        assertEquals(CallRoute.LE_AUDIO, CallSignals.routeFor(26))
        assertEquals(CallRoute.OTHER, CallSignals.routeFor(1)) // earpiece
        assertEquals(CallRoute.OTHER, CallSignals.routeFor(8)) // A2DP is never a call route
        assertEquals(CallRoute.UNKNOWN, CallSignals.routeFor(null))
        assertTrue(CallSignals.isHeadsetRoute(CallRoute.BLUETOOTH_SCO))
        assertTrue(CallSignals.isHeadsetRoute(CallRoute.LE_AUDIO))
        assertFalse(CallSignals.isHeadsetRoute(CallRoute.OTHER))
        assertFalse(CallSignals.isHeadsetRoute(CallRoute.UNKNOWN))
    }

    @Test fun `address match tolerates case and unknowns`() {
        assertTrue(CallSignals.sameAddress("e4:58:bc:00:00:11", "E4:58:BC:00:00:11"))
        assertTrue(CallSignals.sameAddress(null, "E4:58:BC:00:00:11"))
        assertTrue(CallSignals.sameAddress("", "E4:58:BC:00:00:11"))
        assertFalse(CallSignals.sameAddress("11:22:33:44:55:66", "E4:58:BC:00:00:11"))
    }

    @Test fun `absolute volume read - global setting wins, property is the fallback`() {
        assertTrue(AbsoluteVolumeSetting.parse("1\n--earshot--\n\n"))
        assertFalse(AbsoluteVolumeSetting.parse("0\n--earshot--\ntrue\n"))
        assertTrue(AbsoluteVolumeSetting.parse("null\n--earshot--\ntrue\n"))
        assertFalse(AbsoluteVolumeSetting.parse("null\n--earshot--\nfalse\n"))
        assertFalse(AbsoluteVolumeSetting.parse("null\n--earshot--\n\n")) // never set: absolute volume on
        assertFalse(AbsoluteVolumeSetting.parse(""))
    }

    @Test fun `absolute volume write touches both places and reports the property outcome`() {
        val on = AbsoluteVolumeSetting.writeCommand(disabled = true)
        assertTrue(on.startsWith("settings put global bluetooth_disable_absolute_volume 1 &&"))
        assertTrue("setprop persist.bluetooth.disableabsvol true" in on)
        assertTrue(AbsoluteVolumeSetting.PROP_OK in on && AbsoluteVolumeSetting.PROP_SKIPPED in on)
        val off = AbsoluteVolumeSetting.writeCommand(disabled = false)
        assertTrue(off.startsWith("settings put global bluetooth_disable_absolute_volume 0 &&"))
        assertTrue("setprop persist.bluetooth.disableabsvol false" in off)
    }
}

package dev.earshot.protocol.apple

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNTHETIC adverts built from the documented layout (see [ContinuityAdvert]), not captured from
 * our AirPods yet. Replace or extend with a real capture: the scanner logs the raw bytes.
 */
class ContinuityAdvertTest {

    private fun advert(model: Int, status: Int, buds: Int, chargeCase: Int) = byteArrayOf(
        0x07, 0x19, 0x01, (model shr 8).toByte(), model.toByte(), status.toByte(), buds.toByte(), chargeCase.toByte(),
        0x00, 0x00, 0x00, // lid counter, colour, padding: unused
    )

    @Test fun `not flipped - low nibble is the left bud`() {
        // status 0x20 set: low nibble left. Left 80 %, right 90 %, right charging (0x2), case 50 %.
        val a = assertNotNull(ContinuityAdvert.parse(advert(0x1420, status = 0x2B, buds = 0x98, chargeCase = 0x25)))
        assertEquals("AirPods Pro (2nd generation)", a.modelName)
        assertEquals(80, a.leftPercent)
        assertEquals(90, a.rightPercent)
        assertEquals(50, a.casePercent)
        assertFalse(a.leftCharging)
        assertTrue(a.rightCharging)
        assertFalse(a.caseCharging)
        assertEquals(80, a.headlinePercent)
    }

    @Test fun `flipped - high nibble is the left bud, charge bits swap`() {
        // status bit 0x20 clear. High nibble 3 = left 30 %, low 10 = right 100 %; 0x5_ = left charging + case charging.
        val a = assertNotNull(ContinuityAdvert.parse(advert(0x0E20, status = 0x03, buds = 0x3A, chargeCase = 0x68)))
        assertEquals(30, a.leftPercent)
        assertEquals(100, a.rightPercent)
        assertEquals(80, a.casePercent)
        assertTrue(a.leftCharging)
        assertFalse(a.rightCharging)
        assertTrue(a.caseCharging)
    }

    @Test fun `15 means no reading, the case in particular when the lid is shut`() {
        val a = assertNotNull(ContinuityAdvert.parse(advert(0x0F20, status = 0x20, buds = 0xF7, chargeCase = 0x0F)))
        assertEquals(70, a.leftPercent)
        assertNull(a.rightPercent)
        assertNull(a.casePercent)
        assertEquals(70, a.headlinePercent)
    }

    @Test fun `AirPods Max report one battery`() {
        val a = assertNotNull(ContinuityAdvert.parse(advert(0x0A20, status = 0x20, buds = 0xF6, chargeCase = 0x0F)))
        assertTrue(a.isHeadphones)
        assertEquals(60, a.headlinePercent)
    }

    @Test fun `other Continuity messages and short data are ignored`() {
        assertNull(ContinuityAdvert.parse(null))
        assertNull(ContinuityAdvert.parse(byteArrayOf(0x07, 0x19, 0x01)))
        assertNull(ContinuityAdvert.parse(byteArrayOf(0x10, 0x05, 0x01, 0x18, 0x44, 0x00, 0x00, 0x00))) // "nearby info"
        assertNull(ContinuityAdvert.parse(byteArrayOf(0x02, 0x15, 0, 0, 0, 0, 0, 0))) // iBeacon
    }

    @Test fun `an advert only counts for a headset whose name fits its model`() {
        assertTrue(AppleModels.fitsName(0x1420, "Yass's AirPods Pro"))
        assertFalse(AppleModels.fitsName(0x0F20, "Yass's AirPods Pro")) // someone else's plain AirPods
        assertFalse(AppleModels.fitsName(0x0A20, "AirPods Pro"))
        assertTrue(AppleModels.fitsName(0x0A20, "AirPods Max"))
        assertTrue(AppleModels.fitsName(0x0B20, "Powerbeats Pro"))
        assertFalse(AppleModels.fitsName(0x0B20, "AirPods"))
        assertTrue(AppleModels.fitsName(0x7777, "AirPods")) // unknown model: allowed
    }
}

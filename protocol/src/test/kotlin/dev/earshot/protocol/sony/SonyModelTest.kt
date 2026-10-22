package dev.earshot.protocol.sony

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SonyModelTest {

    @Test fun `the byte-reversed service UUIDs match the app's twins`() {
        assertEquals("e2b63c39-7db1-3fb0-a84b-9ad4267b6c95", SonyModel.SERVICE_V2_REVERSED)
        assertEquals("ba69e0f5-16e3-2db3-ad46-68503e20cc96", SonyModel.SERVICE_V1_REVERSED)
        assertTrue(SonyModel.isV2Service("956C7B26-D49A-4BA8-B03F-B17D393CB6E2"))
        assertTrue(SonyModel.isV2Service("E2B63C39-7DB1-3FB0-A84B-9AD4267B6C95"))
        assertFalse(SonyModel.isV2Service(SonyModel.SERVICE_V1))
        assertTrue(SonyModel.isV1Service("BA69E0F5-16E3-2DB3-AD46-68503E20CC96"))
        assertFalse(SonyModel.isV2Service("00001101-0000-1000-8000-00805f9b34fb"))
        assertEquals(SonyModel.SERVICE_V2, SonyModel.reversed(SonyModel.SERVICE_V2_REVERSED))
    }

    @Test fun `v2 models by Bluetooth name, the v1 WH-1000XM4 and other brands left out`() {
        for (name in listOf(
            "WH-1000XM5", "WH-1000XM6", "WF-1000XM4", "WF-1000XM5", "LE_WF-1000XM5", "LinkBuds S", "LinkBuds", "LinkBuds Fit",
            "WF-LS900N", "WF-L900", "ULT WEAR", "WH-ULT900N", "WH-CH720N", "WF-C700N", "WF-C710N", "WF-C510", "Alex's WH-1000XM5",
        )) assertTrue(SonyModel.isSonyName(name), name)
        for (name in listOf(null, "WH-1000XM4", "WH-1000XM3", "WF-1000XM3", "Bose QC Ultra Headphones", "soundcore Sleep A30", "QCY-T13", "WH-1000XM55", "Crusher ANC 2")) {
            assertFalse(SonyModel.isSonyName(name), name.toString())
        }
    }

    @Test fun `form factor from the model name the headset reports`() {
        assertEquals(SonyModel.Shape.HEADPHONES, SonyModel.shapeOf("WH-1000XM5"))
        assertEquals(SonyModel.Shape.EARBUDS, SonyModel.shapeOf("WF-1000XM5"))
        assertEquals(SonyModel.Shape.EARBUDS, SonyModel.shapeOf("WF-LS900N"))
        assertEquals(SonyModel.Shape.OPEN_EARBUDS, SonyModel.shapeOf("WF-L900"))
        assertEquals(SonyModel.Shape.OPEN_EARBUDS, SonyModel.shapeOf("WF-LC900"))
        assertEquals(SonyModel.Shape.EARBUDS, SonyModel.shapeOf(null, series = 0x60))
        assertEquals(SonyModel.Shape.HEADPHONES, SonyModel.shapeOf(null))
    }
}

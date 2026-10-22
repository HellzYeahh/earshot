package dev.earshot.protocol.bragi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Bragi model table: the Crusher 1080 as before, plus the Method 360 / 540 ANC earbuds. */
class BragiModelTest {

    @Test fun `the Crusher is matched exactly as before`() {
        assertEquals(BragiModel.SKULLCANDY_CRUSHER_1080, BragiModel.fromBluetoothName("Skullcandy CRUSHER 1080 ANC"))
        assertEquals(BragiModel.SKULLCANDY_CRUSHER_1080, BragiModel.fromBluetoothName("crusher1080"))
        assertEquals(BragiModel.SKULLCANDY_CRUSHER_1080, BragiModel.fromModelNumber("s6cnw"))
        assertNull(BragiModel.fromModelNumber("S6CAW")) // the Crusher ANC 2 is a different (BOS-AT) product
        assertNull(BragiModel.fromBluetoothName("Bose QC45"))
    }

    @Test fun `Method 360 and 540 are found by name, SKU and codename`() {
        assertEquals(BragiModel.SKULLCANDY_METHOD_360, BragiModel.fromBluetoothName("Skullcandy Method 360 ANC"))
        assertEquals(BragiModel.SKULLCANDY_METHOD_360, BragiModel.fromBluetoothName("Method-360_ANC"))
        assertEquals(BragiModel.SKULLCANDY_METHOD_540, BragiModel.fromBluetoothName("Skullcandy Method 540 ANC"))
        assertEquals(BragiModel.SKULLCANDY_METHOD_360, BragiModel.fromModelNumber("S2MTW"))
        assertEquals(BragiModel.SKULLCANDY_METHOD_540, BragiModel.fromModelNumber(" s2mfw "))
        assertEquals(BragiModel.SKULLCANDY_METHOD_540, BragiModel.fromModelNumber("S2MFW-T120"), "an ODM code after the SKU")
        assertNull(BragiModel.fromModelNumber("S2MFWX"), "a longer SKU is a different product")
        assertEquals(BragiModel.SKULLCANDY_METHOD_360, BragiModel.fromCodename("T99_PLUS"))
        assertEquals(BragiModel.SKULLCANDY_METHOD_360, BragiModel.fromCodename("t99+"))
        assertEquals(BragiModel.SKULLCANDY_METHOD_540, BragiModel.fromCodename("T120"))
        assertEquals(BragiModel.SKULLCANDY_CRUSHER_1080, BragiModel.fromCodename("CHP300"))
        assertNull(BragiModel.fromCodename(null))
        assertNull(BragiModel.fromCodename("   "))
    }

    @Test fun `other Skullcandy and Method products stay generic`() {
        // Not BOS-RPC: Skull-iQ (BOS-AT), the Airoha RACE line, BES, and a Method name without ANC.
        for (name in listOf(
            "Skullcandy Crusher ANC 2", "Skullcandy Rail ANC", "Skullcandy Sesh ANC", "Skullcandy Icon ANC", "Skullcandy Dime 540 ANC",
            "Skullcandy Hesh 540 ANC", "Skullcandy Method 360", "Method Pro", "Skullcandy Crusher 900 ANC", "Jabra Method",
        )) {
            assertFalse(BragiModel.isBragiName(name), name)
        }
    }

    @Test fun `the Crusher bass and spatial audio belong to the Crusher only`() {
        assertTrue(BragiModel.SKULLCANDY_CRUSHER_1080.features.crusherBass)
        assertTrue(BragiModel.SKULLCANDY_CRUSHER_1080.features.spatialAudio)
        assertFalse(BragiModel.SKULLCANDY_CRUSHER_1080.features.earbuds)
        for (m in listOf(BragiModel.SKULLCANDY_METHOD_360, BragiModel.SKULLCANDY_METHOD_540)) {
            assertFalse(m.features.crusherBass, m.name)
            assertFalse(m.features.spatialAudio, m.name)
            assertTrue(m.features.earbuds, m.name)
        }
    }

    @Test fun `SKUs and names are unique across the table`() {
        val skus = BragiModel.entries.flatMap { it.modelNumbers }
        assertEquals(skus.size, skus.toSet().size)
        assertEquals(BragiModel.entries.size, BragiModel.entries.map { it.displayName }.toSet().size)
        for (m in BragiModel.entries) assertEquals(m, BragiModel.fromBluetoothName(m.displayName), m.displayName)
    }

    @Test fun `the services the Method earbuds are allowed to call are still the allow-list`() {
        // The table adds models, not services: the allow-list the Crusher uses is unchanged (no FOTA, no feature.set_state).
        assertEquals(setOf(2, 4, 17, 23, 25), BragiServices.ALLOWED_SERVICES)
        assertFalse(BragiServices.isAllowed(BragiServices.Address(service = 99, method = 1)))
    }
}

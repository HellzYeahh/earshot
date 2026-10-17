package dev.earshot.protocol.qcultra2

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoseModelTest {

    @Test
    fun `product ids map to their models, soft-case variants included`() {
        assertEquals(BoseModel.QC_ULTRA_2, BoseModel.fromProductId(0x4082))
        assertEquals(BoseModel.QC_HEADPHONES_2, BoseModel.fromProductId(0x4083))
        assertEquals(BoseModel.QC_ULTRA_1, BoseModel.fromProductId(0x4066))
        assertEquals(BoseModel.QC_HEADPHONES, BoseModel.fromProductId(0x4075)) // also the QC SC
        assertEquals(BoseModel.QC45, BoseModel.fromProductId(0x4039))          // also the QC SE
        assertEquals(BoseModel.ULTRA_OPEN_EARBUDS, BoseModel.fromProductId(0x4068))
        assertNull(BoseModel.fromProductId(0x4062)) // QC Ultra Earbuds: not supported
        assertNull(BoseModel.fromProductId(null))
        assertEquals(BoseModel.QC_ULTRA_2, ProductInfo.parseProductIdVariant(byteArrayOf(0x40, 0x82.toByte(), 1))?.model)
    }

    @Test
    fun `names guess the model before connecting`() {
        assertEquals(BoseModel.QC45, BoseModel.fromName("Bose QC45"))
        assertEquals(BoseModel.QC45, BoseModel.fromName("Bose QC SE"))
        assertEquals(BoseModel.QC_HEADPHONES, BoseModel.fromName("Bose QC Headphones"))
        assertEquals(BoseModel.QC_HEADPHONES, BoseModel.fromName("Bose QC SC"))
        assertEquals(BoseModel.QC_HEADPHONES_2, BoseModel.fromName("Bose QuietComfort Headphones (2nd Gen)"))
        assertEquals(BoseModel.QC_ULTRA_2, BoseModel.fromName("Bose QC Ultra 2 HP")) // [0.15] on wolverine
        assertEquals(BoseModel.QC_ULTRA_1, BoseModel.fromName("Bose QC Ultra Headphones"))
        assertEquals(BoseModel.ULTRA_OPEN_EARBUDS, BoseModel.fromName("Bose Ultra Open Earbuds (2nd Gen)"))
        assertNull(BoseModel.fromName("Bose QC Ultra Earbuds"))
        assertNull(BoseModel.fromName("Galaxy Buds"))
        assertNull(BoseModel.fromName(null))
    }

    @Test
    fun `channels and layouts follow bosectl`() {
        for (m in listOf(BoseModel.QC45, BoseModel.QC_HEADPHONES)) {
            assertEquals(8, m.rfcommChannel)
            assertEquals(ModeLayout.COMPACT, m.modeLayout)
            assertFalse(m.hasLiveAudioSettings)
            assertTrue(m.windExcludesCnc)
            assertTrue(QcUltra2.AUDIO_SETTINGS in m.knownAbsent)
        }
        for (m in listOf(BoseModel.QC_ULTRA_2, BoseModel.QC_HEADPHONES_2, BoseModel.QC_ULTRA_1, BoseModel.ULTRA_OPEN_EARBUDS)) {
            assertEquals(2, m.rfcommChannel)
        }
        assertEquals(2, BoseModel.otherChannel(8))
        assertEquals(8, BoseModel.otherChannel(2))
        assertFalse(BoseModel.ULTRA_OPEN_EARBUDS.canEditModes)
        assertFalse(BoseModel.ULTRA_OPEN_EARBUDS.hasNoiseCancelling)
        assertTrue(BoseModel.QC_ULTRA_2.canEditModes)
    }

    // ── Compact [31.6] layout (QC45 / QC Headphones) ─────────────────────────────────────

    private fun compactStatus(index: Int, name: String, cnc: Int, wind: Boolean, editable: Boolean): ByteArray {
        val p = ByteArray(ModeConfigs.COMPACT_STATUS_SIZE)
        p[0] = index.toByte()
        p[3] = if (editable) 1 else 0
        p[4] = 1
        name.toByteArray().copyInto(p, 6)
        p[41] = 0x1d
        p[42] = cnc.toByte()
        p[45] = 1 // unknown flag; must not be read as wind
        p[46] = if (wind) 1 else 0
        return p
    }

    @Test
    fun `a 47-byte STATUS parses with wind at 46 and ANC always on`() {
        val c = ModeConfigs.parse(compactStatus(2, "Commute", cnc = 3, wind = false, editable = true))!!
        assertEquals(2, c.index)
        assertEquals("Commute", c.name)
        assertEquals(3, c.cncWire)
        assertFalse(c.windBlock)
        assertTrue(c.ancEnabled)
        assertEquals(true, c.editable)
        assertEquals(ModeLayout.COMPACT, c.layout)

        val windy = ModeConfigs.parse(compactStatus(3, "Bike", cnc = 0, wind = true, editable = true))!!
        assertTrue(windy.windBlock)
    }

    @Test
    fun `the 39-byte write omits the ANC byte and its echo parses`() {
        val write = ModeConfigWrite(2, "Commute", cncWire = 4, spatialCode = 0, windBlock = false, ancEnabled = true)
        val compact = write.build(ModeLayout.COMPACT, editableSlots = 2..3)
        assertEquals(39, compact.size)
        assertEquals(4, compact[35].toInt())
        assertEquals(0, compact[38].toInt())
        val echo = ModeConfigs.parse(compact)!!
        assertEquals("Commute", echo.name)
        assertEquals(4, echo.cncWire)
        assertTrue(echo.ancEnabled)
        assertEquals(ModeLayout.COMPACT, echo.layout)

        // The QC Ultra 2 write is unchanged.
        assertEquals(40, ModeConfigWrite(5, "Work").build().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a QC45 preset slot is refused by the builder`() {
        ModeConfigWrite(1, "Aware").build(ModeLayout.COMPACT, editableSlots = 2..3)
    }
}

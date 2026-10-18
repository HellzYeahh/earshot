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
        // The earbuds used to fall back to "QC Ultra 2"; now they are named for what they are.
        assertEquals(BoseModel.QC_ULTRA_EARBUDS_2, BoseModel.fromProductId(0x4062))
        assertEquals(BoseModel.QC_ULTRA_EARBUDS, BoseModel.fromProductId(0x4072))
        // 0.4.0 (docs/bose-models.md §A, §B): the models that used to fall back to the QC Ultra 2 defaults.
        assertEquals(BoseModel.NC700, BoseModel.fromProductId(0x4024))
        assertEquals(BoseModel.QC_EARBUDS_2, BoseModel.fromProductId(0x4064))
        assertEquals(BoseModel.QC_EARBUDS, BoseModel.fromProductId(0x402F))
        assertEquals(BoseModel.ULTRA_OPEN_EARBUDS_2, BoseModel.fromProductId(0x4089))
        assertEquals(BoseModel.QC35, BoseModel.fromProductId(0x400C))
        assertEquals(BoseModel.QC35_II, BoseModel.fromProductId(0x4020))
        assertEquals(BoseModel.QC_EARBUDS_2024, BoseModel.fromProductId(0x0023))
        assertEquals("mariah", BoseModel.QC_HEADPHONES_2.codename)
        assertNull(BoseModel.fromProductId(0x40aa))
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
        assertEquals(BoseModel.ULTRA_OPEN_EARBUDS, BoseModel.fromName("Bose Ultra Open Earbuds"))
        assertEquals(BoseModel.ULTRA_OPEN_EARBUDS_2, BoseModel.fromName("Bose Ultra Open Earbuds (2nd Gen)"))
        assertEquals(BoseModel.ULTRA_OPEN_EARBUDS_2, BoseModel.fromName("Bose Ultra Open 2 Earbuds"))
        assertNull(BoseModel.fromName("Bose Sport Open Earbuds"))
        // "QC Ultra Earbuds" contains "qc ultra" but must never land on the headphones.
        assertEquals(BoseModel.QC_ULTRA_EARBUDS, BoseModel.fromName("Bose QC Ultra Earbuds"))
        assertEquals(BoseModel.QC_ULTRA_EARBUDS_2, BoseModel.fromName("Bose QC Ultra Earbuds (2nd Gen)"))
        assertEquals(BoseModel.QC_ULTRA_EARBUDS_2, BoseModel.fromName("Bose QuietComfort Ultra Earbuds 2nd Gen"))
        assertEquals(BoseModel.QC_ULTRA_EARBUDS_2, BoseModel.fromName("Bose QC Ultra Earbuds II"))
        assertEquals(BoseModel.QC_ULTRA_1, BoseModel.fromName("Bose QC Ultra"))
        assertNull(BoseModel.fromName("Galaxy Buds"))
        assertNull(BoseModel.fromName(null))
    }

    @Test
    fun `the Bose app's original names map to their models`() {
        // docs/bose-models.md §D5 / §D13: the originalName of every product in scope.
        val names = mapOf(
            "Bose QC Ultra 2 HP" to BoseModel.QC_ULTRA_2,
            "Bose QC 2 HP" to BoseModel.QC_HEADPHONES_2,
            "Bose QC Ultra 2 Earbuds" to BoseModel.QC_ULTRA_EARBUDS_2,
            "Bose QC Ultra Headphones" to BoseModel.QC_ULTRA_1,
            "Bose QC Ultra Earbuds" to BoseModel.QC_ULTRA_EARBUDS,
            "Bose QC Earbuds II" to BoseModel.QC_EARBUDS_2,
            "Bose QuietComfort Headphones" to BoseModel.QC_HEADPHONES,
            "Bose QC 45" to BoseModel.QC45,
            "Bose NC 700 Headphones" to BoseModel.NC700,
            "Bose QuietComfort Earbuds" to BoseModel.QC_EARBUDS,
            "Bose Ultra Open Earbuds" to BoseModel.ULTRA_OPEN_EARBUDS,
            "Bose Ultra Open 2 Earbuds" to BoseModel.ULTRA_OPEN_EARBUDS_2,
            // Setup copy and other spellings.
            "Bose QC Headphones 2" to BoseModel.QC_HEADPHONES_2,
            "Bose QC35 II" to BoseModel.QC35_II,
            "Bose QuietComfort 35" to BoseModel.QC35,
            "LE-Bose QC Ultra 2 Earbuds" to BoseModel.QC_ULTRA_EARBUDS_2,
            "Bose QuietComfort Earbuds II" to BoseModel.QC_EARBUDS_2,
        )
        for ((name, model) in names) assertEquals(model, BoseModel.fromName(name), name)
    }

    @Test
    fun `entries carry family, generation and form factor`() {
        assertEquals("QuietComfort Ultra Headphones", BoseModel.QC_ULTRA_2.family)
        assertEquals("2nd Gen", BoseModel.QC_ULTRA_2.generation)
        assertNull(BoseModel.QC_ULTRA_1.generation, "the 1st Gen's marketing name has no suffix")
        // "2nd Gen" chips: exactly 0x4082, 0x4062, 0x4083 and 0x4089 (§D13).
        assertEquals(
            setOf(QcUltra2.PRODUCT_ID, QcUltra2.PRODUCT_ID_EARBUDS, QcUltra2.PRODUCT_ID_QC_HEADPHONES_2ND_GEN, PRODUCT_ID_ULTRA_OPEN_2),
            BoseModel.entries.filter { it.generation != null }.flatMap { it.productIds }.toSet(),
        )
        assertEquals(ProductForm.EARBUDS, BoseModel.QC_EARBUDS.formFactor)
        assertEquals(ProductForm.OPEN_EARBUDS, BoseModel.ULTRA_OPEN_EARBUDS_2.formFactor)
        assertEquals(ProductForm.HEADPHONES, BoseModel.NC700.formFactor)
        assertEquals(listOf("Stereo", "Immersion"), BoseModel.ULTRA_OPEN_EARBUDS.presetNames)
        assertEquals(listOf("Stereo", "Immersion", "Cinema"), BoseModel.ULTRA_OPEN_EARBUDS_2.presetNames)
        assertFalse(BoseModel.ULTRA_OPEN_EARBUDS.hasLiveAudioSettings, "no [31.10] in bosectl's serena config")
        assertEquals(10, BoseModel.QC_ULTRA_1.slotCount)
        assertEquals(10, BoseModel.QC_ULTRA_EARBUDS.slotCount)
        assertTrue(BoseModel.QC_ULTRA_1.hasWindBlock)
        for (m in listOf(BoseModel.QC35, BoseModel.QC35_II, BoseModel.QC_EARBUDS_2024)) {
            assertTrue(m.isUnsupported)
            assertTrue(!m.supportNote.isNullOrBlank())
        }
        assertTrue("Bose Connect" in BoseModel.QC35.supportNote.orEmpty())
        assertTrue("QC Earbuds app" in BoseModel.QC_EARBUDS_2024.supportNote.orEmpty())
        assertEquals(SupportTier.BASIC, BoseModel.QC_EARBUDS.tier)
        assertEquals(SupportTier.BASIC, BoseModel.NC700.tier)
        assertTrue(BoseModel.QC_EARBUDS.directCnc && BoseModel.NC700.directCnc)
        assertEquals(ModeLayout.BASIC, BoseModel.QC_EARBUDS.modeLayout)
        assertEquals(8, BoseModel.QC_EARBUDS.rfcommChannel)
        // Every product id is in exactly one entry.
        val ids = BoseModel.entries.flatMap { it.productIds }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `an unknown name gives a family and a form factor`() {
        assertEquals("Foo Earbuds", BoseModel.familyFromName("Bose Foo Earbuds"))
        assertNull(BoseModel.familyFromName("  "))
        assertEquals(ProductForm.EARBUDS, BoseModel.formFromName("Bose Foo Earbuds"))
        assertEquals(ProductForm.OPEN_EARBUDS, BoseModel.formFromName("Bose Sport Open Earbuds"))
        assertEquals(ProductForm.HEADPHONES, BoseModel.formFromName(null))
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
        // First guesses only: the app probes 2 / 8 / 9 and SDP whatever the model says.
        for (m in listOf(
            BoseModel.QC_ULTRA_2, BoseModel.QC_HEADPHONES_2, BoseModel.QC_ULTRA_1, BoseModel.ULTRA_OPEN_EARBUDS,
            BoseModel.QC_ULTRA_EARBUDS_2, BoseModel.QC_ULTRA_EARBUDS,
        )) {
            assertEquals(2, m.rfcommChannel)
        }
        assertEquals(ModeLayout.STANDARD, BoseModel.QC_ULTRA_EARBUDS_2.modeLayout)
        assertTrue(BoseModel.QC_ULTRA_EARBUDS_2.verified)
        assertFalse(BoseModel.QC_ULTRA_EARBUDS.verified)
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

package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.toHex
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 0.4.0 parsers the per-model detection rests on (docs/bose-models.md §C, §E). */
class ModelDetectionParsersTest {

    // ── [31.2] Capabilities ──────────────────────────────────────────────────────────────

    @Test
    fun `the fw 10_12 capture reads as 4 presets, 7 custom and every flag`() {
        val c = ModesCapabilities.parse("04070000007f02".hexToBytes())!!
        assertEquals(4, c.boseModes)
        assertEquals(7, c.userModes)
        assertEquals(11, c.totalModes)
        assertTrue(c.cnc && c.autoCnc && c.spatial && c.windBlock && c.favorites && c.ancToggle)
        assertEquals(2, c.minFavorites)
        assertEquals(0..3, c.presetSlots)
        assertEquals(4..10, c.customSlots)
    }

    @Test
    fun `31_2 flag bits follow the Bose app`() {
        // bit0 cnc, bit1 autoCnc, bit2 spatial, bit3 wind, bit4 favourites, bit5 ANC toggle.
        val qc45 = ModesCapabilities.parse("020200000019".hexToBytes())!!
        assertTrue(qc45.cnc && qc45.windBlock && qc45.favorites)
        assertFalse(qc45.spatial || qc45.ancToggle || qc45.autoCnc)
        assertEquals(0, qc45.minFavorites, "no byte 6: 0")
        val anc = ModesCapabilities.parse("040000000020".hexToBytes())!!
        assertTrue(anc.ancToggle)
        assertFalse(anc.favorites)
        // Some QC45 firmware reports no user modes: no custom slots.
        assertTrue(ModesCapabilities.parse("020000000001".hexToBytes())!!.customSlots.isEmpty())
        assertNull(ModesCapabilities.parse("0407000000".hexToBytes()))
        assertEquals("04070000007f02", ModesCapabilities.build(4, 7, 0x7f).toHex())
    }

    // ── Mode names ───────────────────────────────────────────────────────────────────────

    private fun status(index: Int, prompt: ModePrompt, name: String, size: Int = 48): ModeConfig {
        val p = ByteArray(size)
        p[0] = index.toByte()
        p[2] = prompt.code.toByte()
        p[3] = 1
        name.toByteArray().copyInto(p, 6)
        return ModeConfigs.parse(p)!!
    }

    @Test
    fun `mode names come from the prompt, then the name bytes, then Mode N`() {
        assertEquals("Cinema", ModeNames.displayName(status(3, ModePrompt.CINEMA, "")))
        assertEquals("Stereo", ModeNames.displayName(status(0, ModePrompt.STEREO, "")))
        assertEquals("Immersion", ModeNames.displayName(status(1, ModePrompt.IMMERSION, "Immersiv")))
        // A mode named in another language still reads as the app shows it.
        assertEquals("Commute", ModeNames.displayName(status(4, ModePrompt.COMMUTE, "Pendeln")))
        for (p in listOf(ModePrompt.FOCUS, ModePrompt.HOME, ModePrompt.MUSIC, ModePrompt.OUTDOOR, ModePrompt.RELAX,
            ModePrompt.RUN, ModePrompt.WALK, ModePrompt.WORK, ModePrompt.WORKOUT)) {
            assertTrue(ModeNames.forPrompt(p) != null, "$p")
        }
        // Prompts the app does not name: the UTF-8 field, then "Mode N".
        assertEquals("Library", ModeNames.displayName(status(5, ModePrompt.WHISPER, "Library")))
        assertEquals("Office", ModeNames.displayName(status(5, ModePrompt.NONE, "Office")))
        assertEquals("Mode 7", ModeNames.displayName(status(6, ModePrompt.NONE, "")))
        assertEquals("Mode 7", ModeNames.displayName(status(6, ModePrompt.NONE, "None")))
    }

    // ── [31.6] lengths ───────────────────────────────────────────────────────────────────

    private fun tail(size: Int, cnc: Int, spatial: Int = 0, wind: Boolean = false, anc: Boolean = true): ByteArray {
        val p = ByteArray(size)
        p[0] = 2
        p[3] = 1
        p[4] = 1
        "Commute".toByteArray().copyInto(p, 6)
        p[41] = 0x01
        p[42] = cnc.toByte()
        if (size > 44) p[44] = spatial.toByte()
        if (size > 46) p[46] = if (wind) 1 else 0
        if (size > 47) p[47] = if (anc) 1 else 0
        return p
    }

    @Test
    fun `a 44-byte STATUS is a full slot with only the level`() {
        val c = ModeConfigs.parse(tail(44, cnc = 4))!!
        assertEquals("Commute", c.name, "not misread as an echo with the name at byte 3 (§D4)")
        assertEquals(4, c.cncWire)
        assertEquals(SpatialMode.OFF.code, c.spatialCode)
        assertFalse(c.windBlock)
        assertTrue(c.ancEnabled)
        assertEquals(true, c.editable)
        assertNull(c.flags45)
        assertEquals(ModeLayout.BASIC, c.layout)
    }

    @Test
    fun `a 45-byte STATUS adds spatial only`() {
        val c = ModeConfigs.parse(tail(45, cnc = 0, spatial = 2))!!
        assertEquals(SpatialMode.HEAD, c.spatial)
        assertFalse(c.windBlock)
        assertTrue(c.ancEnabled)
        assertEquals(ModeLayout.SPATIAL, c.layout)
    }

    @Test
    fun `STATUS lengths map to the Bose app's write lengths`() {
        assertEquals(mapOf(44 to 37, 45 to 39, 47 to 39, 48 to 40),
            listOf(44, 45, 47, 48).associateWith { ModeLayout.ofStatus(it)!!.writeSize })
        assertNull(ModeLayout.ofStatus(43))
        assertEquals(ModeLayout.STANDARD, ModeLayout.ofStatus(52), "longer future frames keep the known prefix")
        assertEquals(ModeLayout.BASIC, ModeLayout.ofPayload(37))
        assertEquals(ModeLayout.COMPACT, ModeLayout.ofPayload(39))
        assertEquals(ModeLayout.STANDARD, ModeLayout.ofPayload(40))
    }

    @Test
    fun `the write follows the layout`() {
        val w = ModeConfigWrite(2, "Run", cncWire = 3, spatialCode = 2, windBlock = true, ancEnabled = false)
        val basic = w.build(ModeLayout.BASIC, editableSlots = 2..3)
        assertEquals(37, basic.size)
        assertEquals(3, basic[35].toInt())
        assertEquals(0, basic[36].toInt())
        val spatial = w.build(ModeLayout.SPATIAL, editableSlots = 2..3)
        assertEquals(39, spatial.size)
        assertEquals(2, spatial[37].toInt())
        assertEquals(0, spatial[38].toInt(), "the app's 0 wind pad")
        val compact = w.build(ModeLayout.COMPACT, editableSlots = 2..3)
        assertEquals(1, compact[38].toInt())
        val standard = w.build(ModeLayout.STANDARD, editableSlots = 2..3)
        assertEquals(40, standard.size)
        assertEquals(0, standard[39].toInt())
        // Echoes of each write parse back.
        assertEquals("Run", ModeConfigs.parse(basic)!!.name)
        assertEquals(3, ModeConfigs.parse(basic)!!.cncWire)
        assertEquals(2, ModeConfigs.parse(spatial)!!.spatialCode)
        assertFalse(ModeConfigs.parse(spatial)!!.windBlock)
    }

    // ── [1.5] CNC ────────────────────────────────────────────────────────────────────────

    @Test
    fun `1_5 steps map to UI levels with step 0 most cancelling`() {
        val s = Cnc.parse("0b0a03".hexToBytes())!! // wolverine scan in Aware: step 10
        assertEquals(0, s.cancellingLevel)
        assertTrue(s.enabled)
        assertFalse(s.userCanToggle)
        assertEquals(10, CncState(11, 0, 1).cancellingLevel)
        assertTrue(CncState(11, 0, 1).userCanToggle)
        for (level in 0..10) assertEquals(level, CncState(11, 0, 1).let { it.copy(step = it.stepForLevel(level)) }.cancellingLevel)
        // Three steps (an NC 700 favourite-level guess): 10 → step 0, 5 → 1, 0 → 2.
        val three = CncState(3, 1, 1)
        assertEquals(listOf(2, 1, 0), listOf(0, 5, 10).map { three.stepForLevel(it) })
        assertNull(CncState(1, 0, 1).cancellingLevel)
        assertNull(CncState(11, 11, 1).cancellingLevel)
        assertFailsWith<IllegalArgumentException> { CncState(1, 0, 1).stepForLevel(5) }
    }

    @Test
    fun `1_5 SET_GET is step then enabled`() {
        assertEquals("0701", Cnc.build(7, enabled = true).toHex())
        assertEquals("0000", Cnc.build(0, enabled = false).toHex())
    }

    // ── [1.4] StandbyTimer ───────────────────────────────────────────────────────────────

    @Test
    fun `1_4 reads one byte or the two-byte form and writes little-endian above 255`() {
        assertEquals(20, StandbyTimer.parse("14".hexToBytes()))
        assertEquals(0, StandbyTimer.parse("00".hexToBytes()))
        assertEquals(20, StandbyTimer.parse("1400".hexToBytes()), "under 3 bytes only byte 0 counts")
        assertEquals(1440, StandbyTimer.parse("a00005".hexToBytes()), "(byte2 << 8) | byte0")
        assertNull(StandbyTimer.parse(ByteArray(0)))
        assertEquals("14", StandbyTimer.build(20).toHex())
        assertEquals("ff", StandbyTimer.build(255).toHex())
        assertEquals("a005", StandbyTimer.build(1440).toHex())
        assertFailsWith<IllegalArgumentException> { StandbyTimer.build(-1) }
        assertEquals(1440, StandbyTimer.parse(StandbyTimer.buildStatus(1440, twoByte = true)))
        assertEquals(listOf(5, 20, 40, 60, 180, 1440, 0), AutoOffOptions.MINUTES)
    }

    // ── [2.2] Battery ────────────────────────────────────────────────────────────────────

    @Test
    fun `battery records are found by component id with minutes`() {
        val buds = Battery.parse("3c00b401 3cffff02 3cffff04 50ffff03".replace(" ", "").hexToBytes())
        assertEquals(180, buds.reading(BatteryComponent.RIGHT)?.remainingMinutes)
        assertNull(buds.reading(BatteryComponent.LEFT)?.remainingMinutes)
        assertEquals(80, buds.reading(BatteryComponent.CASE)?.percent)
        assertNull(buds.reading(BatteryComponent.SINGLE))
        // Up to 4 bytes is one record for the whole product (Bose StatusBatteryLevelResponse).
        val short = Battery.parse("5a".hexToBytes())
        assertEquals(90, short.percent)
        assertEquals(90, short.reading(BatteryComponent.SINGLE)?.percent)
    }
}

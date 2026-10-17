package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.toHex
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Block 31 parsers. The bosectl capture tool only scanned blocks 0-9, so these vectors come from
 * the byte layouts and raw excerpts in NOTES.md, not from a capture file.
 */
class AudioModesTest {

    // ── [31.10] ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `audio settings example from the notes round trips`() {
        // NOTES: send [5, 0, 0, 0, 1] = CNC 5, autoCNC off, spatial off, wind off, ANC on
        val s = AudioSettings.parse("0500000001".hexToBytes())!!
        assertEquals(5, s.cncWire)
        assertFalse(s.autoCnc)
        assertEquals(SpatialMode.OFF, s.spatial)
        assertFalse(s.windBlock)
        assertTrue(s.ancEnabled)
        assertFalse(s.cncMasked)
        assertEquals("0500000001", AudioSettings.build(s).toHex())
    }

    @Test
    fun `cancelling level is inverted on the wire`() {
        assertEquals(0, AudioSettings.cancellingLevelToWire(10))   // UI max cancelling → wire 0
        assertEquals(10, AudioSettings.cancellingLevelToWire(0))
        assertEquals(10, AudioSettings.cancellingLevelFromWire(0))
        assertEquals(3, AudioSettings.cancellingLevelFromWire(7))
        for (ui in 0..10) assertEquals(ui, AudioSettings.cancellingLevelFromWire(AudioSettings.cancellingLevelToWire(ui)))
        assertFails { AudioSettings.cancellingLevelToWire(11) }
        val s = AudioSettings.parse("0a00020101".hexToBytes())!!   // Aware-like: most ambient
        assertEquals(0, s.cancellingLevel)
        assertEquals("0000020101", AudioSettings.build(s.withCancellingLevel(10)).toHex())
    }

    @Test
    fun `autoCnc is always written as zero because the firmware rejects one`() {
        val s = AudioSettings(cncWire = 2, spatialCode = 2, windBlock = true, ancEnabled = true, autoCnc = true)
        assertEquals("0200020101", AudioSettings.build(s).toHex())
        assertTrue(s.cncMasked) // wind block on → CNC inaudible
        assertTrue(AudioSettings(0, 0, false, false).cncMasked) // ANC off → inaudible
        assertNull(AudioSettings.parse("0500".hexToBytes()))
    }

    // ── [31.3] ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `current mode start payload and the no-saved-mode marker`() {
        assertEquals("0000", CurrentMode.startPayload(0).toHex())
        assertEquals("0201", CurrentMode.startPayload(2, announce = true).toHex())
        assertFails { CurrentMode.startPayload(11) }
        assertEquals(0xFF, CurrentMode.parse(byteArrayOf(0xFF.toByte())))
        assertFalse(CurrentMode.isSavedSlot(QcUltra2.NO_SAVED_MODE))
        assertTrue(CurrentMode.isSavedSlot(10))
    }

    // ── [31.6] ───────────────────────────────────────────────────────────────────────────

    /** Builds a 48-byte STATUS from the NOTES "Mode Config Details" excerpts. */
    private fun status(head: String, name: String, tail41: String): ByteArray {
        val out = ByteArray(48)
        head.hexToBytes().copyInto(out, 0)                      // bytes 0-5
        name.toByteArray().copyInto(out, 6)                     // bytes 6-37, NUL padded
        tail41.hexToBytes().copyInto(out, 41)                   // bytes 41-47
        return out
    }

    private val aware = status("010002000001", "Aware", "020a0000000001")
    private val home = status("04000a010100", "Home", "1d000000010001")
    private val slot5 = status("050000010000", "None", "1d0a0000010001")
    private val immersion = status("020022000001", "Immersion", "00" + "000002000001")
    private val cinema = status("030024000001", "Cinema", "00" + "000001000001")

    @Test
    fun `48-byte status parses presets and user slots`() {
        val a = ModeConfigs.parse(aware)!!
        assertEquals(1, a.index)
        assertEquals("Aware", a.name)
        assertEquals(ModePrompt.AWARE, a.prompt)
        assertEquals(false, a.editable)
        assertEquals(false, a.configured)
        assertEquals(10, a.cncWire)            // Aware = most ambient
        assertEquals(0, a.cancellingLevel)
        assertFalse(a.windBlock)
        assertTrue(a.ancEnabled)
        assertTrue(a.isPreset)
        assertFalse(a.isEmptySlot)
        assertEquals(0x0002, a.unknownWord40)
        assertEquals(0x02, a.changeableFlags)
        assertEquals(setOf(ModeField.AUTO_CNC), a.changeableFields)

        val h = ModeConfigs.parse(home)!!
        assertEquals(4, h.index)
        assertEquals("Home", h.name)
        assertEquals(ModePrompt.HOME, h.prompt)
        assertEquals(true, h.editable)
        assertEquals(true, h.configured)
        assertEquals(0, h.cncWire)
        assertEquals(10, h.cancellingLevel)
        // Real fw 10.12 tail `1d 00 00 00 01 00 01`: byte 45 is 1 on every user slot, wind (46) is 0.
        assertFalse(h.windBlock)
        assertEquals(1, h.flags45)
        assertEquals(true, h.windConfigurable)
        assertTrue(h.ancEnabled)
        assertEquals(0x001d, h.unknownWord40)
        assertEquals(setOf(ModeField.CNC, ModeField.SPATIAL, ModeField.WIND, ModeField.ANC), h.changeableFields)
        assertFalse(h.isPreset)

        val s5 = ModeConfigs.parse(slot5)!!
        assertEquals(5, s5.index)
        assertEquals(true, s5.editable)
        assertEquals(false, s5.configured)
        assertTrue(s5.isEmptySlot)
        assertEquals(10, s5.cncWire)
        assertFalse(s5.windBlock) // `1d 0a 00 00 01 00 01`: empty slot, wind off

        // wind-on user slot `… 1d 00 00 00 01 01 01`: wind on (matches [31.10] wind=1 while in Commute).
        val commute = ModeConfigs.parse(status("060007010100", "Commute", "1d000000010101"))!!
        assertTrue(commute.windBlock)
        assertEquals(1, commute.flags45)
        val work = ModeConfigs.parse(status("07000b010100", "Work", "1d000000010001"))!!
        assertFalse(work.windBlock)
        assertEquals(ModePrompt.WORK, work.prompt)

        assertEquals(SpatialMode.HEAD, ModeConfigs.parse(immersion)!!.spatial)
        assertEquals(SpatialMode.ROOM, ModeConfigs.parse(cinema)!!.spatial)
        assertNull(ModeConfigs.parse(ByteArray(39)))
    }

    @Test
    fun `40-byte write has the documented layout and refuses presets`() {
        val write = ModeConfigWrite(index = 5, name = "Focus", cancellingLevel = 10, spatial = SpatialMode.OFF,
            windBlock = false, ancEnabled = true, prompt = ModePrompt.FOCUS)
        val bytes = write.build()
        assertEquals(40, bytes.size)
        assertEquals(5, bytes[0].toInt())
        assertEquals("000d", bytes.copyOfRange(1, 3).toHex())
        assertEquals("Focus", String(bytes, 3, 5))
        assertTrue(bytes.copyOfRange(8, 35).all { it == 0.toByte() })
        assertEquals("0000000001", bytes.copyOfRange(35, 40).toHex()) // cnc 0 (= UI 10), auto 0, spatial 0, wind 0, anc 1

        // the echo of our own write parses back
        val echo = ModeConfigs.parse(bytes)!!
        assertEquals("Focus", echo.name)
        assertEquals(5, echo.index)
        assertEquals(ModePrompt.FOCUS, echo.prompt)
        assertNull(echo.editable)
        assertEquals(10, echo.cancellingLevel)

        assertFails { ModeConfigWrite(0, "Quiet").build() }
        assertFails { ModeConfigWrite(11, "x").build() }
        assertFails { ModeConfigWrite(6, "x".repeat(32)).build() }
        assertEquals(40, ModeConfigWrite(6, "x".repeat(31)).build().size)
    }

    @Test
    fun `write from an existing slot keeps its prompt and the clear payload matches bosectl`() {
        val h = ModeConfigs.parse(home)!!
        val edited = ModeConfigWrite.from(h).copy(windBlock = false).build()
        val windOn = ModeConfigWrite.from(h).copy(windBlock = true).build()
        assertEquals(1, windOn[38].toInt()) // the 40-byte write keeps wind at 38, not at the STATUS's 46
        assertEquals(h.promptBytes, edited.u8(1) to edited.u8(2))
        assertEquals(0, edited[38].toInt())
        assertEquals(1, edited[39].toInt())

        val clear = ModeConfigWrite.clearSlot(7).build()
        assertEquals("None", String(clear, 3, 4))
        assertEquals("0000000000", clear.copyOfRange(35, 40).toHex())
    }

    @Test
    fun `mode names are 32-byte null padded fields capped at 31 UTF-8 bytes`() {
        val enc = ModeConfigs.encodeName("Café")
        assertEquals(32, enc.size)
        assertEquals("Café", String(enc, 0, 5))
        assertEquals(0, enc[5].toInt())
        assertFails { ModeConfigs.encodeName("é".repeat(16)) } // 32 bytes
        assertEquals("é".repeat(15), ModeConfigs.truncateName("é".repeat(16)))
        assertEquals(31, ModeConfigs.truncateName("a".repeat(50)).length)
        val emoji = ModeConfigs.truncateName("🎧".repeat(8)) // 4 bytes each, 7 fit
        assertEquals(28, emoji.toByteArray().size)
    }

    // ── [31.8] ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `favourites are a bitmask after the slot count`() {
        val f = FavoritesCodec.parse("0b0003".hexToBytes())!!
        assertEquals(11, f.totalModes)
        assertEquals(setOf(0, 1), f.indices)
        assertEquals("0b0003", FavoritesCodec.build(setOf(0, 1)).toHex())
        assertEquals("0b0016", FavoritesCodec.build(setOf(1, 2, 4)).toHex())
        assertEquals(setOf(1, 2, 4), FavoritesCodec.parse(FavoritesCodec.build(setOf(1, 2, 4)))!!.indices)
        // Two-byte big-endian mask for 11 slots: slots 8-10 sit in the first mask byte.
        assertEquals("0b0100", FavoritesCodec.build(setOf(8)).toHex())
        assertEquals("0b0701", FavoritesCodec.build(setOf(0, 8, 9, 10)).toHex())
        assertEquals(setOf(0, 8, 9, 10), FavoritesCodec.parse("0b0701".hexToBytes())!!.indices)
        assertFails { FavoritesCodec.build(setOf(11)) }
        assertNull(FavoritesCodec.parse("0b".hexToBytes()))
    }

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
}

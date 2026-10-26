package dev.earshot.service

import dev.earshot.core.ButtonAction
import dev.earshot.core.Sidetone
import dev.earshot.protocol.qcultra2.ButtonConfig
import dev.earshot.protocol.qcultra2.Buttons
import dev.earshot.protocol.qcultra2.Sidetone as SidetoneCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Sidetone and ButtonAction wire codes moved out of core (0.8, module split): core keeps the
 * human values (ButtonAction a frozen stored id), the Bose driver maps through the `:protocol`
 * tables in StateMapping. The tables core used to carry are copied below as golden values, and the
 * new mapping must give exactly what the old `wire` / `fromWire` gave, both ways, for every code
 * from 0 to 40 (plus -1 and 255). Bytes on the wire stay the same, and so do the saved shortcut ids.
 */
class WireCodeMappingTest {

    /** core's `Sidetone.wire` before the move. */
    private val oldSidetoneWire = linkedMapOf(Sidetone.OFF to 0, Sidetone.LOW to 3, Sidetone.MEDIUM to 2, Sidetone.HIGH to 1)

    /** core's `Sidetone.fromWire`: the first entry with that code, else OFF. */
    private fun oldSidetoneFromWire(v: Int): Sidetone = oldSidetoneWire.entries.firstOrNull { it.value == v }?.key ?: Sidetone.OFF

    /** core's `ButtonAction.wire` before the move (which the shortcut prefs saved as the choice's `mode`). */
    private val oldButtonWire = linkedMapOf(
        ButtonAction.NOT_CONFIGURED to 0, ButtonAction.VOICE_ASSISTANT to 1, ButtonAction.ANC_CYCLE to 2,
        ButtonAction.BATTERY_LEVEL to 3, ButtonAction.PLAY_PAUSE to 4, ButtonAction.INCREASE_CNC to 5,
        ButtonAction.DECREASE_CNC to 6, ButtonAction.TOGGLE_WAKE_WORD to 7, ButtonAction.SWITCH_DEVICE to 8,
        ButtonAction.CONVERSATION_MODE to 9, ButtonAction.TRACK_FORWARD to 10, ButtonAction.TRACK_BACK to 11,
        ButtonAction.FETCH_NOTIFICATIONS to 12, ButtonAction.WIND_MODE to 13, ButtonAction.DISABLED to 14,
        ButtonAction.CLIENT_INTERACTION to 15, ButtonAction.SPOTIFY_TAP to 16, ButtonAction.MODES_CAROUSEL to 17,
        ButtonAction.SPATIAL_AUDIO to 19, ButtonAction.LINE_IN_SWITCH to 20, ButtonAction.LINKING to 21,
        ButtonAction.UNKNOWN to -1,
    )

    /** core's `ButtonAction.fromWire`: the first entry with that code, else UNKNOWN. */
    private fun oldButtonFromWire(v: Int): ButtonAction = oldButtonWire.entries.firstOrNull { it.value == v }?.key ?: ButtonAction.UNKNOWN

    private val codes = (-1..40) + 255

    /** A `[1.9]` record of the Shortcut button's long press as `:protocol` parses it. */
    private fun config(action: Int, supported: Set<Int> = emptySet()) =
        ButtonConfig(0x80, 9, action, supported, emptySet(), tail = ByteArray(0), raw = ByteArray(0))

    // ---- Sidetone [1.11] ---------------------------------------------------------------------

    @Test fun `every sidetone level code reads as the old table read it`() {
        for (v in codes) assertEquals(oldSidetoneFromWire(v), sidetoneFromLevelCode(v), "level code $v")
        for (v in 0..40) {
            val state = assertNotNull(SidetoneCodec.parse(byteArrayOf(1, v.toByte(), 0x0f)))
            assertEquals(oldSidetoneFromWire(v), state.toSidetone(), "[1.11] 01 %02x 0f".format(v))
        }
    }

    @Test fun `every sidetone writes the old level code`() {
        assertEquals(oldSidetoneWire.keys.toList(), Sidetone.entries, "every level has a golden code")
        for ((level, code) in oldSidetoneWire) {
            assertEquals(code, level.toLevelCode(), "$level")
            assertEquals(level, sidetoneFromLevelCode(level.toLevelCode()), "$level round trip")
        }
        // The SET_GET payload setSidetone sends, byte for byte: [persist, level], 1 high .. 3 low.
        assertContentEquals(byteArrayOf(1, 0), SidetoneCodec.build(Sidetone.OFF.toLevelCode(), true))
        assertContentEquals(byteArrayOf(1, 3), SidetoneCodec.build(Sidetone.LOW.toLevelCode(), true))
        assertContentEquals(byteArrayOf(0, 2), SidetoneCodec.build(Sidetone.MEDIUM.toLevelCode(), false))
        assertContentEquals(byteArrayOf(1, 1), SidetoneCodec.build(Sidetone.HIGH.toLevelCode(), true))
    }

    @Test fun `the sidetone order the More screen lists stays off, low, medium, high`() {
        assertEquals(listOf(Sidetone.OFF, Sidetone.LOW, Sidetone.MEDIUM, Sidetone.HIGH), Sidetone.entries)
        assertEquals(listOf("Off", "Low", "Medium", "High"), Sidetone.entries.map { it.label })
    }

    // ---- ButtonAction [1.9] ------------------------------------------------------------------

    @Test fun `every action code reads as the old table read it`() {
        for (v in codes) {
            assertEquals(oldButtonFromWire(v), buttonActionFromCode(v), "action code $v")
            assertEquals(oldButtonFromWire(v), config(v).toButtonAssignment().action, "[1.9] action $v")
            assertEquals(oldButtonFromWire(v), config(v).toButtonPress(0).action, "[2.12] action $v")
        }
    }

    @Test fun `the codes core has no action for stay unknown and out of the choices`() {
        for (v in listOf(18) + (22..27)) assertEquals(ButtonAction.UNKNOWN, buttonActionFromCode(v), "code $v")
        val everything = config(14, supported = (0..40).toSet())
        // The old algorithm: sorted codes, fromWire, UNKNOWN dropped, distinct.
        val old = (0..40).map(::oldButtonFromWire).filter { it != ButtonAction.UNKNOWN }.distinct()
        assertEquals(old, everything.toButtonAssignment().supportedActions)
        assertEquals(ButtonAction.entries - ButtonAction.UNKNOWN, old, "every known action is offered, in code order")
    }

    @Test fun `every action writes the old code and keeps its stored id`() {
        assertEquals(oldButtonWire.keys.toList(), ButtonAction.entries, "every action has a golden code")
        for ((action, code) in oldButtonWire) {
            assertEquals(code, action.toActionCode(), "$action on the wire")
            assertEquals(code, action.storedId, "$action saved in the shortcut prefs")
            assertEquals(action, ButtonAction.fromStoredId(code), "$action read back from the prefs")
        }
        for (v in codes) assertEquals(oldButtonFromWire(v), ButtonAction.fromStoredId(v), "stored id $v")
        // The SET_GET payload setShortcutButton sends for the Shortcut button's long press.
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x09, 0x13), Buttons.build(0x80, 0x09, ButtonAction.SPATIAL_AUDIO.toActionCode()))
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x09, 0x0e), Buttons.build(0x80, 0x09, ButtonAction.DISABLED.toActionCode()))
    }

    @Test fun `the three actions Bose names differently map by meaning`() {
        assertEquals(dev.earshot.protocol.qcultra2.ButtonAction.VPA.code, ButtonAction.VOICE_ASSISTANT.toActionCode())
        assertEquals(dev.earshot.protocol.qcultra2.ButtonAction.SPOTIFY_GO.code, ButtonAction.SPOTIFY_TAP.toActionCode())
        assertEquals(dev.earshot.protocol.qcultra2.ButtonAction.SPATIAL_AUDIO_MODE.code, ButtonAction.SPATIAL_AUDIO.toActionCode())
        // Every other known action has the same name on both sides.
        for (action in ButtonAction.entries - setOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.SPOTIFY_TAP, ButtonAction.SPATIAL_AUDIO, ButtonAction.UNKNOWN)) {
            assertEquals(dev.earshot.protocol.qcultra2.ButtonAction.valueOf(action.name).code, action.toActionCode(), "$action")
        }
    }
}

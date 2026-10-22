package dev.earshot.service

import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.sony.SonyBatteryLevel
import dev.earshot.protocol.sony.SonyEqCapability
import dev.earshot.protocol.sony.SonyEqSetting
import dev.earshot.protocol.sony.SonyNcAsm
import dev.earshot.protocol.sony.SonyProtocolInfo
import dev.earshot.protocol.sony.SonyTable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure Sony mapping: modes by NCASM type, battery parts, level scales, EQ writes. */
class SonyStateMappingTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val v2 = SonyProtocolInfo(2, 0x03003018, table1 = true, table2 = true)

    @Test fun `the ambient level and Earshot's 0-10 scale convert both ways`() {
        val s = SonySnapshot(ambientRange = 1..20, ncAsm = SonyNcAsm(0x17, true, ncAsmMode = 1, ambientLevel = 1))
        assertEquals(10, SonyStateMapping.ambientAsCancelling(s))
        assertEquals(0, SonyStateMapping.ambientAsCancelling(s.copy(ncAsm = s.ncAsm!!.copy(ambientLevel = 20))))
        for (c in 0..10) {
            val level = SonyStateMapping.cancellingAsAmbient(s, c)
            assertTrue(level in 1..20)
            assertEquals(c, SonyStateMapping.ambientAsCancelling(s.copy(ncAsm = s.ncAsm!!.copy(ambientLevel = level))), "c=$c")
        }
    }

    @Test fun `an ambient-only model gets the ambient and off slots, a model without NCASM no modes`() {
        val linkbuds = SonySnapshot(protocol = v2, ncAsmType = 0x22, ncAsm = SonyNcAsm(0x22, true, ambientLevel = 10))
        val st = SonyStateMapping.apply(HeadphoneState(), linkbuds, now = 0)
        assertEquals(listOf(1, 2), st.modes.map { it.index })
        assertEquals(1, st.currentModeIndex)
        assertFalse(st.vendorControls.any { it.id == SonyStateMapping.FOCUS_ON_VOICE && it.value == null })
        val clip = SonyStateMapping.apply(HeadphoneState(), SonySnapshot(protocol = v2, modelName = "WF-LC900"), now = 0)
        assertTrue(clip.modes.isEmpty())
        assertFalse(clip.capabilities.listeningModes)
        assertEquals(FormFactor.OPEN_EARBUDS, clip.capabilities.formFactor)
    }

    @Test fun `earbuds - the headline is the lower bud, the case is its own part`() {
        val s = SonySnapshot(
            modelName = "WF-1000XM5",
            left = SonyBatteryLevel(80, charging = false), right = SonyBatteryLevel(60, charging = true), case = SonyBatteryLevel(40, charging = false),
        )
        val st = SonyStateMapping.apply(HeadphoneState(), s, now = 0)
        assertEquals(60, st.batteryPercent)
        assertEquals(true, st.isCharging)
        assertEquals(80, st.battery?.left?.percent)
        assertEquals(40, st.battery?.case?.percent)
        assertNull(st.battery?.overall)
        assertEquals(FormFactor.EARBUDS, st.capabilities.formFactor)
    }

    @Test fun `editing a band keeps a custom slot and moves a preset to Manual`() {
        val cap = SonyEqCapability(0x00, 6, 21, listOf(0x00, 0x10, 0xA0, 0xA1))
        val base = SonySnapshot(protocol = v2, eqType = 0x00, eqCapability = cap, eq = SonyEqSetting(0x00, 0x10, listOf(10, 9, 10, 12, 14, 15)))
        val m = SonyStateMapping.write(base, "eq_band_1", 3)!!
        assertEquals(SonyTable.T1, m.table)
        assertContentEquals(hex("58 00 a0 06 0a 0d 0a 0c 0e 0f"), m.payload)
        val custom = base.copy(eq = SonyEqSetting(0x00, 0xA1, List(6) { 10 }))
        assertContentEquals(hex("58 00 a1 06 0a 0a 0a 0a 0a 00"), SonyStateMapping.write(custom, "eq_band_5", -12)!!.payload, "clamped to -10 dB")
        assertContentEquals(hex("58 00 10 00"), SonyStateMapping.write(base, "eq_preset", 1)!!.payload)
        assertNull(SonyStateMapping.write(base, "eq_preset", 9))
        assertNull(SonyStateMapping.write(base, "eq_band_7", 0))
    }

    @Test fun `controls only for what the headset announced`() {
        val bare = SonySnapshot(protocol = v2)
        assertTrue(SonyStateMapping.controls(bare).isEmpty())
        assertNull(SonyStateMapping.write(bare, SonyStateMapping.SPEAK_TO_CHAT, 1))
        assertNull(SonyStateMapping.write(bare, SonyStateMapping.VOICE_GUIDANCE, 1))
        assertNull(SonyStateMapping.write(bare, "general_d1", 1))
    }

    @Test fun `pushes and replies fold in the same way`() {
        var s = SonySnapshot(protocol = v2).withSupport(setOf(0x6B, 0x20), emptySet())
        s = SonyStateMapping.update(s, dev.earshot.protocol.sony.SonyMessage(SonyTable.T1, hex("67 17 01 01 00 00 0a")))
        assertEquals(0, SonyStateMapping.apply(HeadphoneState(), s, now = 0).currentModeIndex)
        s = SonyStateMapping.update(s, dev.earshot.protocol.sony.SonyMessage(SonyTable.T1, hex("69 17 01 00 00 00 0a")))
        assertEquals(2, SonyStateMapping.apply(HeadphoneState(), s, now = 0).currentModeIndex)
        s = SonyStateMapping.update(s, dev.earshot.protocol.sony.SonyMessage(SonyTable.T1, hex("25 00 1e 00")))
        assertEquals(30, SonyStateMapping.apply(HeadphoneState(), s, now = 0).batteryPercent)
        // Another NCASM type than the one announced is ignored.
        val ignored = SonyStateMapping.update(s, dev.earshot.protocol.sony.SonyMessage(SonyTable.T1, hex("69 22 01 01 00 05")))
        assertEquals(s, ignored)
    }
}

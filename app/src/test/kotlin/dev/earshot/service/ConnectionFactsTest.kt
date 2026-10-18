package dev.earshot.service

import dev.earshot.core.BatteryDetail
import dev.earshot.core.BatteryLevel
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.qcultra2.Battery
import dev.earshot.protocol.qcultra2.BoseModel
import dev.earshot.protocol.qcultra2.CncState
import dev.earshot.protocol.qcultra2.Eq
import dev.earshot.protocol.qcultra2.ModeConfig
import dev.earshot.protocol.qcultra2.ModeConfigs
import dev.earshot.protocol.qcultra2.ModeLayout
import dev.earshot.protocol.qcultra2.ModePrompt
import dev.earshot.protocol.qcultra2.ModesCapabilities
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ConnectionFacts] and the 0.4.0 state mappings: device-reported facts beat the catalogue. */
class ConnectionFactsTest {

    private fun caps(hex: String) = ModesCapabilities.parse(hex.hexToBytes())!!

    @Test
    fun `31_2 counts replace the catalogue's slot plan`() {
        // QC Ultra 1st Gen guessed at 3 + 7; a unit reporting 4 + 6 wins.
        val f = ConnectionFacts(model = BoseModel.QC_ULTRA_1, modes = caps("040600000077"))
        assertEquals(0..3, f.presetSlots)
        assertEquals(4..9, f.editableSlots)
        assertEquals(10, f.slotCount)
        // [31.8] byte 0 wins over both.
        assertEquals(11, f.copy(favoritesTotal = 11).slotCount)
        // A QC45 whose firmware reports no custom modes cannot edit (docs/bose-models.md §D7).
        val oldQc45 = ConnectionFacts(model = BoseModel.QC45, modes = caps("020000000019"))
        assertFalse(oldQc45.canEditModes)
        assertFalse(oldQc45.capabilities(NoisePath.SLOTS, modesAvailable = true).editableModes)
        // Without [31.2] the catalogue stands.
        assertEquals(BoseModel.QC45.editableSlots, ConnectionFacts(model = BoseModel.QC45).editableSlots)
    }

    @Test
    fun `31_2 flags drive the noise panel together with the slot layout`() {
        val ultra = ConnectionFacts(model = BoseModel.QC_ULTRA_2, modes = caps("04070000007f02"), seenLayout = ModeLayout.STANDARD)
        val live = ultra.capabilities(NoisePath.LIVE, modesAvailable = true)
        assertTrue(live.liveNoiseControl && live.ancToggle && live.windBlock && live.spatialAudio && live.favoriteModes)

        // Flags without ANC / wind / favourites (an unknown firmware): the UI hides them.
        val trimmed = ultra.copy(modes = caps("040700000005")).capabilities(NoisePath.LIVE, modesAvailable = true)
        assertFalse(trimmed.ancToggle)
        assertTrue(trimmed.ancAlwaysOn)
        assertFalse(trimmed.windBlock)
        assertFalse(trimmed.favoriteModes)
        assertTrue(trimmed.spatialAudio)

        // Slot rewrites can only carry what the slot has: 44 bytes = the level alone.
        val basic = ultra.copy(seenLayout = ModeLayout.BASIC).capabilities(NoisePath.SLOTS, modesAvailable = true)
        assertFalse(basic.spatialAudio || basic.windBlock || basic.ancToggle)
        assertFalse(basic.liveNoiseControl)
    }

    @Test
    fun `the direct path offers the level and ANC only where 1_5 allows`() {
        val lando = ConnectionFacts(model = BoseModel.QC_EARBUDS, cnc = CncState(11, 0, 0x01))
        val c = lando.capabilities(NoisePath.DIRECT, modesAvailable = true)
        assertTrue(c.liveNoiseControl)
        assertTrue(c.ancToggle)
        assertFalse(c.windBlock || c.spatialAudio)
        assertEquals(FormFactor.EARBUDS, c.formFactor)
        assertTrue(c.supportNote!!.startsWith("Basic support"))
        assertFalse(lando.copy(cnc = CncState(11, 0, 0x03)).capabilities(NoisePath.DIRECT, true).ancToggle)
        val readOnly = lando.capabilities(NoisePath.READ_ONLY, modesAvailable = false)
        assertFalse(readOnly.liveNoiseControl || readOnly.ancToggle || readOnly.listeningModes)
    }

    @Test
    fun `unsupported products offer nothing but carry their note`() {
        val c = ConnectionFacts(model = BoseModel.QC35).capabilities(NoisePath.NONE, modesAvailable = true)
        assertFalse(c.listeningModes || c.liveNoiseControl || c.noiseCancelling || c.editableModes || c.favoriteModes)
        assertTrue(c.supportNote!!.contains("Bose Connect"))
        assertEquals(c, BoseModel.QC35.toCapabilities())
    }

    @Test
    fun `an unknown product keeps the QC Ultra 2 defaults but is named from 0_15`() {
        val f = ConnectionFacts(catalogued = false, unknownProductName = "Bose Foo Earbuds")
        assertEquals("Foo Earbuds", f.family)
        assertNull(f.generation)
        assertEquals(FormFactor.EARBUDS, f.capabilities(NoisePath.LIVE, true).formFactor)
        assertNull(f.capabilities(NoisePath.LIVE, true).supportNote)
        assertEquals(BoseModel.QC_ULTRA_2.slotCount, f.slotCount)
        assertNull(ConnectionFacts(catalogued = false).family, "no name, no family")
        assertEquals("QuietComfort Ultra Headphones", ConnectionFacts().family)
    }

    @Test
    fun `battery records map by component id with minutes`() {
        val buds = Battery.parse("3c00b401 3affff02 3affff04 50ffff03".replace(" ", "").hexToBytes())
        assertEquals(
            BatteryDetail(overall = BatteryLevel(58), left = BatteryLevel(58), right = BatteryLevel(60, 180), case = BatteryLevel(80)),
            buds.toBatteryDetail(),
        )
        val headphones = HeadphoneState().withBattery(Battery.parse("5affff00".hexToBytes()))
        assertEquals(90, headphones.batteryPercent)
        assertEquals(BatteryDetail(overall = BatteryLevel(90)), headphones.battery)
        // Id 0 is the overall figure even when a combined record is there too.
        assertEquals(BatteryLevel(40), Battery.parse("28ffff00 32ffff04".replace(" ", "").hexToBytes()).toBatteryDetail()?.overall)
        assertNull(Battery.parse(ByteArray(0)).toBatteryDetail())
    }

    @Test
    fun `listening modes are named by prompt and the plan decides presets`() {
        fun slot(index: Int, prompt: ModePrompt, name: String, editable: Boolean): ModeConfig {
            val p = FakeRequester.modeStatus(index, name, cncWire = 0, editable = editable).payload
            p[2] = prompt.code.toByte()
            return ModeConfigs.parse(p)!!
        }
        val plan = ConnectionFacts(model = BoseModel.ULTRA_OPEN_EARBUDS_2, modes = caps("030300000014"))
        assertEquals("Cinema", slot(2, ModePrompt.CINEMA, "Kino", editable = false).toListeningMode(false, plan).name)
        assertTrue(slot(2, ModePrompt.CINEMA, "Kino", editable = false).toListeningMode(false, plan).isPreset)
        assertEquals("Commute", slot(3, ModePrompt.COMMUTE, "Pendeln", editable = true).toListeningMode(false, plan).name)
        // A freed slot keeps "None"; a leftover prompt must not bring it back.
        val freed = slot(4, ModePrompt.HOME, "None", editable = true).toListeningMode(false, plan)
        assertFalse(freed.isConfigured)
        assertEquals("", freed.name)
    }

    @Test
    fun `the EQ range comes from the bands and is kept within 10`() {
        assertEquals(-10..10, Eq.parse("f60a0000f60afe01f60afa02".hexToBytes()).eqRange())
        assertEquals(-6..6, Eq.parse("fa060000fa060001fa060002".hexToBytes()).eqRange())
        assertEquals(-10..10, Eq.parse("f00f0000f00f0001f00f0002".hexToBytes()).eqRange(), "±16 clamps to what HeadphoneEq holds")
        assertNull(Eq.parse(ByteArray(0)).eqRange())
        val s = HeadphoneState().withEq(Eq.parse("fa060300fa060001fa06fa02".hexToBytes()))
        assertEquals(-6..6, s.eqRange)
        assertEquals(3, s.eq?.bass)
    }
}

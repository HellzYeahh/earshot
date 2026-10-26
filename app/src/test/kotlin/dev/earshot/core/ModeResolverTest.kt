package dev.earshot.core

import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.protocol.qcultra2.BoseModel
import dev.earshot.protocol.qcultra2.ModeConfig
import dev.earshot.protocol.qcultra2.ModeConfigs
import dev.earshot.protocol.qcultra2.ModePrompt
import dev.earshot.protocol.sony.SonyNcAsm
import dev.earshot.service.FakeRequester
import dev.earshot.service.SennheiserStateMapping
import dev.earshot.service.SonySnapshot
import dev.earshot.service.SonyStateMapping
import dev.earshot.service.SoundcoreModelMapping
import dev.earshot.service.SoundcoreStateMapping
import dev.earshot.service.buildModeList
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Meaning per vendor, built from the drivers' real mode lists where they are reachable. The name
 * inference for the frozen stacks (Bose, Bragi, Sleep A30) matches English literals: these tests
 * are the tripwire, so renaming or localising one of those modes breaks a test, not users' rules.
 */
class ModeResolverTest {

    private val boseAddress = "68:F2:1F:12:34:56"
    private val otherAddress = "AC:80:0A:00:00:01"

    private fun meanings(modes: List<ListeningMode>): Map<Int, ModeMeaning> =
        modes.filter { it.isConfigured }.associate { it.index to ModeResolver.meaningOf(it) }

    /** A Bose `[31.6]` STATUS for [index], with the voice prompt that names it (bytes 1-2). */
    private fun boseSlot(index: Int, name: String, prompt: ModePrompt? = null): Pair<Int, ModeConfig> {
        val payload = FakeRequester.modeStatus(index, name, cncWire = 0).payload.copyOf()
        if (prompt != null) payload[2] = prompt.code.toByte()
        return index to ModeConfigs.parse(payload)!!
    }

    /** A QC Ultra 2 after the dump: four presets, the user's Home and Gym, and a custom mode the user named "Quiet". */
    private fun qcUltra2(): List<ListeningMode> = buildModeList(
        configs = mapOf(
            boseSlot(0, "Quiet", ModePrompt.QUIET),
            boseSlot(1, "Aware", ModePrompt.AWARE),
            boseSlot(2, "Immersion", ModePrompt.IMMERSION),
            // A localised firmware name: the prompt still names it "Cinema".
            boseSlot(3, "Kino", ModePrompt.CINEMA),
            boseSlot(4, "Home", ModePrompt.HOME),
            boseSlot(5, "Gym"),
            boseSlot(6, "Quiet"),
        ),
        favorites = null,
        previous = emptyList(),
        plan = BoseModel.QC_ULTRA_2,
    )

    private fun state(modes: List<ListeningMode>, address: String?) =
        HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTED, "x", address), modes = modes)

    /** CrusherProtocol.withMode's list (built inside the protocol from a hearing-mode frame). */
    private val crusher = listOf(
        ListeningMode(0, "Quiet", isPreset = true, isEditable = false, isConfigured = true),
        ListeningMode(1, "Aware", isPreset = true, isEditable = false, isConfigured = true),
        ListeningMode(2, "Off", isPreset = true, isEditable = false, isConfigured = true),
    )

    @Test fun `Bose presets are Quiet and Aware by prompt, everything else is custom`() {
        val modes = qcUltra2()
        assertEquals(
            mapOf(
                0 to ModeMeaning.QUIET, 1 to ModeMeaning.AWARE, 2 to ModeMeaning.CUSTOM, 3 to ModeMeaning.CUSTOM,
                4 to ModeMeaning.CUSTOM, 5 to ModeMeaning.CUSTOM, 6 to ModeMeaning.CUSTOM,
            ),
            meanings(modes),
        )
        // The user's own "Quiet" is a custom slot: a QUIET ref goes to the built-in one.
        assertEquals("Quiet", modes[6].name)
        assertEquals(0, ModeResolver.slotFor(ModeRef(ModeMeaning.QUIET), modes, boseAddress))
        assertEquals(6, ModeResolver.slotFor(ModeRef(ModeMeaning.CUSTOM, name = "quiet"), modes, boseAddress))
    }

    @Test fun `Bose placeholders before the dump already carry their meaning`() {
        val ultra = buildModeList(emptyMap(), null, emptyList(), BoseModel.QC_ULTRA_2)
        assertEquals(mapOf(0 to ModeMeaning.QUIET, 1 to ModeMeaning.AWARE, 2 to ModeMeaning.CUSTOM, 3 to ModeMeaning.CUSTOM), meanings(ultra))
        val qc45 = buildModeList(emptyMap(), null, emptyList(), BoseModel.QC45)
        assertEquals(mapOf(0 to ModeMeaning.QUIET, 1 to ModeMeaning.AWARE), meanings(qc45))
    }

    @Test fun `the Ultra Open has no quiet mode`() {
        val open = buildModeList(emptyMap(), null, emptyList(), BoseModel.ULTRA_OPEN_EARBUDS_2)
        assertEquals("Stereo", open.first { it.index == 0 }.name)
        assertEquals(setOf(ModeMeaning.CUSTOM), meanings(open).values.toSet())
        assertNull(ModeResolver.slotFor(ModeRef(ModeMeaning.QUIET), open, boseAddress))
        assertEquals(1, ModeResolver.slotFor(ModeRef(ModeMeaning.CUSTOM, name = "Immersion"), open, boseAddress))
    }

    @Test fun `the fake QC Ultra matches the real one`() {
        val modes = FakeHeadphoneRepository.connectedState().modes
        assertEquals(mapOf(0 to ModeMeaning.QUIET, 1 to ModeMeaning.AWARE, 2 to ModeMeaning.CUSTOM, 3 to ModeMeaning.CUSTOM, 4 to ModeMeaning.CUSTOM), meanings(modes))
    }

    @Test fun `Crusher and Method are Quiet, Aware, Off by name`() {
        assertEquals(mapOf(0 to ModeMeaning.QUIET, 1 to ModeMeaning.AWARE, 2 to ModeMeaning.OFF), meanings(crusher))
    }

    @Test fun `the Sleep A30 is noise cancelling and off, with no aware mode`() {
        val a30 = SoundcoreStateMapping.MODES
        assertEquals(mapOf(0 to ModeMeaning.QUIET, 1 to ModeMeaning.OFF), meanings(a30))
        // The 0.75 bug: a Bose "Aware" rule (slot 1) turned the A30's noise cancelling off.
        assertNull(ModeResolver.slotFor(ModeRef(ModeMeaning.AWARE, "Aware", boseAddress, 1), a30, otherAddress))
        assertEquals(1, ModeResolver.slotFor(ModeRef(ModeMeaning.OFF), a30, otherAddress))
    }

    @Test fun `the experimental drivers say what their modes mean`() {
        val expected = mapOf(0 to ModeMeaning.QUIET, 1 to ModeMeaning.AWARE, 2 to ModeMeaning.OFF)
        assertEquals(expected, meanings(SoundcoreModelMapping.MODES))
        assertEquals(expected, meanings(SennheiserStateMapping.MODES))
        assertEquals(expected, meanings(SonyStateMapping.modes(SonySnapshot(ncAsm = SonyNcAsm(0x17, true)))))
        // Not by name: Soundcore's "Normal" is Off.
        SoundcoreModelMapping.MODES.forEach { assertEquals(true, it.meaning != null, it.name) }
    }

    @Test fun `a Bose Immersion rule no longer turns the Crusher off`() {
        val immersion = ModeResolver.refFor(qcUltra2()[2], boseAddress)
        assertEquals(ModeRef(ModeMeaning.CUSTOM, "Immersion", boseAddress, 2), immersion)
        assertNull(ModeResolver.slotFor(immersion, crusher, otherAddress))
        assertNull(ModeResolver.slotFor(immersion, SennheiserStateMapping.MODES, otherAddress))
        // While Quiet and Aware still mean the same thing everywhere.
        val aware = ModeResolver.refFor(qcUltra2()[1], boseAddress)
        assertEquals(1, ModeResolver.slotFor(aware, crusher, otherAddress))
        assertEquals(1, ModeResolver.slotFor(aware, SoundcoreModelMapping.MODES, otherAddress))
    }

    @Test fun `Sony resolves by slot index when it lacks noise cancelling`() {
        // Type 0x22 (ASM seamless, LinkBuds): ambient and off only, slots 1 and 2.
        val linkbuds = SonyStateMapping.modes(SonySnapshot(ncAsm = SonyNcAsm(0x22, true, ambientLevel = 10)))
        assertEquals(listOf(1, 2), linkbuds.map { it.index })
        assertNull(ModeResolver.slotFor(ModeRef(ModeMeaning.QUIET), linkbuds, otherAddress))
        assertEquals(1, ModeResolver.slotFor(ModeRef(ModeMeaning.AWARE), linkbuds, otherAddress))
        assertEquals(2, ModeResolver.slotFor(ModeRef(ModeMeaning.OFF), linkbuds, otherAddress))
        assertEquals(listOf(1, 2), ModeResolver.defaultCycle(linkbuds))
    }

    @Test fun `on the headset it was picked on, the same slot wins even after a rename`() {
        val gym = ModeResolver.refFor(qcUltra2()[5], boseAddress.lowercase())
        assertEquals(ModeRef(ModeMeaning.CUSTOM, "Gym", boseAddress, 5), gym)
        val renamed = qcUltra2().map { if (it.index == 5) it.copy(name = "Run") else it }
        assertEquals(5, ModeResolver.slotFor(gym, state(renamed, boseAddress)))
        // Another headset: by name only.
        val other = qcUltra2().map { if (it.index == 5) it.copy(name = "Run") else if (it.index == 6) it.copy(name = "GYM") else it }
        assertEquals(6, ModeResolver.slotFor(gym, other, otherAddress))
        assertNull(ModeResolver.slotFor(gym, renamed, otherAddress))
        // A slot that was freed on the same headset falls back to the name too.
        val freed = qcUltra2().map { if (it.index == 5) it.copy(isConfigured = false) else it }
        assertNull(ModeResolver.slotFor(gym, freed, boseAddress))
    }

    @Test fun `a built-in meaning on its own headset needs the same meaning in that slot`() {
        // Picked on the A30 (slot 1 = Off); the same address now reports Aware in slot 1 (a Bose
        // demo device reusing it, say): the meaning wins.
        val off = ModeResolver.refFor(SoundcoreStateMapping.MODES[1], otherAddress)
        assertEquals(2, ModeResolver.slotFor(off, crusher, otherAddress))
    }

    @Test fun `the 0_75 slot is the last resort, and only where that slot exists`() {
        val legacyImmersion = ModeRef(ModeMeaning.CUSTOM, "Immersion", boseAddress, 2, legacySlot = 2)
        // Kept 0.75 behaviour for migrated rules: slot 2 is Off on the Crusher.
        assertEquals(2, ModeResolver.slotFor(legacyImmersion, crusher, otherAddress))
        assertNull(ModeResolver.slotFor(legacyImmersion, SoundcoreStateMapping.MODES, otherAddress))
        // Bound first on a Sleep A30 (slot 1 = Off): on the Bose, Off doesn't resolve, so slot 1 = Aware, as in 0.75.
        val boundOnA30 = ModeResolver.refForLegacySlot(1, SoundcoreStateMapping.MODES, otherAddress)
        assertEquals(ModeRef(ModeMeaning.OFF, "Off", otherAddress, 1, legacySlot = 1), boundOnA30)
        assertEquals(1, ModeResolver.slotFor(boundOnA30, qcUltra2(), boseAddress))
        // A slot the headset doesn't have becomes a custom ref that only knows the number.
        assertEquals(ModeRef(ModeMeaning.CUSTOM, legacySlot = 9), ModeResolver.refForLegacySlot(9, qcUltra2(), boseAddress))
    }

    @Test fun `an explicit meaning beats the name`() {
        val m = ListeningMode(0, "Quiet", isPreset = true, isEditable = false, isConfigured = true, meaning = ModeMeaning.OFF)
        assertEquals(ModeMeaning.OFF, ModeResolver.meaningOf(m))
    }

    @Test fun `the default cycle is the first three configured slots`() {
        assertEquals(listOf(0, 1, 2), ModeResolver.defaultCycle(qcUltra2()))
        val qc45 = buildModeList(mapOf(boseSlot(4, "Home", ModePrompt.HOME)), null, emptyList(), BoseModel.QC45)
        assertEquals(listOf(0, 1, 4), ModeResolver.defaultCycle(qc45))
        assertEquals(listOf(0, 1), ModeResolver.defaultCycle(SoundcoreStateMapping.MODES))
        assertEquals(emptyList(), ModeResolver.defaultCycle(emptyList()))
    }

    @Test fun `labels and names`() {
        assertEquals("Gym", ModeResolver.label(ModeRef(ModeMeaning.CUSTOM, "Gym")))
        assertEquals("Noise cancelling", ModeResolver.label(ModeRef(ModeMeaning.QUIET)))
        assertEquals("mode 7", ModeResolver.label(ModeRef(ModeMeaning.CUSTOM, legacySlot = 7)))
        val modes = qcUltra2()
        // A resolved ref shows the mode's live name; an unresolved one its label; a 0.75 action its slot's name.
        assertEquals("Aware", ModeResolver.nameOf(AutomationAction.SetMode(9, ModeRef(ModeMeaning.AWARE)), modes, boseAddress))
        assertEquals("Off", ModeResolver.nameOf(AutomationAction.SetMode(2, ModeRef(ModeMeaning.OFF)), modes, boseAddress))
        assertEquals("Gym", ModeResolver.nameOf(AutomationAction.SetMode(5), modes, boseAddress))
        assertNull(ModeResolver.nameOf(AutomationAction.SetMode(9), modes, boseAddress))
    }

    @Test fun `meanings are stored lower-case and an unknown one reads as custom`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals("""{"meaning":"aware"}""", json.encodeToString(ModeRef.serializer(), ModeRef(ModeMeaning.AWARE)))
        assertEquals(listOf("quiet", "aware", "off", "adaptive", "custom"), ModeMeaning.entries.map { it.storedName })
        assertEquals(ModeMeaning.CUSTOM, json.decodeFromString(ModeRef.serializer(), """{"meaning":"telepathy","name":"Zen"}""").meaning)
        assertEquals(ModeMeaning.OFF, json.decodeFromString(ModeRef.serializer(), """{"meaning":"off"}""").meaning)
    }
}

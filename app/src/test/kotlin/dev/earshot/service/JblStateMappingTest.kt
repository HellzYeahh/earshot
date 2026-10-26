package dev.earshot.service

import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeRef
import dev.earshot.core.ModeResolver
import dev.earshot.protocol.jbl.JblCommands as C
import dev.earshot.protocol.jbl.JblFrameBuffer
import dev.earshot.protocol.jbl.JblModel
import dev.earshot.protocol.jbl.JblStatus
import dev.earshot.protocol.jbl.JblWriteGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [JblStateMapping]: pure, over statuses folded from the captured Wave Buds 2 frames and shaped variants of them. */
class JblStateMappingTest {

    private fun status(vararg frames: ByteArray): JblStatus {
        val buffer = JblFrameBuffer()
        return frames.flatMap { buffer.feed(it) }.fold(JblStatus()) { s, f -> s.fold(f) }
    }

    private fun captured(vararg hex: String) = status(*hex.map(JblCaptured::bytes).toTypedArray())

    private val waveBuds = captured(
        JblCaptured.DEVICE_INFO, JblCaptured.CALL_PUSH, JblCaptured.MODES, JblCaptured.INFO, JblCaptured.EQ, JblCaptured.VOICE_PROMPTS,
    )

    private fun map(
        s: JblStatus,
        model: JblModel? = JblModel.fromPid(s.info?.productId),
        answered: Set<JblFeature> = JblStateMapping.reported(s),
    ): HeadphoneState = JblStateMapping.apply(HeadphoneState(), s, model, answered, now = 7)

    // ---- The captured Wave Buds 2 -------------------------------------------------------------

    @Test fun `the captured Wave Buds 2 maps to four modes with Ambient Aware on`() {
        val state = map(waveBuds)
        assertEquals(listOf(0, 1, 2, 3), state.modes.map { it.index })
        assertEquals(listOf("Noise cancelling", "Ambient Aware", "Off", "TalkThru"), state.modes.map { it.name })
        assertTrue(state.modes.all { it.isConfigured && it.isPreset && !it.isEditable })
        assertEquals(JblStateMapping.MODE_AMBIENT, state.currentModeIndex)
        assertEquals("Ambient Aware", state.currentMode?.name)
        assertNull(state.noise, "no live noise card on this protocol")
        assertNull(state.eq, "the headset's ten-band EQ is not the app's three-band one")
        assertEquals(7, state.lastUpdatedMillis)
    }

    @Test fun `the captured batteries are left 100, right unknown, case 100`() {
        val state = map(waveBuds)
        val battery = assertNotNull(state.battery)
        assertEquals(100, battery.left?.percent)
        assertNotNull(battery.right, "the right bud is reported, in its case")
        assertNull(battery.right?.percent)
        assertEquals(100, battery.case?.percent)
        assertNull(battery.overall)
        assertEquals(100, state.batteryPercent, "the headline is the lower bud that reports")
        assertEquals(false, state.isCharging)
    }

    @Test fun `the captured identity is JBL Wave Buds 2 on firmware 2_7_0`() {
        val state = map(waveBuds)
        assertEquals("JBL Wave Buds 2", state.info?.productName)
        assertEquals("JBL Wave Buds 2", state.info?.modelName)
        assertEquals("2.7.0", state.info?.firmwareVersion)
        assertEquals(0x2100, state.info?.productId)
        assertEquals(FormFactor.EARBUDS, state.capabilities.formFactor)
    }

    @Test fun `the captured settings are an EQ preset on Off and voice prompts on, with no level slider`() {
        val state = map(waveBuds)
        assertEquals(listOf(JblStateMapping.EQ_PRESET, JblStateMapping.VOICE_PROMPTS), state.vendorControls.map { it.id })
        assertTrue(state.vendorControls.none { it.id == JblStateMapping.ANC_LEVEL }, "the Wave Buds 2 never answered the level read")

        val eq = state.vendorControls.first { it.id == JblStateMapping.EQ_PRESET }
        assertEquals(listOf("Off", "Jazz", "Vocal", "Bass", "Club", "Studio", "Extreme Bass"), eq.options)
        assertEquals(0, eq.value)
        assertNull(eq.subtitle)
        assertEquals(1, state.vendorControls.first { it.id == JblStateMapping.VOICE_PROMPTS }.value)
    }

    @Test fun `the capabilities say what is driven and that it is experimental`() {
        val caps = map(waveBuds).capabilities
        assertTrue(caps.listeningModes && caps.modesOnHome && caps.liveNoiseControl)
        assertFalse(caps.noiseCancelling || caps.ancToggle || caps.editableModes || caps.favoriteModes)
        assertFalse(caps.spatialAudio || caps.windBlock || caps.verified)
        val note = assertNotNull(caps.supportNote)
        assertTrue(note.startsWith("Experimental:"), note)
        assertTrue("jbl-control" in note && "not yet tried on our own headphones" in note, note)
    }

    // ---- Modes ------------------------------------------------------------------------------

    @Test fun `unlisted modes are empty slots, so the numbering stays and nothing can pick them`() {
        val s = status(JblCaptured.modeStatus(listOf(1, 2), 1))
        val state = map(s, model = null)
        assertEquals(listOf(0, 1, 2, 3), state.modes.map { it.index })
        assertEquals(listOf(true, true, true, false), state.modes.map { it.isConfigured }, "TalkThru was not listed")
        assertFalse(state.modes[3].isPreset, "the carousel shows presets and configured modes; an empty slot is neither")
        assertEquals(JblStateMapping.MODE_ANC, state.currentModeIndex)

        val onlyAmbient = map(status(JblCaptured.modeStatus(listOf(2), 0)), model = null)
        assertEquals(listOf(false, true, true, false), onlyAmbient.modes.map { it.isConfigured })
        assertEquals(JblStateMapping.MODE_OFF, onlyAmbient.currentModeIndex, "all pairs off is Off")
        assertEquals(0, onlyAmbient.modes.minOf { it.index }, "slots always start at 0")
    }

    @Test fun `a headset that listed no modes has no mode UI`() {
        val state = map(status(JblCaptured.deviceInfo("JBL Wave Buds 2", 0x2100, 0x64)))
        assertTrue(state.modes.isEmpty())
        assertNull(state.currentModeIndex)
        assertFalse(state.capabilities.listeningModes)
        assertTrue(state.vendorControls.isEmpty())
        assertTrue(state.capabilities.supportNote!!.startsWith("Experimental"))
    }

    @Test fun `every slot has its meaning, listed or not`() {
        val listed = map(waveBuds).modes
        val unlisted = map(status(JblCaptured.modeStatus(listOf(2), 2)), model = null).modes
        for (modes in listOf(listed, unlisted)) {
            assertEquals(
                listOf(ModeMeaning.QUIET, ModeMeaning.AWARE, ModeMeaning.OFF, ModeMeaning.CUSTOM),
                modes.map { ModeResolver.meaningOf(it) },
            )
            assertTrue(modes.all { it.meaning != null }, "explicit, not inferred from the English names")
        }
        // Rules saved by meaning resolve to the slots.
        val state = map(waveBuds)
        assertEquals(0, ModeResolver.slotFor(ModeRef(ModeMeaning.QUIET), state))
        assertEquals(1, ModeResolver.slotFor(ModeRef(ModeMeaning.AWARE), state))
        assertEquals(2, ModeResolver.slotFor(ModeRef(ModeMeaning.OFF), state))
        assertEquals(3, ModeResolver.slotFor(ModeRef(ModeMeaning.CUSTOM, name = "TalkThru"), state))
        assertNull(ModeResolver.slotFor(ModeRef(ModeMeaning.QUIET), map(status(JblCaptured.modeStatus(listOf(2), 2)), model = null)), "no noise cancelling listed")
    }

    @Test fun `the wire numbers map to the slots both ways`() {
        for ((wire, slot) in listOf(0 to 2, 1 to 0, 2 to 1, 3 to 3)) assertEquals(slot, JblStateMapping.slotOfActive(wire)?.index, "wire $wire")
        assertNull(JblStateMapping.slotOfActive(null))
        assertNull(JblStateMapping.slotOfActive(9))
        assertEquals(listOf(C.WIRE_ANC, C.WIRE_AMBIENT, null, C.WIRE_TALKTHRU), JblStateMapping.SLOTS.map { it.wire })
        assertNull(JblStateMapping.slot(4))
    }

    // ---- Headphones and the older bud read ---------------------------------------------------

    @Test fun `headphones show the headband battery as the one figure`() {
        val s = status(
            JblCaptured.deviceInfo("JBL Tour One M2", 0x2085, 0x80 or 55),
            JblCaptured.modeStatus(listOf(1, 2), 1),
            JblCaptured.tlv(mapOf(C.FID_BATTERY_HEADBAND to listOf(0, 80))),
        )
        val state = map(s)
        assertEquals(FormFactor.HEADPHONES, state.capabilities.formFactor)
        assertEquals("JBL Tour One M2", state.info?.modelName)
        assertEquals(80, state.batteryPercent, "the headband figure beats the device-info byte")
        assertEquals(80, state.battery?.overall?.percent)
        assertNull(state.battery?.left)
        assertEquals(false, state.isCharging)

        val noHeadband = map(status(JblCaptured.deviceInfo("JBL Tour One M2", 0x2085, 0x80 or 55), JblCaptured.modeStatus(listOf(1, 2), 1)))
        assertEquals(55, noHeadband.batteryPercent, "else the device-info byte")
        assertEquals(true, noHeadband.isCharging)
    }

    @Test fun `the older earbud read gives one figure, the lower bud, and any bud charging counts`() {
        val s = status(
            JblCaptured.deviceInfo("JBL Tune Buds", 0x20B9, 0x64),
            JblCaptured.bytes("aa 25 07 01 00 01 50 5a 00 40"),
        )
        val state = map(s)
        assertEquals(FormFactor.EARBUDS, state.capabilities.formFactor)
        assertEquals(80, state.batteryPercent)
        assertEquals(80, state.battery?.overall?.percent)
        assertNull(state.battery?.left)
        assertNull(state.battery?.right)
        assertEquals(64, state.battery?.case?.percent)
        assertEquals(true, state.isCharging)
    }

    @Test fun `with both buds in the case the device-info byte is the headline`() {
        val s = status(
            JblCaptured.deviceInfo("JBL Wave Buds 2", 0x2100, 75),
            JblCaptured.tlv(mapOf(C.FID_BATTERY_LEFT to listOf(0, 0xFF), C.FID_BATTERY_RIGHT to listOf(0, 0xFF), C.FID_BATTERY_CASE to listOf(0, 60))),
        )
        val state = map(s)
        assertEquals(75, state.batteryPercent)
        assertNull(state.battery?.left?.percent)
        assertEquals(60, state.battery?.case?.percent)
    }

    @Test fun `no battery figure at all leaves the state's alone`() {
        val before = HeadphoneState(batteryPercent = 42, isCharging = true)
        val state = JblStateMapping.apply(before, JblStatus(), null, emptySet(), now = 1)
        assertEquals(42, state.batteryPercent)
        assertEquals(true, state.isCharging)
    }

    // ---- Vendor controls -----------------------------------------------------------------------

    @Test fun `the level slider appears only once the level was answered`() {
        val withLevel = waveBuds.fold(JblFrameBuffer().feed(JblCaptured.level(5)).single())
        assertEquals(5, withLevel.ancLevel)

        val shown = JblStateMapping.controls(withLevel, JblStateMapping.reported(withLevel))
        assertEquals(listOf(JblStateMapping.ANC_LEVEL, JblStateMapping.EQ_PRESET, JblStateMapping.VOICE_PROMPTS), shown.map { it.id })
        val slider = shown.first()
        assertEquals("Noise-cancelling level", slider.title)
        assertEquals(1..7, slider.range)
        assertEquals(5, slider.value)
        assertTrue(slider.onHome)
        assertNull(slider.options)

        // Seen in the status but never answered by a probe: not shown.
        assertTrue(JblStateMapping.controls(withLevel, emptySet()).isEmpty())
    }

    @Test fun `a custom EQ reads as no preset and says it is kept`() {
        for (category in listOf(C.EQ_CUSTOM, 4)) {
            val s = status(JblCaptured.eqReturn(category))
            val eq = JblStateMapping.controls(s, JblStateMapping.reported(s)).single()
            assertNull(eq.value, "category $category is not one of the app's presets")
            assertTrue(eq.subtitle!!.contains("custom EQ"), eq.subtitle)
        }
        val club = status(JblCaptured.eqReturn(6))
        assertEquals(C.EQ_PRESETS.indexOfFirst { it.label == "Club" }, JblStateMapping.controls(club, JblStateMapping.reported(club)).single().value)
    }

    @Test fun `voice prompts off reads as 0`() {
        val s = status(JblCaptured.voicePrompts(false))
        assertEquals(0, JblStateMapping.controls(s, JblStateMapping.reported(s)).single().value)
    }

    @Test fun `the write for a control is the frame the app sends, or null`() {
        assertEquals(C.ancLevelSet(5), JblStateMapping.write(JblStateMapping.ANC_LEVEL, 5))
        val bass = C.EQ_PRESETS.indexOfFirst { it.label == "Bass" }
        val preset = C.EQ_PRESETS[bass]
        assertEquals(C.eqSet(preset.category, preset.gainsDb), JblStateMapping.write(JblStateMapping.EQ_PRESET, bass))
        assertEquals(C.voicePromptsSet(false), JblStateMapping.write(JblStateMapping.VOICE_PROMPTS, 0))
        assertEquals(C.voicePromptsSet(true), JblStateMapping.write(JblStateMapping.VOICE_PROMPTS, 1))

        assertNull(JblStateMapping.write(JblStateMapping.ANC_LEVEL, 0))
        assertNull(JblStateMapping.write(JblStateMapping.ANC_LEVEL, 8))
        assertNull(JblStateMapping.write(JblStateMapping.EQ_PRESET, C.EQ_PRESETS.size))
        assertNull(JblStateMapping.write(JblStateMapping.EQ_PRESET, -1))
        assertNull(JblStateMapping.write(JblStateMapping.VOICE_PROMPTS, 2))
        assertNull(JblStateMapping.write("sidetone", 1))
    }

    @Test fun `every control the mapping writes passes the write guard`() {
        val frames = listOf(
            JblStateMapping.write(JblStateMapping.ANC_LEVEL, 1), JblStateMapping.write(JblStateMapping.ANC_LEVEL, 7),
            JblStateMapping.write(JblStateMapping.VOICE_PROMPTS, 0), JblStateMapping.write(JblStateMapping.VOICE_PROMPTS, 1),
        ) + C.EQ_PRESETS.indices.map { JblStateMapping.write(JblStateMapping.EQ_PRESET, it) }
        assertTrue(frames.all { it != null && JblWriteGuard.isAllowed(it) })
    }
}

package dev.earshot.service

import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeSetting
import dev.earshot.core.NoiseSettings
import dev.earshot.core.PairedDevice
import dev.earshot.core.Sidetone
import dev.earshot.core.SpatialAudio
import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.qcultra2.AudioSettings
import dev.earshot.protocol.qcultra2.BoseModel
import dev.earshot.protocol.qcultra2.Buttons
import dev.earshot.protocol.qcultra2.CncState
import dev.earshot.protocol.qcultra2.ModeLayout
import dev.earshot.protocol.qcultra2.Eq
import dev.earshot.protocol.qcultra2.ModeConfigWrite
import dev.earshot.protocol.qcultra2.ModeConfigs
import dev.earshot.protocol.qcultra2.SpatialMode
import dev.earshot.protocol.qcultra2.Sidetone as SidetoneCodec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateMappingTest {

    @Test
    fun `noise settings invert the CNC byte and map spatial by wire code`() {
        val wire = AudioSettings.parse("0300020101".hexToBytes())!! // cnc 3, spatial head, wind on, anc on
        val ui = wire.toNoiseSettings()
        assertEquals(NoiseSettings(cancellingLevel = 7, ancEnabled = true, windBlock = true, spatialAudio = SpatialAudio.HEAD), ui)

        val back = ui.toAudioSettings()
        assertEquals(3, back.cncWire)
        assertEquals(SpatialMode.HEAD.code, back.spatialCode)
        assertFalse(back.autoCnc)
        assertEquals("0300020101", AudioSettings.build(back).joinToString("") { "%02x".format(it) })

        assertEquals(0, NoiseSettings(cancellingLevel = 10).toAudioSettings().cncWire)
        assertEquals(10, NoiseSettings(cancellingLevel = 0).toAudioSettings().cncWire)
    }

    @Test
    fun `mode config maps presets, custom slots and empty slots`() {
        val aware = ModeConfigs.parse(FakeRequester.modeStatus(1, "Aware", cncWire = 10).payload)!!.toListeningMode(favorite = true)
        assertEquals(ListeningMode(1, "Aware", isPreset = true, isEditable = false, isConfigured = true, isFavorite = true,
            settings = NoiseSettings(cancellingLevel = 0), changeableSettings = emptySet()), aware)

        val home = ModeConfigs.parse(FakeRequester.modeStatus(4, "Home", cncWire = 0, wind = true).payload)!!.toListeningMode(favorite = false)
        assertEquals("Home", home.name)
        assertFalse(home.isPreset)
        assertTrue(home.isEditable)
        assertTrue(home.isConfigured)
        assertEquals(NoiseSettings(cancellingLevel = 10, windBlock = true), home.settings)
        // byte 41 = 0x1d: everything but auto-CNC
        assertEquals(setOf(ModeSetting.CANCELLING_LEVEL, ModeSetting.SPATIAL_AUDIO, ModeSetting.WIND_BLOCK, ModeSetting.ANC), home.changeableSettings)

        val empty = ModeConfigs.parse(FakeRequester.modeStatus(5, "None", cncWire = 10).payload)!!.toListeningMode(favorite = false)
        assertEquals("", empty.name)
        assertFalse(empty.isConfigured)
        assertTrue(empty.isEditable)
        assertNull(empty.settings)

        // The 40-byte SET_GET echo has no editable bit: fall back to the slot range.
        val echo = ModeConfigs.parse(ModeConfigWrite(6, "Focus", 10, SpatialMode.OFF, false, true).build())!!.toListeningMode(false)
        assertTrue(echo.isEditable)
        assertTrue(echo.isConfigured)
        assertNull(echo.changeableSettings)
        assertEquals("Focus", echo.name)
    }

    @Test
    fun `mode list always has eleven slots and keeps previous entries for missing ones`() {
        val previous = listOf(ListeningMode(7, "Old", isPreset = false, isEditable = true, isConfigured = true, isFavorite = true))
        val configs = mapOf(
            0 to ModeConfigs.parse(FakeRequester.modeStatus(0, "Quiet", 0).payload)!!,
            5 to ModeConfigs.parse(FakeRequester.modeStatus(5, "None", 10).payload)!!,
        )
        val modes = buildModeList(configs, favorites = setOf(0, 7), previous = previous)
        assertEquals((0..10).toList(), modes.map { it.index })
        assertEquals("Quiet", modes[0].name)
        assertTrue(modes[0].isFavorite)
        assertEquals("Aware", modes[1].name)          // placeholder preset keeps its name
        assertTrue(modes[1].isConfigured)
        assertNull(modes[1].settings)
        assertFalse(modes[5].isConfigured)
        assertEquals("Old", modes[7].name)            // not in the dump: previous entry kept
        assertTrue(modes[7].isFavorite)
        assertFalse(modes[8].isConfigured)
        assertEquals("", modes[8].name)

        // favourites null = not read: previous flags survive
        val kept = buildModeList(configs, favorites = null, previous = previous)
        assertTrue(kept[7].isFavorite)
        assertFalse(kept[0].isFavorite)

        val refav = modes.withFavorites(setOf(1))
        assertEquals(listOf(1), refav.filter { it.isFavorite }.map { it.index })

        val replaced = modes.withSlot(ModeConfigs.parse(FakeRequester.modeStatus(7, "New", 5).payload)!!)
        assertEquals("New", replaced[7].name)
        assertTrue(replaced[7].isFavorite)            // favourite flag preserved across the rewrite
    }

    @Test
    fun `eq bands from the capture map to the three band model`() {
        val bands = Eq.parse("f60a0000 f60afe01 f60afa02".replace(" ", "").hexToBytes())
        assertEquals(HeadphoneEq(bass = 0, mid = -2, treble = -6), bands.toHeadphoneEq())
        assertNull(Eq.parse("f60a0000".hexToBytes()).toHeadphoneEq())
    }

    @Test
    fun `sidetone wire order is off high medium low`() {
        assertEquals(Sidetone.HIGH, SidetoneCodec.parse("0101".hexToBytes())!!.toSidetone())
        assertEquals(Sidetone.MEDIUM, SidetoneCodec.parse("01020f".hexToBytes())!!.toSidetone())
        assertEquals(Sidetone.LOW, SidetoneCodec.parse("0103".hexToBytes())!!.toSidetone())
        assertEquals(Sidetone.OFF, SidetoneCodec.parse("0000".hexToBytes())!!.toSidetone())
        assertEquals(Sidetone.OFF, SidetoneCodec.parse("0009".hexToBytes())!!.toSidetone()) // unknown code
        assertTrue(SidetoneCodec.parse("01020f".hexToBytes())!!.persist)
    }

    @Test
    fun `button capture maps to the shortcut assignment`() {
        val cfg = Buttons.parse("80090e00094002".hexToBytes())!!.toButtonAssignment()
        assertEquals(0x80, cfg.buttonId)
        assertEquals(9, cfg.eventType)
        assertEquals(ButtonAction.DISABLED, cfg.action)
        // Big-endian mask 0x00094002 = the Bose app's shortcut-button menu (verified on firmware 10.12).
        assertEquals(
            listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.DISABLED, ButtonAction.SPOTIFY_TAP, ButtonAction.SPATIAL_AUDIO),
            cfg.supportedActions,
        )
        assertFalse(ButtonAction.UNKNOWN in cfg.supportedActions)
        assertEquals(ButtonAction.UNKNOWN, Buttons.parse("800963".hexToBytes())!!.toButtonAssignment().action)
    }

    @Test
    fun `active source marks exactly one paired device`() {
        val devices = listOf(PairedDevice("C4:0F:08:11:22:33", "Pixel", isActiveSource = true), PairedDevice("94:45:60:85:05:A7", "Laptop"))
        val switched = devices.withActiveSource("94:45:60:85:05:a7")
        assertEquals(listOf(false, true), switched.map { it.isActiveSource })
        assertEquals(listOf(false, false), devices.withActiveSource(null).map { it.isActiveSource })
    }

    @Test
    fun `capabilities follow what the connection showed, not only the catalogue`() {
        val guess = BoseModel.QC_ULTRA_1.toCapabilities()
        assertTrue(guess.liveNoiseControl && guess.ancToggle && guess.listeningModes)

        val compact = BoseModel.QC_ULTRA_1.toCapabilities(layout = ModeLayout.COMPACT, liveNoise = false)
        assertFalse(compact.liveNoiseControl)
        assertFalse(compact.ancToggle)
        assertTrue(compact.ancAlwaysOn)
        assertTrue(compact.windExcludesCancelling)

        val noModes = BoseModel.QC_ULTRA_2.toCapabilities(modes = false)
        assertFalse(noModes.listeningModes)
        assertFalse(noModes.liveNoiseControl)
        assertFalse(noModes.editableModes)
        assertFalse(noModes.favoriteModes)
    }

    @Test
    fun `the 1_5 CNC step reads as a level with step 0 most cancelling`() {
        assertEquals(10, CncState(steps = 11, step = 0, flags = 3).toNoiseSettings()?.cancellingLevel)
        assertEquals(0, CncState(steps = 11, step = 10, flags = 3).toNoiseSettings()?.cancellingLevel)
        assertEquals(5, CncState(steps = 3, step = 1, flags = 1).toNoiseSettings()?.cancellingLevel)
        assertFalse(CncState(steps = 11, step = 4, flags = 2).toNoiseSettings()!!.ancEnabled)
        assertNull(CncState(steps = 11, step = 12, flags = 1).toNoiseSettings())
        assertNull(CncState(steps = 1, step = 0, flags = 1).toNoiseSettings())
    }
}

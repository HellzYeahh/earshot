package dev.earshot.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PresetCodecTest {
    @Test
    fun `presets round-trip`() {
        val presets = BuiltInPresets.all + EqPreset("mine", "Mine", listOf(ParametricBand(440.0, 2.5, 0.9, FilterType.HIGH_SHELF, enabled = false)), -2.5)
        val text = PresetCodec.encodePresets(presets)
        assertEquals(presets, PresetCodec.decodePresets(text))
    }

    @Test
    fun `settings round-trip`() {
        val s = TuningSettings(enabled = true, activePresetId = "bass", bands = BuiltInPresets.bassBoost.bands, preampDb = -4.0, boostDb = 6.0, limiterEnabled = false)
        assertEquals(s, PresetCodec.decodeSettings(PresetCodec.encodeSettings(s)))
    }

    @Test
    fun `app profiles round-trip`() {
        val p = listOf(AppProfileRecord("com.spotify.music", "Spotify", "bass"), AppProfileRecord("org.videolan.vlc", "VLC", "flat"))
        assertEquals(p, PresetCodec.decodeAppProfiles(PresetCodec.encodeAppProfiles(p)))
    }

    @Test
    fun `unknown keys and missing optional fields are tolerated`() {
        val s = PresetCodec.decodeSettings("""{"enabled":true,"futureField":42}""")
        assertEquals(true, s.enabled)
        assertEquals(TuningSettings().bands, s.bands)
    }

    @Test
    fun `OrNull variants swallow corruption`() {
        assertNull(PresetCodec.decodeSettingsOrNull("not json"))
        assertNull(PresetCodec.decodePresetsOrNull(null))
        assertEquals(emptyList(), PresetCodec.decodeAppProfilesOrNull("[]"))
    }
}

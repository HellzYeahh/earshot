package dev.earshot.ui.modes

import dev.earshot.core.NoiseSettings
import dev.earshot.core.SpatialAudio
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The custom-mode editor keeps its noise settings across a rotation (audit R15): what goes into
 * the saved state comes back unchanged, and a value this build didn't write never crashes the sheet.
 */
class NoiseSettingsSavingTest {

    @Test fun `every setting survives the round trip`() {
        for (spatial in SpatialAudio.entries) {
            for (level in listOf(0, 3, 10)) {
                for (anc in listOf(true, false)) {
                    for (wind in listOf(true, false)) {
                        val settings = NoiseSettings(cancellingLevel = level, ancEnabled = anc, windBlock = wind, spatialAudio = spatial)
                        assertEquals(settings, NoiseSettingsSaving.decode(NoiseSettingsSaving.encode(settings)))
                    }
                }
            }
        }
    }

    @Test fun `spatial audio is saved by its stored id, not the wire code`() {
        val saved = NoiseSettingsSaving.encode(NoiseSettings(cancellingLevel = 7, ancEnabled = true, windBlock = false, spatialAudio = SpatialAudio.HEAD))
        assertContentEquals(intArrayOf(7, 1, 0, SpatialAudio.HEAD.storedId), saved)
    }

    @Test fun `an unreadable saved value starts the editor from the slot again`() {
        assertNull(NoiseSettingsSaving.decode(intArrayOf()))
        assertNull(NoiseSettingsSaving.decode(intArrayOf(5, 1, 0)))
        assertNull(NoiseSettingsSaving.decode(intArrayOf(5, 1, 0, 0, 9)))
    }

    @Test fun `an out-of-range level is clamped instead of crashing NoiseSettings`() {
        assertEquals(10, NoiseSettingsSaving.decode(intArrayOf(42, 1, 0, 0))?.cancellingLevel)
        assertEquals(0, NoiseSettingsSaving.decode(intArrayOf(-3, 1, 0, 0))?.cancellingLevel)
        // An unknown spatial id reads as off, as the rules do.
        assertEquals(SpatialAudio.OFF, NoiseSettingsSaving.decode(intArrayOf(5, 1, 0, 99))?.spatialAudio)
    }
}

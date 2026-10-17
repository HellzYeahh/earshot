package dev.earshot.service

import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import org.junit.Test
import kotlin.test.assertEquals

class HeadphoneServiceTest {

    private fun mode(index: Int, favorite: Boolean, configured: Boolean = true) =
        ListeningMode(index, "M$index", isPreset = index < 4, isEditable = index >= 4, isConfigured = configured, isFavorite = favorite)

    @Test
    fun `cycle walks the favourites in slot order and wraps`() {
        val modes = listOf(mode(0, true), mode(1, false), mode(2, true), mode(4, true), mode(5, true, configured = false))
        assertEquals(2, HeadphoneService.nextModeIndex(HeadphoneState(currentModeIndex = 0, modes = modes)))
        assertEquals(4, HeadphoneService.nextModeIndex(HeadphoneState(currentModeIndex = 2, modes = modes)))
        assertEquals(0, HeadphoneService.nextModeIndex(HeadphoneState(currentModeIndex = 4, modes = modes)))
    }

    @Test
    fun `cycle falls back to Quiet Aware Immersion without favourites`() {
        val modes = (0..3).map { mode(it, favorite = false) }
        assertEquals(1, HeadphoneService.nextModeIndex(HeadphoneState(currentModeIndex = 0, modes = modes)))
        assertEquals(0, HeadphoneService.nextModeIndex(HeadphoneState(currentModeIndex = 2, modes = modes)))
        // Unknown current mode (0xFF after a manual tweak) starts from the first.
        assertEquals(0, HeadphoneService.nextModeIndex(HeadphoneState(currentModeIndex = 0xFF, modes = modes)))
    }
}

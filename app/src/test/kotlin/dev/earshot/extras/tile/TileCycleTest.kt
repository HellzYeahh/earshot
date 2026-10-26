package dev.earshot.extras.tile

import dev.earshot.core.AppPreferences
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeRef
import dev.earshot.service.SennheiserStateMapping
import dev.earshot.service.SoundcoreStateMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileCycleTest {
    private fun mode(index: Int, fav: Boolean = false, configured: Boolean = true) =
        ListeningMode(index, "M$index", isPreset = index < 4, isEditable = index >= 4, isConfigured = configured, isFavorite = fav)

    private val modes = listOf(mode(0, fav = true), mode(1), mode(2, fav = true), mode(3), mode(4, fav = true), mode(5, configured = false))

    private val bose = "68:F2:1F:12:34:56"
    private val other = "AC:80:0A:00:00:01"

    private fun state(modes: List<ListeningMode>, address: String?) =
        HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTED, "x", address), modes = modes)

    @Test
    fun `preferences win and are filtered to configured slots`() {
        assertEquals(listOf(3, 4), TileCycle.order(listOf(3, 5, 4, 3), modes))
    }

    @Test
    fun `favourites when no preference`() {
        assertEquals(listOf(0, 2, 4), TileCycle.order(emptyList(), modes))
    }

    @Test
    fun `the first three modes when no favourites either`() {
        val plain = modes.map { it.copy(isFavorite = false) }
        assertEquals(listOf(0, 1, 2), TileCycle.order(emptyList(), plain))
        // Not Bose slot numbers: the Sleep A30 cycles its own two modes.
        assertEquals(listOf(0, 1), TileCycle.order(emptyList(), SoundcoreStateMapping.MODES))
    }

    @Test
    fun `before modes are loaded there is nothing to cycle`() {
        // 0.75 sent a blind 0/1/2 (or the saved slots) here, which meant another mode on every make.
        assertEquals(emptyList(), TileCycle.order(emptyList(), emptyList()))
        assertEquals(emptyList(), TileCycle.order(listOf(4, 1), emptyList()))
        assertEquals(emptyList(), TileCycle.order(listOf(0), modes.map { it.copy(isConfigured = false) }))
    }

    @Test
    fun `until the migration ran the 0_75 slots are used as they are`() {
        val prefs = AppPreferences(tileCycleModes = listOf(4, 1))
        assertEquals(listOf(4, 1), TileCycle.order(prefs, state(modes, bose)))
    }

    @Test
    fun `after the migration the refs resolve on whatever headset is connected`() {
        val quiet = ModeRef(ModeMeaning.QUIET, "Quiet", bose, 0)
        val gym = ModeRef(ModeMeaning.CUSTOM, "Gym", bose, 5)
        val prefs = AppPreferences(tileCycleModes = listOf(9)).withTileCycle(listOf(quiet, gym))
        // A Sennheiser: Quiet is its noise cancelling, Gym means nothing, so only one entry.
        assertEquals(listOf(0), TileCycle.order(prefs, state(SennheiserStateMapping.MODES, other)))
        // Nothing of the choice exists here: favourites, then the first modes.
        val off = AppPreferences().withTileCycle(listOf(gym))
        assertEquals(listOf(0, 1, 2), TileCycle.order(off, state(SennheiserStateMapping.MODES, other)))
    }

    @Test
    fun `toggling saves refs and carries a 0_75 list over`() {
        val boseModes = listOf(
            ListeningMode(0, "Quiet", isPreset = true, isEditable = false, isConfigured = true),
            ListeningMode(1, "Aware", isPreset = true, isEditable = false, isConfigured = true),
            ListeningMode(2, "Immersion", isPreset = true, isEditable = false, isConfigured = true),
            ListeningMode(5, "Gym", isPreset = false, isEditable = true, isConfigured = true),
        )
        val legacy = AppPreferences(tileCycleModes = listOf(2, 0))
        val added = TileCycle.toggle(legacy, boseModes[3], boseModes, bose)
        assertTrue(added.modeRefsMigrated)
        assertEquals(
            listOf(
                ModeRef(ModeMeaning.QUIET, "Quiet", bose, 0, legacySlot = 0),
                ModeRef(ModeMeaning.CUSTOM, "Immersion", bose, 2, legacySlot = 2),
                ModeRef(ModeMeaning.CUSTOM, "Gym", bose, 5),
            ),
            added.tileCycle,
        )
        // An older build still finds its slots.
        assertEquals(listOf(0, 2, 5), added.tileCycleModes)
        val removed = TileCycle.toggle(added, boseModes[0], boseModes, bose)
        assertEquals(listOf(2, 5), TileCycle.preferredSlots(removed, boseModes, bose))
        assertFalse(removed.tileCycle.any { it.meaning == ModeMeaning.QUIET })
    }

    @Test
    fun `next wraps and recovers from a mode outside the list`() {
        val order = listOf(0, 2, 4)
        assertEquals(2, TileCycle.next(0, order))
        assertEquals(0, TileCycle.next(4, order))
        assertEquals(0, TileCycle.next(3, order))
        assertEquals(0, TileCycle.next(null, order))
        assertNull(TileCycle.next(0, emptyList()))
    }
}

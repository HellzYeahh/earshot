package dev.earshot.extras.tile

import dev.earshot.core.ListeningMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TileCycleTest {
    private fun mode(index: Int, fav: Boolean = false, configured: Boolean = true) =
        ListeningMode(index, "M$index", isPreset = index < 4, isEditable = index >= 4, isConfigured = configured, isFavorite = fav)

    private val modes = listOf(mode(0, fav = true), mode(1), mode(2, fav = true), mode(3), mode(4, fav = true), mode(5, configured = false))

    @Test
    fun `preferences win and are filtered to configured slots`() {
        assertEquals(listOf(3, 4), TileCycle.order(listOf(3, 5, 4, 3), modes))
    }

    @Test
    fun `favourites when no preference`() {
        assertEquals(listOf(0, 2, 4), TileCycle.order(emptyList(), modes))
    }

    @Test
    fun `presets when no favourites either`() {
        val plain = modes.map { it.copy(isFavorite = false) }
        assertEquals(listOf(0, 1, 2), TileCycle.order(emptyList(), plain))
    }

    @Test
    fun `before modes are loaded we still cycle presets`() {
        assertEquals(listOf(0, 1, 2), TileCycle.order(emptyList(), emptyList()))
        assertEquals(listOf(4, 1), TileCycle.order(listOf(4, 1), emptyList()))
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

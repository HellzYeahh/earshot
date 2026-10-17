package dev.earshot.extras.tile

import dev.earshot.core.ListeningMode

/** Pure "which mode comes next" logic for the Quick Settings tile and the widget buttons. */
object TileCycle {
    private val PRESET_FALLBACK = listOf(ListeningMode.QUIET, ListeningMode.AWARE, ListeningMode.IMMERSION)

    /**
     * The ordered list of mode slots to cycle through: the user's [preferred] list (slots that
     * actually exist and are configured), else the favourites, else Quiet/Aware/Immersion.
     * When [modes] is empty (not loaded yet) we still return something usable.
     */
    fun order(preferred: List<Int>, modes: List<ListeningMode>): List<Int> {
        val usable = modes.filter { it.isConfigured }.map { it.index }.toSet()
        val fromPrefs = preferred.distinct().filter { modes.isEmpty() || it in usable }
        if (fromPrefs.isNotEmpty()) return fromPrefs
        val favourites = modes.filter { it.isFavorite && it.isConfigured }.map { it.index }
        if (favourites.isNotEmpty()) return favourites
        return PRESET_FALLBACK.filter { modes.isEmpty() || it in usable }.ifEmpty { usable.sorted() }
    }

    /** The slot after [current] in [order] (wrapping); the first slot when [current] is not in the list. */
    fun next(current: Int?, order: List<Int>): Int? {
        if (order.isEmpty()) return null
        val i = order.indexOf(current)
        return if (i < 0) order.first() else order[(i + 1) % order.size]
    }
}

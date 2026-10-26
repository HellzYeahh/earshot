package dev.earshot.extras.tile

import dev.earshot.core.AppPreferences
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeRef
import dev.earshot.core.ModeResolver

/** Pure "which mode comes next" logic for the Quick Settings tile and the widget buttons. */
object TileCycle {
    /**
     * The slots the user's tile choice means on [state]'s headset: the saved refs resolved there
     * (in the saved order), or, until the one-time migration ran, the 0.75 slot numbers as they are.
     */
    fun preferredSlots(prefs: AppPreferences, state: HeadphoneState): List<Int> =
        preferredSlots(prefs, state.modes, state.connection.deviceAddress)

    fun preferredSlots(prefs: AppPreferences, modes: List<ListeningMode>, address: String?): List<Int> =
        if (prefs.modeRefsMigrated) prefs.tileCycle.mapNotNull { ModeResolver.slotFor(it, modes, address) }
        else prefs.tileCycleModes

    /** [order] for the connected headset and the saved preferences. */
    fun order(prefs: AppPreferences, state: HeadphoneState): List<Int> = order(preferredSlots(prefs, state), state.modes)

    /**
     * The preferences after the user toggled [mode] of the headset at [address] in or out of the
     * tile cycle (Preferences screen). The choice is saved by meaning from then on; a 0.75 list not
     * yet migrated is carried over first, keeping its slot numbers as the fallback.
     */
    fun toggle(prefs: AppPreferences, mode: ListeningMode, modes: List<ListeningMode>, address: String?): AppPreferences {
        fun slot(ref: ModeRef) = ModeResolver.slotFor(ref, modes, address)
        val current = if (prefs.modeRefsMigrated) prefs.tileCycle
        else prefs.tileCycleModes.distinct().map { ModeResolver.refForLegacySlot(it, modes, address) }
        val chosen = current.any { slot(it) == mode.index }
        val next = if (chosen) current.filterNot { slot(it) == mode.index } else current + ModeResolver.refFor(mode, address)
        // Slot order on this headset; refs that mean nothing here (picked on another one) go last.
        return prefs.withTileCycle(next.sortedBy { slot(it) ?: Int.MAX_VALUE })
    }

    /**
     * The ordered list of mode slots to cycle through: the [preferred] slots that exist and are
     * configured, else the favourites, else the headset's first modes ([ModeResolver.defaultCycle]).
     * Empty until the modes are known: a blind slot number would mean something else on every make.
     */
    fun order(preferred: List<Int>, modes: List<ListeningMode>): List<Int> {
        val usable = modes.filter { it.isConfigured }.map { it.index }.toSet()
        if (usable.isEmpty()) return emptyList()
        val fromPrefs = preferred.distinct().filter { it in usable }
        if (fromPrefs.isNotEmpty()) return fromPrefs
        val favourites = modes.filter { it.isFavorite && it.isConfigured }.map { it.index }
        if (favourites.isNotEmpty()) return favourites
        return ModeResolver.defaultCycle(modes)
    }

    /** The slot after [current] in [order] (wrapping); the first slot when [current] is not in the list. */
    fun next(current: Int?, order: List<Int>): Int? {
        if (order.isEmpty()) return null
        val i = order.indexOf(current)
        return if (i < 0) order.first() else order[(i + 1) % order.size]
    }
}

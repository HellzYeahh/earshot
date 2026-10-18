package dev.earshot.shortcut

import dev.earshot.dsp.EqPreset

/** The "Cycle EQ preset" step. Pure. */
object EqCycle {
    /**
     * The preset after [activeId] in [presets] (the Sound screen's list: built-ins, then the user's),
     * wrapping around. Custom bands (no active preset) or a preset that was deleted start at the first.
     * Null only when there are no presets at all.
     */
    fun next(presets: List<EqPreset>, activeId: String?): EqPreset? {
        if (presets.isEmpty()) return null
        val index = presets.indexOfFirst { it.id == activeId }
        return if (index < 0) presets.first() else presets[(index + 1) % presets.size]
    }
}

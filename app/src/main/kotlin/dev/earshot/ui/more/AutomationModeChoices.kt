package dev.earshot.ui.more

import dev.earshot.core.AutomationAction
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeRef
import dev.earshot.core.ModeResolver

/**
 * The "Set mode" choices of the automation editor. Pure, so the rule a pick writes is unit-tested.
 * A pick writes the mode by meaning ([ModeRef]) with the headset it was picked on, plus its slot
 * as `modeIndex` for an older build.
 */
internal object AutomationModeChoices {

    /** One choice per mode of the connected headset, or the generic meanings when none is known. */
    fun choices(modes: List<ListeningMode>, address: String?): List<AutomationAction.SetMode> {
        val live = modes.filter { it.isConfigured }
        if (live.isNotEmpty()) return live.map { AutomationAction.SetMode(it.index, ModeResolver.refFor(it, address)) }
        return ModeResolver.GENERIC_CHOICES.map { AutomationAction.SetMode(ModeResolver.legacyIndexFor(it), ModeRef(it)) }
    }

    /** What a new "Set mode" action starts as: the headset's quiet mode, else its first mode. */
    fun default(modes: List<ListeningMode>, address: String?): AutomationAction.SetMode {
        val all = choices(modes, address)
        return all.firstOrNull { it.mode?.meaning == ModeMeaning.QUIET } ?: all.first()
    }

    /**
     * The choice that shows [action] in the field: the mode it resolves to on this headset, else
     * [action] itself (a mode the connected headset doesn't have, or a 0.75 slot it lacks).
     */
    fun shown(action: AutomationAction.SetMode, choices: List<AutomationAction.SetMode>, modes: List<ListeningMode>, address: String?): AutomationAction.SetMode {
        val ref = action.mode
        val slot = if (ref != null) ModeResolver.slotFor(ref, modes, address) else action.modeIndex
        return choices.firstOrNull { it.mode?.slot != null && it.modeIndex == slot } ?: action
    }

    /** The field and menu text; null for an unbound 0.75 slot this headset doesn't have (the UI shows "Slot N"). */
    fun label(choice: AutomationAction.SetMode, modes: List<ListeningMode>, address: String?): String? =
        ModeResolver.nameOf(choice, modes, address)
}

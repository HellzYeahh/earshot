package dev.earshot.ui.more

import androidx.lifecycle.viewModelScope
import dev.earshot.R
import dev.earshot.core.AppPreferences
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.AutomationRepository
import dev.earshot.core.AutomationRule
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.Trigger
import dev.earshot.dsp.EqPreset
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.UiMessenger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Automations list + editor + app preferences. */
class AutomationsViewModel(
    private val automation: AutomationRepository,
    headphones: HeadphoneRepository,
    tuning: AudioTuningRepository,
    messenger: UiMessenger,
) : EarshotViewModel(messenger) {

    /**
     * The store appends an upserted rule at the end, so a plain enable/disable would jump the
     * row to the bottom; the list keeps the order rules were first seen in for this screen's life.
     */
    private val firstSeen = HashMap<String, Int>()
    val rules: StateFlow<List<AutomationRule>> = automation.rules
        .map { list -> list.sortedBy { firstSeen.getOrPut(it.id) { firstSeen.size } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, automation.rules.value)
    val recentLog: StateFlow<List<String>> = automation.recentLog
    val preferences: StateFlow<AppPreferences> = automation.preferences
    val headphoneState: StateFlow<HeadphoneState> = headphones.state
    val presets: StateFlow<List<EqPreset>> = tuning.presets

    fun hasUsageAccess(): Boolean = automation.hasUsageAccess()
    fun openUsageAccessSettings() = automation.openUsageAccessSettings()

    fun setEnabled(rule: AutomationRule, enabled: Boolean) = launchCatching { automation.upsertRule(rule.copy(enabled = enabled)) }
    fun save(rule: AutomationRule, onDone: () -> Unit) = launchCatching {
        automation.upsertRule(rule)
        // A foreground-app rule can't fire until Usage Access is granted; say so right away.
        if (rule.trigger is Trigger.ForegroundApp && !automation.hasUsageAccess()) {
            messenger.show(R.string.automation_saved_needs_usage_access, rule.name)
        } else {
            messenger.show(R.string.automation_saved, rule.name)
        }
        onDone()
    }
    fun delete(id: String, onDone: () -> Unit = {}) = launchCatching { automation.deleteRule(id); onDone() }
    fun setPreferences(preferences: AppPreferences) = launchCatching { automation.setPreferences(preferences) }
}

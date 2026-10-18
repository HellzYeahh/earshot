package dev.earshot.ui.tools

import android.app.Activity
import androidx.lifecycle.viewModelScope
import dev.earshot.bt.DeviceCandidate
import dev.earshot.bt.DeviceFinder
import dev.earshot.shortcut.AssistantCandidate
import dev.earshot.shortcut.HandlerStatus
import dev.earshot.shortcut.ShortcutAction
import dev.earshot.shortcut.ShortcutEngine
import dev.earshot.shortcut.ShortcutSettings
import dev.earshot.shortcut.VoiceMemoService
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.UiMessenger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the Shortcuts remapper screen shows. */
data class ShortcutsUiState(
    val handler: HandlerStatus = HandlerStatus.Unknown,
    val settings: ShortcutSettings = ShortcutSettings(),
    /** Bonded audio devices that can get their own action. */
    val headsets: List<DeviceCandidate> = emptyList(),
    /** Other apps' VOICE_COMMAND / VOICE_ASSIST activities. */
    val assistants: List<AssistantCandidate> = emptyList(),
    /** What "automatic" resolves to on this phone right now. */
    val autoAssistant: AssistantCandidate? = null,
    val memoState: VoiceMemoService.MemoState = VoiceMemoService.MemoState.IDLE,
    /** The experimental wired / USB-C headset trigger is on. */
    val wiredEnabled: Boolean = false,
) {
    /** Some configured action records audio (the screen then checks the microphone permission). */
    val usesVoiceMemo: Boolean
        get() = settings.defaultAction == ShortcutAction.VoiceMemo || settings.overrides.any { it.action == ShortcutAction.VoiceMemo }
}

class ShortcutsRemapperViewModel(
    private val engine: ShortcutEngine,
    private val finder: DeviceFinder,
    messenger: UiMessenger,
) : EarshotViewModel(messenger) {

    private data class Lookups(
        val handler: HandlerStatus = HandlerStatus.Unknown,
        val headsets: List<DeviceCandidate> = emptyList(),
        val assistants: List<AssistantCandidate> = emptyList(),
        val autoAssistant: AssistantCandidate? = null,
        val wiredEnabled: Boolean = false,
    )

    private val lookups = MutableStateFlow(Lookups())

    val state: StateFlow<ShortcutsUiState> = combine(engine.prefs.settings, lookups, VoiceMemoService.state) { settings, l, memo ->
        ShortcutsUiState(l.handler, settings, l.headsets, l.assistants, l.autoAssistant, memo, l.wiredEnabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShortcutsUiState())

    /** Re-reads the package manager and the bonded devices; called on every resume (back from Settings). */
    fun refresh() {
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                Lookups(
                    handler = runCatching { engine.assistants.handlerStatus() }.getOrDefault(HandlerStatus.Unknown),
                    headsets = runCatching { finder.candidates().filter { it.isAudioDevice || it.looksLikeBose || it.isSaved } }.getOrDefault(emptyList()),
                    assistants = runCatching { engine.assistants.candidates() }.getOrDefault(emptyList()),
                    autoAssistant = runCatching { engine.assistants.forwardTarget(null) }.getOrNull(),
                    wiredEnabled = runCatching { engine.wiredTriggerEnabled }.getOrDefault(false),
                )
            }
            lookups.update { fresh }
        }
    }

    fun setDefaultAction(action: ShortcutAction) = launchCatching { engine.prefs.update { it.copy(defaultAction = action) } }

    /** [action] null = "Use default". */
    fun setOverride(address: String, name: String?, action: ShortcutAction?) =
        launchCatching { engine.prefs.update { it.withOverride(address, name, action) } }

    fun setSpokenFeedback(enabled: Boolean) = launchCatching { engine.prefs.update { it.copy(spokenFeedback = enabled) } }

    /** Runs the current default action from this screen's activity, as the headset would. */
    fun tryNow(activity: Activity) {
        val settings = state.value.settings
        engine.run(activity, settings.defaultAction, settings, trigger = null, headsetAddress = null) {}
    }

    fun setWiredEnabled(enabled: Boolean) = launchCatching {
        withContext(Dispatchers.IO) { engine.wiredTriggerEnabled = enabled }
        lookups.update { it.copy(wiredEnabled = engine.wiredTriggerEnabled) }
    }
}

package dev.earshot.ui.more

import dev.earshot.R
import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.Sidetone
import dev.earshot.core.VoicePrompts
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.UiMessenger
import kotlinx.coroutines.flow.StateFlow

/** Headphone settings ([1.x]) plus devices and power, shared by the More and Devices screens. */
class SettingsViewModel(private val headphones: HeadphoneRepository, messenger: UiMessenger) : EarshotViewModel(messenger) {
    val state: StateFlow<HeadphoneState> = headphones.state

    fun setSidetone(level: Sidetone, persistent: Boolean) = launchCatching { headphones.setSidetone(level, persistent) }
    fun setAutoPlayPause(enabled: Boolean) = launchCatching { headphones.setAutoPlayPause(enabled) }
    fun setAutoAnswer(enabled: Boolean) = launchCatching { headphones.setAutoAnswer(enabled) }
    fun setMultipoint(enabled: Boolean) = launchCatching { headphones.setMultipoint(enabled) }
    fun setTouchControls(enabled: Boolean) = launchCatching { headphones.setTouchControls(enabled) }
    fun setAutoOff(minutes: Int) = launchCatching { headphones.setAutoOff(minutes) }
    fun setVoicePrompts(prompts: VoicePrompts) = launchCatching { headphones.setVoicePrompts(prompts) }
    fun setShortcutButton(action: ButtonAction) = launchCatching { headphones.setShortcutButton(action) }
    fun rename(name: String) = launchCatching { headphones.setDeviceName(name); messenger.show(R.string.settings_renamed, name) }
    fun switchToDevice(address: String) = launchCatching { headphones.switchToDevice(address) }
    fun enterPairingMode() = launchCatching { headphones.enterPairingMode() }
    fun powerOff() = launchCatching { headphones.powerOff() }
    fun refresh() = launchCatching { headphones.refresh() }
}

/** Voice-prompt language table (ids are the wire values in [1.3]). */
val VOICE_PROMPT_LANGUAGES: List<Pair<Int, String>> = listOf(
    0 to "UK English", 1 to "US English", 2 to "French", 3 to "Italian", 4 to "German", 5 to "EU Spanish",
    6 to "MX Spanish", 7 to "BR Portuguese", 8 to "Mandarin", 9 to "Korean", 10 to "Russian", 11 to "Polish",
    12 to "Hebrew", 13 to "Turkish", 14 to "Dutch", 15 to "Japanese", 16 to "Cantonese", 17 to "Arabic",
    18 to "Swedish", 19 to "Danish", 20 to "Norwegian", 21 to "Finnish", 22 to "Hindi",
)

fun voicePromptLanguageName(id: Int): String = VOICE_PROMPT_LANGUAGES.firstOrNull { it.first == id }?.second ?: "Language $id"

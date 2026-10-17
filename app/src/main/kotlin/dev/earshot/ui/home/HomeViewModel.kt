package dev.earshot.ui.home

import androidx.lifecycle.viewModelScope
import dev.earshot.R
import dev.earshot.core.ButtonPress
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.core.TransportControl
import dev.earshot.protocol.qcultra2.ButtonEvent
import dev.earshot.protocol.qcultra2.ButtonId
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.UiMessenger
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HomeViewModel(
    private val headphones: HeadphoneRepository,
    messenger: UiMessenger,
) : EarshotViewModel(messenger) {

    val state: StateFlow<HeadphoneState> = headphones.state

    init {
        // `[2.12]` button pushes: one short line per press, keyed on atMillis so two identical
        // presses in a row both show. The press already recorded when the screen opened is old
        // news and is skipped by timestamp (a plain drop(1) would eat the first live press
        // whenever nothing was recorded yet).
        val initial = headphones.state.value.lastButtonPress?.atMillis
        viewModelScope.launch {
            headphones.state.map { it.lastButtonPress }.filterNotNull()
                .distinctUntilChanged { a, b -> a.atMillis == b.atMillis }
                .filter { it.atMillis != initial }
                .collect { press -> messenger.show(R.string.button_press_line, buttonName(press), eventName(press), press.action.label) }
        }
    }

    fun connect() = headphones.connect()
    fun refresh() = launchCatching { headphones.refresh() }
    fun setMode(index: Int) = launchCatching { headphones.setMode(index) }

    fun toggleFavorite(index: Int) = launchCatching {
        val current = headphones.state.value.modes.filter { it.isFavorite }.map { it.index }.toSet()
        headphones.setFavorites(if (index in current) current - index else current + index)
    }

    /** [31.10] applies immediately; the noise panel sends the whole struct after each gesture. */
    fun setNoise(settings: NoiseSettings) = launchCatching { headphones.setNoise(settings) }

    fun transport(control: TransportControl) = launchCatching { headphones.transport(control) }
    fun setVolume(level: Int) = launchCatching { headphones.setVolume(level) }

    private fun buttonName(press: ButtonPress): String = ButtonId.fromCode(press.buttonId)?.label ?: "Button ${press.buttonId}"
    private fun eventName(press: ButtonPress): String = (ButtonEvent.fromCode(press.eventType)?.label ?: "event ${press.eventType}").lowercase()
}

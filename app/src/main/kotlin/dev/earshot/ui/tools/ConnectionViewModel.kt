package dev.earshot.ui.tools

import androidx.lifecycle.viewModelScope
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.UiMessenger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** LE Audio toggle, experimental latency and the restart banner for the Connection screen. */
class ConnectionViewModel(private val headphones: HeadphoneRepository, messenger: UiMessenger) : EarshotViewModel(messenger) {
    val state: StateFlow<HeadphoneState> = headphones.state

    private val _restarting = MutableStateFlow(false)
    /** True from the LE Audio write until the link has dropped and come back (or the safety timeout). */
    val restarting: StateFlow<Boolean> = _restarting
    private var restartTimeout: Job? = null

    val latencyAcknowledged: StateFlow<Boolean> = LatencySession.acknowledged
    val originalLatencyMs: StateFlow<Int?> = LatencySession.original

    init {
        viewModelScope.launch {
            LatencySession.rememberOriginal(headphones.state.map { it.latencyMs }.filterNotNull().first())
        }
        // The headphones reboot after `[1.37]`: wait for the connection to go away and return
        // before dropping the banner, otherwise it would vanish while the link is still up.
        viewModelScope.launch {
            var dropped = false
            headphones.state.map { it.isConnected }.distinctUntilChanged().collect { connected ->
                if (!_restarting.value) {
                    dropped = false
                } else if (!connected) {
                    dropped = true
                } else if (dropped) {
                    _restarting.value = false
                }
            }
        }
    }

    fun setLeAudio(enabled: Boolean) = launchCatching {
        _restarting.value = true
        try {
            headphones.setLeAudioEnabled(enabled)
        } catch (e: Throwable) {
            _restarting.value = false
            throw e
        }
        restartTimeout?.cancel()
        restartTimeout = viewModelScope.launch {
            delay(RESTART_TIMEOUT_MS)
            _restarting.value = false
        }
    }

    fun setLatency(ms: Int) = launchCatching { headphones.setLatencyMs(ms) }

    fun acknowledgeLatency() { LatencySession.acknowledged.value = true }

    private companion object {
        /** Reconnects take ~10-20 s; well past that the banner is only misleading. */
        const val RESTART_TIMEOUT_MS = 60_000L
    }
}

/**
 * Per-process memory for the experimental latency slider: the warning is acknowledged once per
 * app session (not once per screen visit), and the first value seen is what "Reset to original"
 * restores. Outlives the ViewModel on purpose; dies with the process.
 */
private object LatencySession {
    val acknowledged = MutableStateFlow(false)
    val original = MutableStateFlow<Int?>(null)
    fun rememberOriginal(ms: Int) { if (original.value == null) original.value = ms }
}

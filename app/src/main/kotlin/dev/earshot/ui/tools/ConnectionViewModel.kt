package dev.earshot.ui.tools

import androidx.lifecycle.viewModelScope
import dev.earshot.R
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
import kotlinx.coroutines.withTimeoutOrNull

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

    private var verifyJob: Job? = null

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
        verifyJob?.cancel()
        verifyJob = viewModelScope.launch { verifyLeAudio(enabled) }
    }

    /**
     * Tell the user whether the switch took. The write may also be forced on headphones that
     * report no LE Audio support, where the firmware can ignore it, so "Restart and switch" alone
     * proves nothing. The headphones reboot after `[1.37]`; the state resets on the drop and the
     * reconnect's refresh re-reads `[1.37]`. If they never drop, a refresh re-reads it in place.
     */
    private suspend fun verifyLeAudio(target: Boolean) {
        val dropped = withTimeoutOrNull(DROP_WAIT_MS) { headphones.state.first { !it.isConnected } } != null
        val now: Boolean? = if (dropped) {
            withTimeoutOrNull(RESTART_TIMEOUT_MS) { headphones.state.first { it.isConnected } }
                ?: return messenger.show(R.string.conn_le_result_unknown)
            // The state was reset on the drop; the reconnect's refresh fills [1.37] back in.
            val read = withTimeoutOrNull(VERIFY_MS) { headphones.state.first { it.leAudioEnabled != null || !it.isConnected } }
            when {
                read != null && !read.isConnected -> return messenger.show(R.string.conn_le_result_unknown)
                else -> read?.leAudioEnabled
            }
        } else {
            // Never restarted: the firmware ignored the write. Re-read in place to be sure.
            if (runCatching { headphones.refresh() }.isFailure) return messenger.show(R.string.conn_le_result_unknown)
            headphones.state.value.leAudioEnabled
        }
        when (now) {
            target -> messenger.show(if (target) R.string.conn_le_result_on else R.string.conn_le_result_off)
            null -> messenger.show(R.string.conn_le_result_no_switch)
            else -> messenger.show(R.string.conn_le_result_unchanged)
        }
    }

    fun setLatency(ms: Int) = launchCatching { headphones.setLatencyMs(ms) }

    fun acknowledgeLatency() { LatencySession.acknowledged.value = true }

    private companion object {
        /** Reconnects take ~10-20 s; well past that the banner is only misleading. */
        const val RESTART_TIMEOUT_MS = 60_000L

        /** A restart drops the link within a few seconds of the write; no drop by then means it was ignored. */
        const val DROP_WAIT_MS = 12_000L

        /** The reconnect's refresh, including the slow reads on older firmware. */
        const val VERIFY_MS = 30_000L
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

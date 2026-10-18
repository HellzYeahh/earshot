package dev.earshot.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.earshot.core.HeadphoneState
import kotlinx.coroutines.delay

/**
 * Whether the connected headphones' settings have been read, so a null value means "this model
 * doesn't have it" rather than "not read yet". Screens hide unsupported rows only after that;
 * while loading they keep showing them disabled, so nothing jumps around during a connect.
 */
object SettingsLoad {
    /** How long the state must sit idle after connecting before nulls count as "unsupported". */
    const val SETTLE_MILLIS = 2_500L

    /**
     * The firmware version is read by the full refresh on every model and applied with the rest of
     * it in one state update, so once it is there the refresh has landed.
     */
    fun looksRefreshed(state: HeadphoneState): Boolean = state.isConnected && state.info?.firmwareVersion != null
}

/**
 * [SettingsLoad.looksRefreshed], or connected and idle for [SettingsLoad.SETTLE_MILLIS] (for
 * firmware whose version GET fails). Latched per connection and device so later commands,
 * which toggle `isBusy`, don't make rows flicker.
 */
@Composable
fun rememberSettingsLoaded(state: HeadphoneState): Boolean {
    val connected = state.isConnected
    val address = state.connection.deviceAddress
    var settled by remember(connected, address) { mutableStateOf(false) }
    LaunchedEffect(connected, address, state.isBusy) {
        if (connected && !state.isBusy && !settled) {
            delay(SettingsLoad.SETTLE_MILLIS)
            settled = true
        }
    }
    return connected && (settled || SettingsLoad.looksRefreshed(state))
}

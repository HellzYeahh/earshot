package dev.earshot.bt

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether the headset Earshot shows is connected to the phone for audio (A2DP, HFP or LE Audio),
 * apart from Earshot's own control link. The two differ: a Bose whose control channel the Bose app
 * holds plays music and takes calls fine while Earshot reports DISCONNECTED. Before 0.6 that also
 * stopped the foreground service after two minutes, and with it the call-volume boost and the
 * automations. The router puts this into `ConnectionState.audioLinked`.
 */
class AudioLinkMonitor(
    context: Context,
    private val finder: DeviceFinder,
    private val scope: CoroutineScope,
) {
    private val _linked = MutableStateFlow<Boolean?>(null)

    /** True / false for the followed address, null while unknown or nothing is followed. */
    val linked: StateFlow<Boolean?> = _linked.asStateFlow()

    @Volatile
    private var address: String? = null

    private val serial = Dispatchers.Default.limitedParallelism(1)
    private var pending: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val a = address ?: return
            val from = DeviceFinder.deviceAddressFrom(intent)
            // Adapter on/off has no device; a profile change on any device can be the twin's.
            if (from == null || from.equals(a, ignoreCase = true) || finder.twinsOf(a).any { it.address.equals(from, ignoreCase = true) }) {
                recheck()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(DeviceFinder.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // Protected system broadcasts, as in ConnectionManager.
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /** Follow [address] (the shown headset), or nothing. */
    fun follow(address: String?) {
        if (address.equals(this.address, ignoreCase = true) && _linked.value != null) return
        this.address = address
        if (address == null) _linked.value = null
        recheck()
    }

    /** One query after the burst of broadcasts a link-up or link-down sends settles. */
    private fun recheck() {
        pending?.cancel()
        pending = scope.launch(serial) {
            delay(SETTLE_MS)
            val a = address ?: return@launch
            val result = finder.isAudioConnected(a)
            // A proxy that didn't answer keeps the last known value.
            if (result != null && a == address) _linked.value = result
        }
    }

    private companion object {
        const val SETTLE_MS = 400L
    }
}

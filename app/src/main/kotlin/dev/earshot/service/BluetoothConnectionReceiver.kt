package dev.earshot.service

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.earshot.appContainer
import dev.earshot.bt.DeviceFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manifest-registered: wakes the process when the headphones link to the phone and, if
 * `AppPreferences.autoConnectOnBluetooth` is on, starts the service and connects.
 *
 * This is the *only* link-up path: [dev.earshot.bt.ConnectionManager]'s own receiver does not
 * listen for ACL / A2DP connected, because a manifest receiver is delivered whether or not the
 * process is alive and the same broadcast used to reach the manager twice. `connect()` is
 * idempotent, so the ACL and A2DP broadcasts for one link-up are harmless together.
 *
 * Starting a foreground service from here is allowed on Android 12+ because a Bluetooth
 * broadcast that needs BLUETOOTH_CONNECT is one of the documented background-start
 * exemptions; the repository still guards the call in case an OEM disagrees.
 *
 * ACL_DISCONNECTED needs nothing: if the process is alive, [dev.earshot.bt.ConnectionManager]
 * has its own receiver and the socket notices anyway; if it is dead there is nothing to tell.
 * BOOT_COMPLETED does nothing here: the headphones are not linked yet at boot, Android restricts
 * foreground-service starts from boot broadcasts, and ACL_CONNECTED follows soon after. It stays
 * subscribed because receiving it starts the process, and `ExtrasInitializer` re-arms the schedule
 * alarms a reboot cleared (dropping it in 0.8 was caught in review and reverted).
 *
 * Headsets linked through the universal remote also reach [HeadsetPresenceService], Android's
 * companion-device path, which survives OEM limits on manifest receivers. Both may fire for one
 * link-up; this receiver stays for the headsets that were never linked.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val address = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> DeviceFinder.deviceAddressFrom(intent)
            // LE Audio links up without A2DP (earbuds on LE Audio only): its own profile broadcast.
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, DeviceFinder.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED ->
                if (DeviceFinder.isProfileConnected(intent)) DeviceFinder.deviceAddressFrom(intent) else null
            else -> null
        } ?: return

        val repository = context.appContainer.headphones as? RoutingHeadphoneRepository
        if (repository == null) {
            Log.d(TAG, "link up for $address, but the repository is not the routing one; ignoring")
            return
        }
        if (!autoConnectEnabled(context)) {
            Log.d(TAG, "link up for $address, auto-connect off")
            return
        }

        // Reading the saved address is a DataStore read: finish it asynchronously but within
        // the receiver's ~10 s budget.
        val pending = goAsync()
        scope.launch {
            try {
                val ours = withTimeoutOrNull(RECEIVER_BUDGET_MS) { repository.isOurDevice(address) } ?: false
                if (ours) {
                    Log.i(TAG, "headset linked ($address), connecting")
                    repository.followLinkUp(address)
                    // Awaited: the receiver must not finish before the service and the manager have started.
                    repository.connectNow()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun autoConnectEnabled(context: Context): Boolean =
        runCatching { context.appContainer.automation.preferences.value.autoConnectOnBluetooth }.getOrDefault(true)

    private companion object {
        const val TAG = "Earshot/BtReceiver"
        const val RECEIVER_BUDGET_MS = 8_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

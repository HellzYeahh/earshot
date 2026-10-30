package dev.earshot.service

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.earshot.appContainer
import dev.earshot.bt.DeviceFinder
import dev.earshot.core.AppPreferences
import dev.earshot.core.AutomationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

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
 * Every link and profile broadcast, ACL_DISCONNECTED included, is also handed to the diagnostics
 * timeline ([dev.earshot.bt.TimelineRecorder.onLinkBroadcast]) before anything else.
 *
 * Headsets linked through the universal remote also reach [HeadsetPresenceService], Android's
 * companion-device path, which survives OEM limits on manifest receivers. Both may fire for one
 * link-up; this receiver stays for the headsets that were never linked.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The diagnostics timeline gets every link and profile change, down ones included, from
        // here only: this receiver runs whether or not the process was alive. Its write is awaited
        // below, so a process this broadcast cold-started isn't killed before the event is on disk.
        val timeline: Job? = if (intent.action == Intent.ACTION_BOOT_COMPLETED) null else {
            runCatching { context.appContainer.timeline.onLinkBroadcast(intent) }
                .onFailure { Log.w(TAG, "timeline: ${it.javaClass.simpleName}") }
                .getOrNull()
        }
        val address = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> DeviceFinder.deviceAddressFrom(intent)
            // LE Audio links up without A2DP (earbuds on LE Audio only): its own profile broadcast.
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, DeviceFinder.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED ->
                if (DeviceFinder.isProfileConnected(intent)) DeviceFinder.deviceAddressFrom(intent) else null
            else -> null
        }

        val repository = context.appContainer.headphones as? RoutingHeadphoneRepository
        val linkUp = when {
            address == null -> false
            repository == null -> {
                Log.d(TAG, "link up for $address, but the repository is not the routing one; ignoring")
                false
            }
            else -> true
        }
        if (!linkUp && timeline == null) return
        val automation = if (linkUp) runCatching { context.appContainer.automation }.getOrNull() else null

        // The auto-connect choice and the saved address are DataStore reads: finish them
        // asynchronously but within the receiver's ~10 s budget. The choice is never read from
        // `preferences.value` here: see [autoConnectAllowed].
        val pending = goAsync()
        scope.launch {
            try {
                if (linkUp && address != null && repository != null) {
                    val ours = withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                        if (automation != null && !autoConnectAllowed(automation)) {
                            Log.d(TAG, "link up for $address, auto-connect off")
                            false
                        } else {
                            isOurs(repository, address)
                        }
                    } ?: false
                    if (ours) {
                        Log.i(TAG, "headset linked ($address), connecting")
                        repository.followLinkUp(address)
                        try {
                            // Awaited: the receiver must not finish before the service and the manager have started.
                            repository.connectNow()
                        } catch (e: IOException) {
                            // Picking the stack reads the same store (the "Headphone type"): same
                            // reasoning as in isOurs. The user can still connect from the app.
                            Log.w(TAG, "link up: the saved headset settings can't be read (${e.javaClass.simpleName}); not connecting")
                        }
                    }
                }
                timeline?.let { withTimeoutOrNull(TIMELINE_WAIT_MS) { it.join() } }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * [RoutingHeadphoneRepository.isOurDevice] reads DataStore (the saved headset, directly and
     * again through the finder's candidates), and a store that can't be read throws IOException. This
     * coroutine runs in a scope without an exception handler, so an escaping IOException would
     * crash the whole process from a background broadcast (audit R24). An unreadable store means
     * "not ours": nothing is connected, and the user can still connect from the app.
     * CancellationException is not an IOException, so the receiver's timeout still works.
     */
    private suspend fun isOurs(repository: RoutingHeadphoneRepository, address: String): Boolean = try {
        repository.isOurDevice(address)
    } catch (e: IOException) {
        // No address: this broadcast fires for every device that links up, not only headsets.
        Log.w(TAG, "link up: the saved headset can't be read (${e.javaClass.simpleName}); treating the device as not ours")
        false
    }

    private companion object {
        const val TAG = "Earshot/BtReceiver"
        const val RECEIVER_BUDGET_MS = 8_000L

        /** The timeline write is a short file append; bounded so the whole receiver stays within its budget. */
        const val TIMELINE_WAIT_MS = 1_500L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** How long a link-up waits for the saved auto-connect choice before treating it as off. */
internal const val AUTO_CONNECT_READ_MS = 2_000L

/**
 * Whether a headset that just linked up may be connected to without the user asking: their saved
 * "Auto-connect" choice. Shared by every path that connects on its own (this receiver, the sticky
 * restart of [HeadphoneService], [HeadsetPresenceService]).
 *
 * Read from the store, never from `AutomationRepository.preferences.value`: that StateFlow holds
 * the default (auto-connect on) until the store's first read lands, and the broadcast or rebind
 * that cold-starts the process usually gets here first. With auto-connect off, Earshot would then
 * take the headset's single control channel away from the maker's app (audit R04). A store that
 * doesn't answer within [timeoutMs] counts as off: the user can still connect from the app, and
 * the A2DP broadcast that follows the ACL one asks again.
 */
internal suspend fun autoConnectAllowed(automation: AutomationRepository, timeoutMs: Long = AUTO_CONNECT_READ_MS): Boolean =
    withTimeoutOrNull(timeoutMs) { automation.storedPreferences() }?.autoConnectOnBluetooth ?: false

/**
 * The preferences as saved, waiting for the store's first read if it hasn't landed yet.
 *
 * The contract has no plain read, so this borrows [AutomationRepository.updatePreferences]: it
 * hands its transform the stored value inside one store transaction (AutomationRepositoryImpl
 * decodes it from DataStore there), and the transform throws before anything is written, which
 * aborts the transaction (DataStore writes nothing when the transform throws). A store that can't
 * be read gives what the rest of the app runs on, [AutomationRepository.preferences].
 *
 * A plain suspend read on the contract (in AutomationRepositoryImpl
 * `store.data.catch { emit(emptyPreferences()) }.map(::decodePreferences).first()`) would make
 * this detour unnecessary.
 */
internal suspend fun AutomationRepository.storedPreferences(): AppPreferences {
    try {
        updatePreferences { stored -> throw StoredPreferencesRead(stored) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Searched along the causes in case the store wrapped it on the way out.
        generateSequence<Throwable>(e) { it.cause }.filterIsInstance<StoredPreferencesRead>().firstOrNull()
            ?.let { return it.preferences }
        // Unreadable: the app shows and runs on the defaults then, so does this.
    }
    return preferences.value
}

/**
 * Carries the stored value out of [storedPreferences]' aborted transaction. Only this constructor,
 * so coroutine stack-trace recovery can't replace it with a copy that lost [preferences]; no stack
 * trace, it is never logged.
 */
private class StoredPreferencesRead(val preferences: AppPreferences) : RuntimeException(null, null, false, false)

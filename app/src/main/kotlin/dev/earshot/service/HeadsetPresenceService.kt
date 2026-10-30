package dev.earshot.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.earshot.appContainer
import dev.earshot.bt.CompanionPresence
import dev.earshot.bt.DeviceFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bound by Android while a headset linked through the universal remote is present
 * ([CompanionPresence]): the companion-device way to hear that it connected, with the process
 * started for us if it was dead and the right to start the foreground service from the background.
 *
 * What counts as "connected":
 *  - Android 16+: `EVENT_BT_CONNECTED`, the classic link coming up, exactly what the ACL receiver
 *    reacts to. BLE "appeared" events are ignored: a headset merely in range must not be paged,
 *    since an RFCOMM connect would pull it away from another phone.
 *  - Android 12–15: "appeared" can also mean an advert was seen, so Earshot waits (up to
 *    [AUDIO_WAIT_MS]) for the headset to be linked for audio before it connects.
 *
 * Then it does what [BluetoothConnectionReceiver] does: follow the link-up and connect. Both paths
 * may fire for one link-up; connecting is idempotent.
 */
@RequiresApi(Build.VERSION_CODES.S)
class HeadsetPresenceService : CompanionDeviceService() {

    /**
     * Android binds this service only while a linked headset is present, and rebinds it on its own
     * about 10 s after Earshot's process dies (seen on One UI 9, Android 17: "Start proc … for
     * bound-service HeadsetPresenceService", even after a force-stop). A rebind delivers no new
     * event, since the headset was already there, so a fresh start connects to whichever linked
     * headset is linked for audio, unless the process ended by the user's own force-stop.
     */
    override fun onCreate() {
        super.onCreate()
        // Force-stopping Earshot is how a user hands the one control channel to the maker's app;
        // a rebind right after must not grab it back. A real link-up event still connects.
        if (lastExitWasUserStop()) {
            Log.i(TAG, "started after a user force-stop; not reconnecting until the next link-up")
            return
        }
        CompanionPresence(applicationContext).linkedAddresses().forEach { connect(it, waitForAudio = true) }
    }

    /** True when the previous process ended by the user's force-stop (Settings or adb). */
    private fun lastExitWasUserStop(): Boolean = runCatching {
        val am = getSystemService(ActivityManager::class.java)
        am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.reason == ApplicationExitInfo.REASON_USER_REQUESTED
    }.getOrDefault(false)

    /**
     * Abstract on Android 12/12L (concrete from 13): without this override the system's call when a
     * linked headset goes away throws AbstractMethodError there. Nothing to do: the connection
     * manager notices the dropped link on its own.
     */
    @Deprecated("Android 13 delivers the AssociationInfo overload")
    override fun onDeviceDisappeared(address: String) = Unit

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event != DevicePresenceEvent.EVENT_BT_CONNECTED) return
        val address = CompanionPresence(this).addressOf(event.associationId) ?: return
        connect(address, waitForAudio = false)
    }

    @Deprecated("Android 16 delivers onDevicePresenceEvent")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) return
        val address = associationInfo.deviceMacAddress?.toString() ?: return
        connect(address, waitForAudio = true)
    }

    @Deprecated("Android 13 delivers the AssociationInfo overload")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        connect(address, waitForAudio = true)
    }

    private fun connect(address: String, waitForAudio: Boolean) {
        val app = applicationContext
        val repository = app.appContainer.headphones as? RoutingHeadphoneRepository ?: return
        val automation = runCatching { app.appContainer.automation }.getOrNull()
        scope.launch {
            // The saved choice, read from the store, never `preferences.value`: a rebind ~10 s after
            // the process died gets here before DataStore's first read lands, while that StateFlow
            // still holds the default "on" (audit R04, see [autoConnectAllowed]).
            if (automation != null && !autoConnectAllowed(automation)) {
                Log.d(TAG, "$address present, auto-connect off")
                return@launch
            }
            if (waitForAudio && !linkedForAudio(app, address)) {
                Log.d(TAG, "$address present but not linked for audio; leaving it to the link-up broadcast")
                return@launch
            }
            Log.i(TAG, "linked headset connected ($address), connecting")
            repository.followLinkUp(address)
            repository.connectNow()
        }
    }

    private suspend fun linkedForAudio(app: Context, address: String): Boolean {
        val finder = DeviceFinder(app, app.appContainer.devicePrefs)
        return withTimeoutOrNull(AUDIO_WAIT_MS) {
            while (finder.isAudioConnected(address) != true) delay(AUDIO_POLL_MS)
            true
        } ?: false
    }

    private companion object {
        const val TAG = "Earshot/Presence"
        const val AUDIO_WAIT_MS = 8_000L
        const val AUDIO_POLL_MS = 1_000L

        // Outlives an unbind: a connect that started must finish even if the headset drops at once.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

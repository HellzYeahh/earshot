package dev.earshot.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.earshot.appContainer
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ModeResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and so the RFCOMM socket) alive while the
 * headphones are connected, and owns the persistent notification.
 *
 * It holds no Bluetooth state of its own: everything is read from `appContainer.headphones`.
 * Started by [ServiceHeadphoneRepository.connect] (and by the notification's own buttons);
 * stopped by `disconnect()`, by [ACTION_DISCONNECT], or on its own after [IDLE_STOP_MS]
 * without a connection. `AppPreferences.persistentNotification` then keeps the notification
 * (with its Connect button), never the service: a `connectedDevice` service is for talking to a
 * device, and the link-up receiver and [HeadsetPresenceService] bring it back when one links up.
 *
 * Every stop goes through a start id ([stopNow] uses `stopSelfResult`, [stop] sends
 * [ACTION_STOP]): a `startForegroundService` still on its way when the service stops must find it
 * alive and call `startForeground`, or Android crashes the app.
 *
 * Android 14+ rules observed here: the type is declared in the manifest, `startForeground`
 * is called from every `onStartCommand` before anything else (5 s budget), and the
 * `connectedDevice` type needs the runtime BLUETOOTH_CONNECT grant, which the repository
 * checks before starting us. A `SecurityException` from `startForeground` means that grant
 * disappeared under us; we just stop.
 */
class HeadphoneService : LifecycleService() {

    private val repository: HeadphoneRepository get() = appContainer.headphones
    private lateinit var notification: ServiceNotification
    private var idleStop: Job? = null
    /**
     * Parent of the state collectors. One job, so [stopNow] also cancels a collector that is still
     * inside its own `launch` (Main.immediate runs it at once, and it may be what called stopNow).
     */
    private var collectors: Job? = null
    private var foreground = false
    /** The last start handled; [stopNow] stops only if no start arrived after it. */
    private var lastStartId = 0

    /** Set by [stopNow]; a state update that lands after it must not call startForeground again. */
    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        running = true
        notification = ServiceNotification(this)
        notification.ensureChannel()
        if (!goForeground(repository.state.value)) return
        startCollectors()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        if (stopping && intent?.action == ACTION_STOP) {
            // Already on the way down (disconnect() sends this right after ACTION_DISCONNECT's
            // stopNow): nothing to bring back. [stop] sends it as a plain start, so no
            // startForeground is owed.
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // stopNow() kept the service because this start was already on its way: it still owes
        // startForeground, and what it asks for (a link-up, a notification tap) gets a live service.
        val revived = stopping
        stopping = false
        if (!goForeground(repository.state.value)) return START_NOT_STICKY
        if (revived) startCollectors()

        when (intent?.action) {
            // Sticky restart after the system killed us: pick the connection back up if wanted.
            // The saved choice, not the not-yet-loaded default (autoConnectAllowed, audit R04).
            null -> lifecycleScope.launch {
                if (autoConnectAllowed(appContainer.automation)) repository.connect()
                else Log.d(TAG, "restarted, auto-connect off")
            }
            ACTION_START -> Unit // repository.connect() already drove the manager; we only needed to come up
            ACTION_CONNECT -> repository.connect()
            ACTION_SET_MODE -> intent.getIntExtra(EXTRA_MODE_INDEX, -1).takeIf { it >= 0 }?.let { index ->
                // The slot is the headset's the button was drawn for; another one's slot means something else.
                if (ModeResolver.slotStillApplies(intent.getStringExtra(EXTRA_MODE_ADDRESS), repository.state.value.connection.deviceAddress)) {
                    runCommand("set mode") { setMode(index) }
                } else {
                    Log.i(TAG, "set mode from a notification for another headset ignored")
                }
            }
            ACTION_TOGGLE_ANC -> runCommand("toggle ANC") {
                val noise = state.value.noise ?: throw HeadphoneCommandException("Noise settings not loaded yet")
                setAncEnabled(!noise.ancEnabled)
            }
            ACTION_CYCLE_MODE -> runCommand("cycle mode") {
                nextModeIndex(state.value)?.let { setMode(it) }
            }
            ACTION_DISCONNECT -> {
                repository.disconnect()
                stopNow()
            }
            // No UI exposes this: powering off from a notification tap with no confirmation is
            // too easy to hit by accident. Kept for the tile/widget agents to opt into.
            ACTION_POWER_OFF -> runCommand("power off") { powerOff() }
            ACTION_STOP -> stopNow()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        idleStop?.cancel()
        super.onDestroy()
    }

    private fun startCollectors() {
        collectors?.cancel()
        val parent = SupervisorJob(lifecycleScope.coroutineContext[Job])
        collectors = parent
        lifecycleScope.launch(parent) {
            repository.state
                .map { ServiceNotification.Snapshot.of(it) }
                .distinctUntilChanged()
                .conflate()
                .collect { snapshot ->
                    goForeground(snapshot)
                    delay(NOTIFICATION_THROTTLE_MS) // conflate() drops the bursts in between
                }
        }
        lifecycleScope.launch(parent) {
            // The audio link counts too: a headset playing music or on a call keeps the call boost and
            // the automations alive even when the control link is down (Bose app holding it).
            repository.state.map { it.connection.status to (it.connection.audioLinked == true) }.distinctUntilChanged()
                .collect { (status, audio) -> onStatus(if (audio && status == ConnectionStatus.DISCONNECTED) ConnectionStatus.CONNECTING else status) }
        }
    }

    private fun onStatus(status: ConnectionStatus) {
        idleStop?.cancel()
        idleStop = null
        when (status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING -> Unit
            // Nothing to keep alive and nothing the notification can do about it.
            ConnectionStatus.NO_PERMISSION, ConnectionStatus.NO_PAIRED_DEVICE -> stopNow()
            // Nothing linked, not even for audio (that counts as CONNECTING above): a connectedDevice
            // service has nothing to talk to, so it stops whatever the persistent notification says
            // (Play allows the type only while the app works with a device, audit R34). The
            // notification may stay, detached, with its Connect button.
            ConnectionStatus.DISCONNECTED -> idleStop = lifecycleScope.launch {
                delay(IDLE_STOP_MS)
                val keep = persistentNotificationWanted()
                Log.d(TAG, "idle for ${IDLE_STOP_MS / 1000}s, stopping" + if (keep) " (notification kept)" else "")
                stopNow(keepNotification = keep)
            }
        }
    }

    /** Idempotent; also how the notification is updated (no POST_NOTIFICATIONS needed for the FGS one). */
    private fun goForeground(state: HeadphoneState): Boolean = goForeground(ServiceNotification.Snapshot.of(state))

    private fun goForeground(snapshot: ServiceNotification.Snapshot): Boolean {
        if (stopping) return false
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(this, ServiceNotification.NOTIFICATION_ID, notification.build(snapshot), type)
            foreground = true
            true
        } catch (e: Exception) {
            // SecurityException: BLUETOOTH_CONNECT revoked; ForegroundServiceStartNotAllowedException:
            // started from a context Android 12+ does not accept. Either way we cannot stay up.
            Log.w(TAG, "startForeground failed: ${e.message}")
            stopNow()
            false
        }
    }

    /** [keepNotification]: leave the notification up as an ordinary one (persistent notification on). */
    private fun stopNow(keepNotification: Boolean = false) {
        stopping = true
        idleStop?.cancel()
        idleStop = null
        collectors?.cancel()
        collectors = null
        if (foreground) {
            ServiceCompat.stopForeground(this, if (keepNotification) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        foreground = false
        // Not stopSelf(): a startForegroundService that Android already took but onStartCommand
        // hasn't seen yet (a link-up racing the idle stop) would lose its service before its
        // startForeground, which crashes the app. With the last id handled, that newer start keeps
        // the service and revives it in onStartCommand (audit R18).
        stopSelfResult(lastStartId)
    }

    private fun runCommand(label: String, block: suspend HeadphoneRepository.() -> Unit) {
        lifecycleScope.launch {
            try {
                repository.block()
            } catch (e: HeadphoneCommandException) {
                // The repository already emitted the state; the UI shows errors, a notification cannot.
                Log.w(TAG, "$label failed: ${e.message}")
            }
        }
    }

    private fun persistentNotificationWanted(): Boolean =
        runCatching { appContainer.automation.preferences.value.persistentNotification }.getOrDefault(true)

    companion object {
        private const val TAG = "Earshot/Service"
        private const val PKG = "dev.earshot.service"

        /** Bring the service up; no command. Sent by the repository. */
        const val ACTION_START = "$PKG.START"
        /** Connect (or reconnect); for the notification's Connect button. */
        const val ACTION_CONNECT = "$PKG.CONNECT"
        const val ACTION_SET_MODE = "$PKG.SET_MODE"
        const val ACTION_TOGGLE_ANC = "$PKG.TOGGLE_ANC"
        const val ACTION_CYCLE_MODE = "$PKG.CYCLE_MODE"
        const val ACTION_DISCONNECT = "$PKG.DISCONNECT"
        const val ACTION_POWER_OFF = "$PKG.POWER_OFF"
        /** Sent by [stop] as a plain start (never with startForegroundService, see onStartCommand). */
        const val ACTION_STOP = "$PKG.STOP"
        const val EXTRA_MODE_INDEX = "mode_index"
        /** Optional: the headset [EXTRA_MODE_INDEX] belongs to. */
        const val EXTRA_MODE_ADDRESS = "mode_address"

        const val IDLE_STOP_MS = 2 * 60 * 1000L
        private const val NOTIFICATION_THROTTLE_MS = 500L

        /** An instance is up, or a start of one was accepted: [stop] has something to reach. */
        @Volatile
        private var running = false

        fun start(context: Context) {
            val intent = Intent(context, HeadphoneService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
                running = true
            } catch (e: Exception) {
                // Background-start restriction (Android 12+). The manager still connects
                // in-process; the process just has no FGS protection until the UI comes up.
                Log.w(TAG, "could not start the foreground service: ${e.message}")
            }
        }

        /**
         * Stops the service through its own onStartCommand, never with stopService(): that tears
         * the service down even while a startForegroundService() is still on its way (a quick
         * disconnect and reconnect), before that start's startForeground, and Android answers by
         * crashing the app. Queued behind such a start, [ACTION_STOP] stops with its own start id,
         * so a newer start keeps the service up. A plain start of an already started service is
         * allowed from the background. With no service there is nothing to stop, but the
         * notification an idle stop kept (persistent notification on) may still be up: a
         * disconnect removes it, as it does when the service is running (ACTION_STOP, REMOVE).
         */
        fun stop(context: Context) {
            if (!running) {
                ServiceNotification.cancelKept(context)
                return
            }
            try {
                context.startService(Intent(context, HeadphoneService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "could not stop the foreground service: ${e.message}")
            }
        }

        /**
         * Which mode a "cycle" tap should go to: the next favourite after the current one, or the
         * next of the headset's first three modes when nothing is marked favourite (Quiet/Aware/
         * Immersion on the QC Ultra; never Bose slot numbers on another make). Null when the modes
         * have not been read yet.
         */
        fun nextModeIndex(state: HeadphoneState): Int? {
            val pool = state.favoriteModes.filter { it.isConfigured }.map { it.index }
                .ifEmpty { ModeResolver.defaultCycle(state.modes) }
            if (pool.isEmpty()) return null
            val at = pool.indexOf(state.currentModeIndex)
            return pool[(at + 1) % pool.size]
        }
    }
}

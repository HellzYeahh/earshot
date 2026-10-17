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
import dev.earshot.core.ListeningMode
import kotlinx.coroutines.Job
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
 * without a connection unless `AppPreferences.persistentNotification` says to stay.
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
    private val collectors = ArrayList<Job>()
    private var foreground = false

    /** Set by [stopNow]; a state update that lands after stopSelf() must not call startForeground again. */
    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        notification = ServiceNotification(this)
        notification.ensureChannel()
        if (!goForeground(repository.state.value)) return

        collectors += lifecycleScope.launch {
            repository.state
                .map { ServiceNotification.Snapshot.of(it) }
                .distinctUntilChanged()
                .conflate()
                .collect { snapshot ->
                    goForeground(snapshot)
                    delay(NOTIFICATION_THROTTLE_MS) // conflate() drops the bursts in between
                }
        }
        collectors += lifecycleScope.launch {
            repository.state.map { it.connection.status }.distinctUntilChanged().collect(::onStatus)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!goForeground(repository.state.value)) return START_NOT_STICKY

        when (intent?.action) {
            // Sticky restart after the system killed us: pick the connection back up if wanted.
            null -> if (autoConnectEnabled()) repository.connect()
            ACTION_START -> Unit // repository.connect() already drove the manager; we only needed to come up
            ACTION_CONNECT -> repository.connect()
            ACTION_SET_MODE -> intent.getIntExtra(EXTRA_MODE_INDEX, -1).takeIf { it >= 0 }?.let { index ->
                runCommand("set mode") { setMode(index) }
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
        idleStop?.cancel()
        super.onDestroy()
    }

    private fun onStatus(status: ConnectionStatus) {
        idleStop?.cancel()
        idleStop = null
        when (status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING -> Unit
            // Nothing to keep alive and nothing the notification can do about it.
            ConnectionStatus.NO_PERMISSION, ConnectionStatus.NO_PAIRED_DEVICE -> stopNow()
            ConnectionStatus.DISCONNECTED -> idleStop = lifecycleScope.launch {
                delay(IDLE_STOP_MS)
                if (persistentNotificationWanted()) {
                    Log.d(TAG, "idle, staying up (persistent notification on)")
                } else {
                    Log.d(TAG, "idle for ${IDLE_STOP_MS / 1000}s, stopping")
                    stopNow()
                }
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

    private fun stopNow() {
        stopping = true
        idleStop?.cancel()
        collectors.forEach { it.cancel() }
        collectors.clear()
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
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

    private fun autoConnectEnabled(): Boolean =
        runCatching { appContainer.automation.preferences.value.autoConnectOnBluetooth }.getOrDefault(true)

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
        const val ACTION_STOP = "$PKG.STOP"
        const val EXTRA_MODE_INDEX = "mode_index"

        const val IDLE_STOP_MS = 2 * 60 * 1000L
        private const val NOTIFICATION_THROTTLE_MS = 500L

        fun start(context: Context) {
            val intent = Intent(context, HeadphoneService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Background-start restriction (Android 12+). The manager still connects
                // in-process; the process just has no FGS protection until the UI comes up.
                Log.w(TAG, "could not start the foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, HeadphoneService::class.java)) }
        }

        /**
         * Which mode a "cycle" tap should go to: the next favourite after the current one, or the
         * next of Quiet/Aware/Immersion when nothing is marked favourite. Null when the modes have
         * not been read yet.
         */
        fun nextModeIndex(state: HeadphoneState): Int? {
            val pool = state.favoriteModes.filter { it.isConfigured }.map { it.index }
                .ifEmpty { listOf(ListeningMode.QUIET, ListeningMode.AWARE, ListeningMode.IMMERSION) }
            if (pool.isEmpty()) return null
            val at = pool.indexOf(state.currentModeIndex)
            return pool[(at + 1) % pool.size]
        }
    }
}

package dev.earshot.sleep

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
import dev.earshot.core.SleepSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while a night runs or an alarm rings: Android 17 silences
 * audio, focus and volume calls from an app that is neither visible nor running a foreground
 * service (docs/sleep-mode.md §4.4). Type `mediaPlayback`.
 *
 * Like `HeadphoneService` it holds no state: everything is read from the repository
 * ([SleepRepositoryImpl]), or from [LockedAlarms] before the first unlock after a restart. It
 * stops itself once the session is idle (or snoozed). Its notification buttons are handled here:
 * a notification tap counts as a user action, so audio started from one is allowed. The
 * repository runs those commands to the end even when this service stops meanwhile.
 */
class SleepService : LifecycleService() {
    private lateinit var notifications: SleepNotifications
    private var foreground = false
    private var shownId = 0
    private var lastStartId = 0

    /** Before the first unlock the app's own storage can't be read: only the locked ringer runs. */
    private var locked = false
    private val sessions: StateFlow<SleepSession>
        get() = if (locked) LockedAlarms.state else appContainer.sleepRuntime.session

    override fun onCreate() {
        super.onCreate()
        // Before the first unlock, or a ring that began then and still goes on after it.
        locked = LockedAlarms.isLocked(this) || LockedAlarms.isActive
        notifications = SleepNotifications(this)
        notifications.ensureChannels()
        lifecycleScope.launch {
            sessions.map { it }.distinctUntilChanged().collect { session ->
                if (!show(session) && foreground) stopNow()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        // startForeground first, every time: a started FGS must call it within a few seconds, even
        // when a stale notification button arrives after the night already ended.
        val showing = show(sessions.value) || placeholder()
        val action = intent?.action
        if (locked) {
            when (action) {
                ACTION_SNOOZE -> LockedAlarms.snooze(this)
                ACTION_DISMISS, ACTION_STOP -> LockedAlarms.stop(this)
            }
            if (!showing || !show(sessions.value)) stopNow()
            return START_NOT_STICKY
        }
        val sleep = appContainer.sleepRuntime
        lifecycleScope.launch {
            // The repository runs each of these to the end even if this scope is cancelled.
            when (action) {
                ACTION_EXTEND -> runCatching { sleep.extend(EXTEND_MINUTES) }
                ACTION_STOP -> runCatching { sleep.stop() }
                ACTION_SNOOZE -> runCatching { sleep.snooze() }
                ACTION_DISMISS -> runCatching { sleep.dismissAlarm() }
            }
            if (!showing || !show(sleep.state.value.session)) stopNow()
        }
        return START_NOT_STICKY
    }

    /** Posts the notification for [session] as the foreground one; false when there is nothing to show. */
    private fun show(session: SleepSession): Boolean {
        val (id, notification) = when (session) {
            is SleepSession.Running -> SleepNotifications.ID_NIGHT to notifications.night(session)
            is SleepSession.Ringing -> SleepNotifications.ID_ALARM to notifications.alarm(session)
            else -> return false
        }
        return try {
            if (shownId != 0 && shownId != id) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            ServiceCompat.startForeground(this, id, notification, type())
            shownId = id
            foreground = true
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException: started from a context Android doesn't accept.
            Log.w(TAG, "startForeground failed: ${e.message}")
            false
        }
    }

    /** A plain notification that satisfies the foreground-service contract while there is no night to show. */
    private fun placeholder(): Boolean = try {
        ServiceCompat.startForeground(this, SleepNotifications.ID_NIGHT, notifications.placeholder(), type())
        shownId = SleepNotifications.ID_NIGHT
        foreground = true
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed: ${e.message}")
        false
    }

    private fun type(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0

    /**
     * Stops unless a newer start arrived meanwhile (an alarm starting while the night closes):
     * that start's own onStartCommand then keeps the service up.
     */
    private fun stopNow() {
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        shownId = 0
        stopSelfResult(lastStartId)
    }

    companion object {
        private const val TAG = "Earshot/Sleep"
        private const val PKG = "dev.earshot.sleep"
        const val ACTION_START = "$PKG.START"
        const val ACTION_EXTEND = "$PKG.EXTEND"
        const val ACTION_STOP = "$PKG.STOP"
        const val ACTION_SNOOZE = "$PKG.SNOOZE"
        const val ACTION_DISMISS = "$PKG.DISMISS"
        const val EXTEND_MINUTES = 15

        /** True when Android accepted the start; false from a background context it refuses. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, SleepService::class.java).setAction(ACTION_START))
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start the sleep service: ${e.message}")
            false
        }
    }
}

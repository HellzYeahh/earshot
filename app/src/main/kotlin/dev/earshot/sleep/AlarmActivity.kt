package dev.earshot.sleep

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.appContainer
import dev.earshot.core.SleepSession
import dev.earshot.ui.sleep.AlarmRingingScreen
import dev.earshot.ui.theme.EarshotTheme

/**
 * The alarm over the lock screen: opened by the alarm notification's full-screen intent. It shows
 * while an alarm rings and closes itself once it is stopped or snoozed. Being visible is also
 * what lets the alarm sound play on Android 17 if the foreground service couldn't start.
 * Back doesn't stop the alarm; only Stop or Snooze does.
 *
 * Direct-boot aware: before the first unlock after a restart it shows [LockedAlarms]' ring.
 * Stop and Snooze go to the repository, which finishes them even after this screen is gone.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Before the first unlock, or a ring that began then and still goes on after it.
        val locked = LockedAlarms.isLocked(this) || LockedAlarms.isActive
        val sessions = if (locked) LockedAlarms.state else appContainer.sleepRuntime.session
        val app = applicationContext
        setContent {
            EarshotTheme(darkTheme = true) {
                val session by sessions.collectAsStateWithLifecycle()
                when (val s = session) {
                    is SleepSession.Ringing -> AlarmRingingScreen(
                        session = s,
                        onStop = { if (locked) LockedAlarms.stop(app) else app.appContainer.sleepRuntime.dismissAlarmAsync() },
                        onSnooze = { if (locked) LockedAlarms.snooze(app) else app.appContainer.sleepRuntime.snoozeAsync() },
                    )
                    else -> LaunchedEffect(Unit) { finish() }
                }
            }
        }
    }

    // Visible: volume calls work on Android 17 (a pending morning volume can go back).
    override fun onStart() {
        super.onStart()
        if (!LockedAlarms.isLocked(this)) appContainer.sleepRuntime.setAppVisible(true)
    }

    override fun onStop() {
        if (!LockedAlarms.isLocked(this)) appContainer.sleepRuntime.setAppVisible(false)
        super.onStop()
    }
}

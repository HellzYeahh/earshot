package dev.earshot.sleep

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.earshot.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the Sleep tab's `AlarmManager` alarms ([SleepScheduler]) and the system broadcasts that
 * move them, and hands them to the repository. Not exported: only our own pending intents and the
 * system's broadcasts reach it.
 *
 * Direct-boot aware: before the first unlock after a restart only [LockedAlarms] runs (the app's
 * own storage can't be read yet). The work runs in its own scope and is never cancelled by
 * `goAsync`'s budget: an alarm half-handled is worse than one handled late.
 */
class SleepAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (LockedAlarms.isLocked(context)) {
            when (intent.action) {
                Intent.ACTION_LOCKED_BOOT_COMPLETED -> LockedAlarms.rearmAfterLockedBoot(context)
                ACTION_ALARM -> intent.getStringExtra(EXTRA_ALARM_ID)?.let { LockedAlarms.ring(context, it) }
            }
            return
        }
        val pending = goAsync()
        val work = scope.launch {
            try {
                val sleep = context.appContainer.sleepRuntime
                when (intent.action) {
                    ACTION_STEP -> intent.getStringExtra(EXTRA_STEP)
                        ?.let { name -> SleepStep.entries.firstOrNull { it.name == name } }
                        ?.let { sleep.onStep(it) }
                    ACTION_ALARM -> {
                        val kind = intent.getStringExtra(EXTRA_KIND)
                        val id = intent.getStringExtra(EXTRA_ALARM_ID)
                        if (kind != null && id != null) sleep.onAlarm(kind, id)
                    }
                    // The wall clock or the zone moved (alarms are armed in UTC), exact alarms were
                    // just allowed, or the phone was unlocked after a locked boot: re-arm.
                    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                    Intent.ACTION_LOCKED_BOOT_COMPLETED -> sleep.rearmAlarms()
                }
            } catch (e: Exception) {
                Log.w(TAG, "sleep alarm failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        scope.launch {
            withTimeoutOrNull(BUDGET_MS) { work.join() }
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "Earshot/Sleep"
        private const val PKG = "dev.earshot.sleep"
        const val ACTION_STEP = "$PKG.STEP"
        const val ACTION_ALARM = "$PKG.ALARM"
        const val EXTRA_STEP = "step"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val KIND_RING = "ring"
        const val KIND_WAKE_WINDOW = "window"
        const val KIND_SNOOZE = "snooze"

        /** goAsync allows ~10 s; past that the work carries on without holding the broadcast. */
        private const val BUDGET_MS = 8_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

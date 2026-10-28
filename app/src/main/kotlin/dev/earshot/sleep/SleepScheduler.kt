package dev.earshot.sleep

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import dev.earshot.MainActivity

/** The night's scheduled moments. The names are stored in pending intents only, never on disk. */
enum class SleepStep { NOISE_OFF, END, RESTORE, RECONNECT }

/**
 * Arms the night's moments and the wake alarms with `AlarmManager` (docs/sleep-mode.md §4.3):
 * coroutine `delay` stops counting while the CPU sleeps, an alarm doesn't.
 *
 * - Wake alarms: `setAlarmClock`, exact, shown as the next alarm in the status bar, fires in
 *   Doze, and lets the receiver start the foreground service from the background.
 * - Night steps: `setExactAndAllowWhileIdle`.
 * - Without "Alarms & reminders" (SCHEDULE_EXACT_ALARM, Android 12+) both fall back to
 *   `setAndAllowWhileIdle`, which may be minutes late; the tab says so.
 */
class SleepScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarms: AlarmManager? = appContext.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { alarms?.canScheduleExactAlarms() == true }.getOrDefault(false)

    fun armStep(step: SleepStep, atMillis: Long) {
        val manager = alarms ?: return
        val pi = stepIntent(step)
        runCatching {
            if (canScheduleExact()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }.onFailure { Log.w(TAG, "arm $step failed: ${it.message}") }
    }

    fun cancelStep(step: SleepStep) {
        runCatching { alarms?.cancel(stepIntent(step)) }
    }

    fun cancelNightSteps() = listOf(SleepStep.NOISE_OFF, SleepStep.END).forEach(::cancelStep)

    /** A wake alarm ([kind] RING, WAKE_WINDOW or SNOOZE) for [alarmId] at [atMillis]. */
    fun armAlarm(kind: String, alarmId: String, atMillis: Long) {
        val manager = alarms ?: return
        val pi = alarmIntent(kind, alarmId)
        runCatching {
            when {
                // The wake window is a quiet lead-in: exact, but not the alarm the status bar should show.
                kind == SleepAlarmReceiver.KIND_WAKE_WINDOW && canScheduleExact() ->
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                canScheduleExact() -> manager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showIntent()), pi)
                else -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        }.onFailure { Log.w(TAG, "arm alarm failed: ${it.message}") }
    }

    fun cancelAlarm(kind: String, alarmId: String) {
        runCatching { alarms?.cancel(alarmIntent(kind, alarmId)) }
    }

    private fun stepIntent(step: SleepStep): PendingIntent {
        val intent = Intent(appContext, SleepAlarmReceiver::class.java)
            .setAction(SleepAlarmReceiver.ACTION_STEP)
            .putExtra(SleepAlarmReceiver.EXTRA_STEP, step.name)
        return PendingIntent.getBroadcast(appContext, REQUEST_STEP + step.ordinal, intent, FLAGS)
    }

    private fun alarmIntent(kind: String, alarmId: String): PendingIntent {
        val intent = Intent(appContext, SleepAlarmReceiver::class.java)
            .setAction(SleepAlarmReceiver.ACTION_ALARM)
            // The data URI keeps the intents of different alarms and kinds apart (filterEquals).
            .setData("earshot-sleep://alarm/$kind/$alarmId".toUri())
            .putExtra(SleepAlarmReceiver.EXTRA_KIND, kind)
            .putExtra(SleepAlarmReceiver.EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(appContext, REQUEST_ALARM, intent, FLAGS)
    }

    /** What tapping the status bar's alarm opens: the Sleep tab. */
    private fun showIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_TAB, "sleep")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(appContext, REQUEST_SHOW, intent, FLAGS)
    }

    companion object {
        private const val TAG = "Earshot/Sleep"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private const val REQUEST_STEP = 7_300
        private const val REQUEST_ALARM = 7_400
        private const val REQUEST_SHOW = 7_500
    }
}

package dev.earshot.sleep

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import dev.earshot.MainActivity
import dev.earshot.core.SleepAlarm

/** The night's scheduled moments. The names are stored in pending intents only, never on disk. */
enum class SleepStep { NOISE_OFF, END, RESTORE, RECONNECT }

/**
 * Arms the night's moments and the wake alarms with `AlarmManager` (docs/sleep-mode.md §4.3):
 * coroutine `delay` stops counting while the CPU sleeps, an alarm doesn't.
 *
 * - Wake alarms: `setAlarmClock`, exact, shown as the next alarm in the status bar, fires in
 *   Doze, and lets the receiver start the foreground service from the background.
 * - Night steps: `setExactAndAllowWhileIdle`.
 * - Without "Alarms & reminders" (SCHEDULE_EXACT_ALARM, Android 12+; Android 14+ denies it to new
 *   installs) both fall back to `setAndAllowWhileIdle`. Android may deliver that up to an hour
 *   late, and its broadcast may not start a foreground service, so a wake alarm may not sound at
 *   all on a locked phone: [mayEnable] keeps one from being switched on then,
 *   [SleepPlanner.ringsToArm] keeps one already on (a restored backup) from being armed, and the
 *   tab says why. Snoozes and night steps still use the fallback.
 * - Each call falls through to the next way ([armWays]) when Android refuses it, so a refusal
 *   never leaves an alarm unarmed.
 */
class SleepScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarms: AlarmManager? = appContext.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { alarms?.canScheduleExactAlarms() == true }.getOrDefault(false)

    fun armStep(step: SleepStep, atMillis: Long) {
        arm("$step", armWays(showAsAlarmClock = false, exactAllowed = canScheduleExact()), atMillis) { stepIntent(step) }
    }

    fun cancelStep(step: SleepStep) {
        runCatching { alarms?.cancel(stepIntent(step)) }
    }

    fun cancelNightSteps() = listOf(SleepStep.NOISE_OFF, SleepStep.END).forEach(::cancelStep)

    /** A wake alarm ([kind] RING, WAKE_WINDOW or SNOOZE) for [alarmId] at [atMillis]. */
    fun armAlarm(kind: String, alarmId: String, atMillis: Long) {
        // The wake window is a quiet lead-in: exact, but not the alarm the status bar should show.
        val ways = armWays(showAsAlarmClock = kind != SleepAlarmReceiver.KIND_WAKE_WINDOW, exactAllowed = canScheduleExact())
        arm("alarm $kind", ways, atMillis) { alarmIntent(kind, alarmId) }
    }

    /** Tries [ways] in order until AlarmManager takes one. */
    private fun arm(label: String, ways: List<ArmWay>, atMillis: Long, intent: () -> PendingIntent) {
        val manager = alarms ?: return
        val pi = intent()
        for (way in ways) {
            try {
                when (way) {
                    ArmWay.ALARM_CLOCK -> manager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showIntent()), pi)
                    ArmWay.EXACT -> manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                    ArmWay.INEXACT -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                }
                return
            } catch (e: Exception) {
                // SecurityException: the access went away after the check, or this phone answers
                // canScheduleExactAlarms() more generously than the call itself. A later, less exact
                // alarm still beats none (the old code gave up here and armed nothing).
                Log.w(TAG, "arm $label as $way failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
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

    /** How an alarm is handed to AlarmManager, most reliable first. */
    internal enum class ArmWay { ALARM_CLOCK, EXACT, INEXACT }

    companion object {
        private const val TAG = "Earshot/Sleep"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private const val REQUEST_STEP = 7_300
        private const val REQUEST_ALARM = 7_400
        private const val REQUEST_SHOW = 7_500

        /**
         * The ways to try, best first: a wake alarm as an alarm clock (status bar, foreground-service
         * start), a night step or wake window exact, and anything inexact when exact alarms aren't
         * allowed. Every exact way ends in the inexact one, the fallback when Android refuses it.
         */
        internal fun armWays(showAsAlarmClock: Boolean, exactAllowed: Boolean): List<ArmWay> = when {
            !exactAllowed -> listOf(ArmWay.INEXACT)
            showAsAlarmClock -> listOf(ArmWay.ALARM_CLOCK, ArmWay.EXACT, ArmWay.INEXACT)
            else -> listOf(ArmWay.EXACT, ArmWay.INEXACT)
        }

        /**
         * Whether [alarm] may be saved as it is: switched off always, switched on only when
         * exact alarms are allowed. An inexact wake alarm may ring up to an hour late and, unable
         * to start its foreground service, may stay silent on a locked phone (audit R05): better
         * no alarm than one the user trusts that doesn't ring.
         */
        internal fun mayEnable(alarm: SleepAlarm, exactAllowed: Boolean): Boolean = !alarm.enabled || exactAllowed
    }
}

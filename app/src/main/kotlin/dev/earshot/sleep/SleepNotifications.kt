package dev.earshot.sleep

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import dev.earshot.MainActivity
import dev.earshot.R
import dev.earshot.core.SleepPhase
import dev.earshot.core.SleepSession
import java.util.Date

/**
 * The Sleep tab's notifications: the quiet one while a night runs (time left, +15 min, Stop), the
 * alarm one (full-screen over the lock screen, Stop, Snooze), and "Tap to resume sounds" for a
 * night Android 17 won't let play in the background ([resume]). Separate channels so the user can
 * silence the night ones without touching the alarm.
 */
class SleepNotifications(private val context: Context) {
    private val manager: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val nm = manager ?: return
        if (nm.getNotificationChannel(CHANNEL_NIGHT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_NIGHT, context.getString(R.string.sleep_rt_channel_night), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.sleep_rt_channel_night_description)
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ALARM) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALARM, context.getString(R.string.sleep_rt_channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.sleep_rt_channel_alarm_description)
                    // The alarm plays its own sound on the chosen outputs; the notification stays silent.
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    fun night(session: SleepSession.Running): Notification {
        val endsAt = DateFormat.getTimeFormat(context).format(Date(session.endsAtMillis))
        val text = when (session.phase) {
            SleepPhase.FADING_OUT -> context.getString(R.string.sleep_rt_notif_fading, endsAt)
            SleepPhase.TIMER_ONLY -> context.getString(R.string.sleep_rt_notif_timer_only, endsAt)
            else -> context.getString(R.string.sleep_rt_notif_playing, endsAt)
        }
        return NotificationCompat.Builder(context, CHANNEL_NIGHT)
            .setSmallIcon(R.drawable.ic_sleep_moon)
            .setContentTitle(context.getString(R.string.sleep_rt_notif_title))
            .setContentText(text)
            // A chronometer counting down to the end, without waking the app every second.
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(session.endsAtMillis)
            .setShowWhen(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openTab())
            .addAction(0, context.getString(R.string.sleep_rt_action_extend), service(SleepService.ACTION_EXTEND, 1))
            .addAction(0, context.getString(R.string.sleep_rt_action_stop), service(SleepService.ACTION_STOP, 2))
            .build()
    }

    /**
     * "Tap to resume sounds": a night rebuilt in the background after Android closed Earshot. At
     * target 37 Android 17 silences playback from a service started that way, so the sounds wait.
     * The tap starts the service from a notification, which gives it while-in-use status. On the
     * quiet night channel: it must not wake anyone; it is there when the phone is picked up.
     */
    fun resume(): Notification = NotificationCompat.Builder(context, CHANNEL_NIGHT)
        .setSmallIcon(R.drawable.ic_sleep_moon)
        .setContentTitle(context.getString(R.string.a17_sleep_resume_title))
        .setContentText(context.getString(R.string.a17_sleep_resume_text))
        .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.a17_sleep_resume_text)))
        .setSilent(true)
        .setAutoCancel(true)
        .setContentIntent(service(SleepService.ACTION_RESUME, 5))
        .build()

    fun showResume() {
        ensureChannels()
        runCatching { manager?.notify(ID_RESUME, resume()) }
    }

    fun cancelResume() {
        runCatching { manager?.cancel(ID_RESUME) }
    }

    /** Shown for a moment while the service handles a button and finds the night already over. */
    fun placeholder(): Notification = NotificationCompat.Builder(context, CHANNEL_NIGHT)
        .setSmallIcon(R.drawable.ic_sleep_moon)
        .setContentTitle(context.getString(R.string.sleep_rt_notif_title))
        .setSilent(true)
        .build()

    fun alarm(session: SleepSession.Ringing): Notification {
        val title = session.label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.sleep_rt_alarm_title)
        val now = DateFormat.getTimeFormat(context).format(Date())
        val builder = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_sleep_moon)
            .setContentTitle(title)
            .setContentText(session.phoneFallbackReason ?: now)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // Not setSilent: that puts it in a "silent" group whose alerts SystemUI suppresses,
            // full-screen intent included. The channel itself has no sound.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(alarmScreen())
            .setFullScreenIntent(alarmScreen(), true)
            .addAction(0, context.getString(R.string.sleep_rt_action_stop_alarm), service(SleepService.ACTION_DISMISS, 3))
        if (session.canSnooze) builder.addAction(0, context.getString(R.string.sleep_rt_action_snooze), service(SleepService.ACTION_SNOOZE, 4))
        return builder.build()
    }

    private fun openTab(): PendingIntent = PendingIntent.getActivity(
        context, 7_600,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TAB, "sleep").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarmScreen(): PendingIntent = PendingIntent.getActivity(
        context, 7_601,
        Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Notification buttons go to the service: a tap there counts as a user action for Android 17's audio rules. */
    private fun service(action: String, code: Int): PendingIntent = PendingIntent.getForegroundService(
        context, 7_700 + code,
        Intent(context, SleepService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_NIGHT = "sleep_night"
        const val CHANNEL_ALARM = "sleep_alarm"
        const val ID_NIGHT = 4_201
        const val ID_ALARM = 4_202
        const val ID_RESUME = 4_203
    }
}

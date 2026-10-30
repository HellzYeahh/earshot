package dev.earshot.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.earshot.MainActivity
import dev.earshot.R

/**
 * "Tap to boost the call volume": posted during a call when Android 17 ignored the boost because
 * Earshot was closed ([CallVolumeBoost.refusedInBackground]). The tap opens Earshot; a visible app
 * may change volumes, and [CallAudioRepositoryImpl] boosts the call as soon as a screen starts.
 *
 * It opens the app rather than a service: the headset service belongs to the connection, and a
 * visible screen is the one state every Android 17 build accepts for a volume call.
 */
internal class CallBoostNotification(private val context: Context) {
    private val manager: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    fun show() {
        val nm = manager ?: return
        // Without the notification permission (Android 13+) notify() is dropped anyway.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.i(TAG, "boost notification skipped: notifications are off")
            return
        }
        runCatching {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    // High, so it shows over the call screen; silent, so it never beeps into the call.
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.a17_call_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                        description = context.getString(R.string.a17_call_channel_description)
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, REQUEST_CODE,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_TAB, "tools")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile_mode)
                .setContentTitle(context.getString(R.string.a17_call_boost_title))
                .setContentText(context.getString(R.string.a17_call_boost_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.a17_call_boost_text)))
                // No CATEGORY_CALL: that is for the call itself, and Do Not Disturb treats it as one.
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                // A call rarely lasts longer; the call's end cancels it sooner.
                .setTimeoutAfter(TIMEOUT_MS)
                .setContentIntent(open)
                .build()
            nm.notify(ID, notification)
        }.onFailure { Log.w(TAG, "boost notification failed: ${it.javaClass.simpleName}") }
    }

    fun cancel() {
        runCatching { manager?.cancel(ID) }
    }

    private companion object {
        const val TAG = "Earshot/CallAudio"
        const val CHANNEL_ID = "call_boost"
        const val ID = 4_301
        const val REQUEST_CODE = 7_800
        const val TIMEOUT_MS = 2 * 60 * 60_000L
    }
}

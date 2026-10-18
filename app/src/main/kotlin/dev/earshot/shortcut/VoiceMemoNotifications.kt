package dev.earshot.shortcut

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.earshot.R

/** The ongoing "Recording" notification (with Stop) and the "Saved" one that opens the memo. */
internal class VoiceMemoNotifications(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // LOW: visible, silent. The start beep in the headset is the audible confirmation.
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.memo_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.memo_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun starting(): Notification = base()
        .setContentTitle(context.getString(R.string.memo_notif_starting))
        .build()

    /** [startedAtWallClock] drives the chronometer, so the elapsed time ticks without updates from us. */
    fun recording(startedAtWallClock: Long, headsetMic: Boolean, maxMinutes: Int): Notification = base()
        .setContentTitle(context.getString(R.string.memo_notif_recording))
        .setContentText(
            context.getString(
                if (headsetMic) R.string.memo_notif_headset_mic else R.string.memo_notif_phone_mic,
                maxMinutes,
            ),
        )
        .setWhen(startedAtWallClock)
        .setShowWhen(true)
        .setUsesChronometer(true)
        .addAction(0, context.getString(R.string.action_stop), stopIntent())
        .build()

    fun saved(displayName: String, folder: String, uri: Uri?, note: String?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_mode)
            .setContentTitle(context.getString(R.string.memo_notif_saved))
            .setContentText(note ?: "$folder/$displayName")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(note, "$folder/$displayName").joinToString("\n")))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (view.resolveActivity(context.packageManager) != null) {
                builder.setContentIntent(PendingIntent.getActivity(context, 0, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }
        return builder.build()
    }

    fun notifySaved(notification: Notification) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Missing POST_NOTIFICATIONS on 13+ just means it isn't shown; the spoken "Saved" still is.
        runCatching { manager.notify(SAVED_ID, notification) }
    }

    private fun base(): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_tile_mode)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    private fun stopIntent(): PendingIntent {
        val intent = Intent(context, VoiceMemoService::class.java).setAction(VoiceMemoService.ACTION_STOP)
        return PendingIntent.getService(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_ID = "voice_memo"
        const val RECORDING_ID = 0x3e30
        const val SAVED_ID = 0x3e31
    }
}

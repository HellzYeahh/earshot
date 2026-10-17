package dev.earshot.extras.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.earshot.MainActivity
import dev.earshot.R
import dev.earshot.core.AutomationRepository
import dev.earshot.core.HeadphoneRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Posts one "battery low" notification per discharge cycle (decided by [LowBatteryTracker]).
 *
 * Needs `POST_NOTIFICATIONS` on Android 13+, which is a runtime permission the UI must request;
 * until it is granted we silently skip (the tracker still advances so we do not pile up a
 * notification the moment permission arrives mid-cycle).
 */
class LowBatteryAlert(
    context: Context,
    private val scope: CoroutineScope,
    private val headphones: HeadphoneRepository,
    private val automation: AutomationRepository,
) {
    private val appContext = context.applicationContext
    private val tracker = LowBatteryTracker()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(
                headphones.state.map { Triple(it.isConnected, it.batteryPercent, it.isCharging) }.distinctUntilChanged(),
                automation.preferences.map { it.lowBatteryThreshold }.distinctUntilChanged(),
            ) { (connected, percent, charging), threshold -> Sample(connected, percent, charging, threshold) }
                .distinctUntilChanged()
                .collect { s ->
                    // Disconnected readings are stale; neither notify nor reset on them.
                    if (!s.connected) return@collect
                    if (tracker.onSample(s.percent, s.charging, s.threshold)) notify(s.percent ?: return@collect)
                }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private fun notify(percent: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Low battery ($percent%) but POST_NOTIFICATIONS not granted")
            return
        }
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Headphone battery warnings"
            },
        )
        val open = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val name = headphones.state.value.connection.deviceName ?: "Headphones"
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_mode)
            .setContentTitle("$name battery low")
            .setContentText("$percent% left. Time to charge.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed", it) }
    }

    private data class Sample(val connected: Boolean, val percent: Int?, val charging: Boolean?, val threshold: Int?)

    companion object {
        const val CHANNEL_ID = "alerts"
        const val NOTIFICATION_ID = 0x1ba7
        private const val TAG = "Earshot/LowBattery"
    }
}

package dev.earshot.extras.automation

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls `UsageStatsManager` for the package most recently brought to the foreground and calls
 * [onChange] when it differs from the previous poll (including the very first observation, so
 * putting the headphones on while a video app is open still applies its rule).
 *
 * Why polling: there is no broadcast for "foreground app changed" available to normal apps;
 * `UsageStatsManager.queryEvents` is the sanctioned source and needs the *Usage Access*
 * special permission (`PACKAGE_USAGE_STATS`, granted by the user in Settings). The engine
 * only runs this while the headphones are connected and a ForegroundApp rule exists, because
 * a 3-second poll is the single most battery-relevant thing in the app.
 */
class ForegroundAppWatcher(
    context: Context,
    private val hasUsageAccess: () -> Boolean,
    private val onChange: (packageName: String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val usage = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private var job: Job? = null
    @Volatile private var lastPackage: String? = null

    val currentPackage: String? get() = lastPackage

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        lastPackage = null
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (!hasUsageAccess()) {
                    // Nothing to read until the user grants Usage Access; back off hard.
                    delay(NO_ACCESS_BACKOFF_MILLIS)
                    continue
                }
                poll()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastPackage = null
    }

    private fun poll() {
        val manager = usage ?: return
        val now = System.currentTimeMillis()
        val latest = runCatching { latestForeground(manager, now - LOOKBACK_MILLIS, now) }
            .onFailure { Log.w(TAG, "usage query failed", it) }
            .getOrNull() ?: return // no event in the window: keep the previous answer
        if (latest == appContext.packageName) return // Earshot itself is never a trigger
        if (latest != lastPackage) {
            lastPackage = latest
            onChange(latest)
        }
    }

    private fun latestForeground(manager: UsageStatsManager, from: Long, to: Long): String? {
        val events = manager.queryEvents(from, to) ?: return null
        val event = UsageEvents.Event()
        var result: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == FOREGROUND_EVENT) result = event.packageName
        }
        return result
    }

    private companion object {
        const val TAG = "Earshot/ForegroundApp"
        const val POLL_INTERVAL_MILLIS = 3_000L
        const val LOOKBACK_MILLIS = 10_000L
        const val NO_ACCESS_BACKOFF_MILLIS = 15_000L

        /** `ACTIVITY_RESUMED` (API 29) has the same value as the older `MOVE_TO_FOREGROUND`. */
        @Suppress("DEPRECATION")
        val FOREGROUND_EVENT: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) UsageEvents.Event.ACTIVITY_RESUMED
            else UsageEvents.Event.MOVE_TO_FOREGROUND
    }
}

package dev.earshot.extras.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.earshot.core.AutomationRule
import dev.earshot.core.Trigger
import java.time.LocalDateTime

/**
 * Arms one `AlarmManager` alarm per enabled [Trigger.Schedule] rule, delivered to
 * [ScheduleReceiver].
 *
 * Why inexact: `setAndAllowWhileIdle` needs no permission. Exact alarms require
 * `SCHEDULE_EXACT_ALARM` (Android 12+), which Android 13 turns into a user-revocable special
 * access and Play policy restricts to alarm-clock apps. A listening-mode switch being a minute
 * late inside Doze is acceptable; the alarm still fires in idle mode (that is what the
 * `AllowWhileIdle` variant buys us, at most once per ~9 minutes per app).
 */
class ScheduleAlarms(context: Context) {
    private val appContext = context.applicationContext
    private val alarms = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val armed = HashSet<String>()

    /** Arm every enabled schedule in [rules] and cancel alarms for rules that are gone or disabled. */
    @Synchronized
    fun sync(rules: List<AutomationRule>, now: LocalDateTime = LocalDateTime.now()) {
        val wanted = rules.filter { it.enabled && it.trigger is Trigger.Schedule }
        val wantedIds = wanted.map { it.id }.toSet()
        (armed - wantedIds).toList().forEach { cancel(it) }
        wanted.forEach { arm(it, now) }
    }

    /** Re-arm a single rule after it fired (from [now], so the same minute is skipped). */
    @Synchronized
    fun arm(rule: AutomationRule, now: LocalDateTime = LocalDateTime.now()) {
        val schedule = rule.trigger as? Trigger.Schedule ?: return
        val manager = alarms ?: return
        val at = SchedulePlanner.nextEpochMillis(schedule, now)
        if (at == null || !rule.enabled) { cancel(rule.id); return }
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(rule.id))
            armed += rule.id
        }.onFailure { Log.w(TAG, "arm failed for ${rule.id}", it) }
    }

    @Synchronized
    fun cancel(ruleId: String) {
        runCatching { alarms?.cancel(pendingIntent(ruleId)) }
        armed -= ruleId
    }

    @Synchronized
    fun cancelAll() = armed.toList().forEach { cancel(it) }

    private fun pendingIntent(ruleId: String): PendingIntent {
        val intent = Intent(appContext, ScheduleReceiver::class.java)
            .setAction(ScheduleReceiver.ACTION_FIRE)
            .putExtra(ScheduleReceiver.EXTRA_RULE_ID, ruleId)
        // Request code must differ per rule or the alarms replace each other.
        return PendingIntent.getBroadcast(
            appContext, ruleId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object { const val TAG = "Earshot/Schedule" }
}

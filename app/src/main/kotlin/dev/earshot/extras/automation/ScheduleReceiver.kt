package dev.earshot.extras.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.earshot.appContainer
import dev.earshot.extras.ExtrasInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Target of the schedule alarms (declared in the manifest, `exported=false`).
 *
 * A receiver gets ~10 s before the system may kill the process, so the work is handed to the
 * engine under `goAsync()` with a timeout: run the rule if the headphones are connected, then
 * re-arm the next occurrence either way. Booting the engine here also covers the case where
 * the alarm wakes a dead process (the initializer is idempotent).
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return
        val engine = runCatching { ExtrasInitializer.start(context, context.appContainer).engine }
            .onFailure { Log.w(TAG, "No engine for scheduled rule $ruleId", it) }
            .getOrNull() ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MILLIS) { engine.onScheduleFired(ruleId) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.earshot.extras.automation.SCHEDULE_FIRE"
        const val EXTRA_RULE_ID = "rule_id"
        private const val RECEIVER_BUDGET_MILLIS = 8_000L
        private const val TAG = "Earshot/Schedule"
    }
}

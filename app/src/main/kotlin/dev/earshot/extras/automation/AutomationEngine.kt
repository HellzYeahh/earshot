package dev.earshot.extras.automation

import android.content.Context
import android.util.Log
import dev.earshot.appContainer
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.AutomationRepository
import dev.earshot.core.AutomationRule
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.Trigger
import dev.earshot.extras.tile.ModeTileService
import dev.earshot.extras.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Watches the triggers and runs the matching rules. Everything except the schedule alarms is
 * active only while the headphones are connected: there is nothing to switch otherwise, and
 * the Wi-Fi callback / usage-stats poller are the parts that cost battery.
 *
 * Permissions the UI must obtain before the corresponding trigger can work:
 *  - `ACCESS_FINE_LOCATION` (runtime) for [Trigger.WifiNetwork]; see [WifiSsidWatcher].
 *  - Usage Access (Settings, via `AutomationRepository.openUsageAccessSettings`) for [Trigger.ForegroundApp].
 */
class AutomationEngine(
    context: Context,
    scope: CoroutineScope,
    private val headphones: HeadphoneRepository,
    tuning: AudioTuningRepository,
    private val automation: AutomationRepository,
) {
    private val appContext = context.applicationContext
    private val scope = scope + SupervisorJob(scope.coroutineContext[Job])
    private val runner = RuleRunner(headphones, tuning, automation)
    private val alarms = ScheduleAlarms(appContext)

    private val wifi = WifiSsidWatcher(appContext) { ssid -> scope.launch { onSsid(ssid) } }
    private val foreground = ForegroundAppWatcher(appContext, automation::hasUsageAccess) { pkg -> scope.launch { onForegroundApp(pkg) } }

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true

        // OnConnect: wait for the first full refresh so a SetMode does not race the greeting burst.
        scope.launch {
            headphones.events.collect { event ->
                if (event is HeadphoneEvent.Connected) {
                    delay(CONNECT_SETTLE_MILLIS)
                    if (headphones.state.value.isConnected) fireAll { it is Trigger.OnConnect }
                }
            }
        }

        // Wi-Fi watcher lives only while the headset is in use (control link, or linked for audio:
        // an EQ rule works on any headset, a mode rule fails quietly without the control link).
        scope.launch {
            headphones.state.map { it.isAudioActive }.distinctUntilChanged().collect { connected ->
                if (connected) wifi.start() else wifi.stop()
            }
        }

        // Usage-stats poller: connected AND at least one enabled ForegroundApp rule. Usage Access
        // itself is re-checked inside the watcher so granting it later needs no restart.
        scope.launch {
            combine(
                headphones.state.map { it.isAudioActive }.distinctUntilChanged(),
                automation.rules.map { rules -> rules.any { it.enabled && it.trigger is Trigger.ForegroundApp } }.distinctUntilChanged(),
            ) { connected, wanted -> connected && wanted }
                .distinctUntilChanged()
                .collect { active -> if (active) foreground.start(scope) else foreground.stop() }
        }

        // Alarms follow the rule list regardless of connection; the receiver checks connection at fire time.
        scope.launch { automation.rules.collect { alarms.sync(it) } }

        // 0.75 saved bare slot numbers; bind them to meanings on the first real headset that shows its modes.
        ModeRefMigration(headphones, automation, appContext.appContainer.demoMode.isEnabled).start(scope)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        wifi.stop()
        foreground.stop()
        scope.coroutineContext.cancelChildren()
    }

    /** Re-arm every schedule; for the boot receiver (alarms do not survive a reboot). */
    fun rearmAll() = alarms.sync(automation.rules.value)

    /** Called by [ScheduleReceiver]; runs the rule if connected and always re-arms it. */
    suspend fun onScheduleFired(ruleId: String) {
        val rule = automation.rules.value.firstOrNull { it.id == ruleId }
        if (rule == null) { alarms.cancel(ruleId); return }
        try {
            if (rule.enabled && headphones.state.value.isAudioActive) runAndRefresh(rule)
            else Log.i(TAG, "Schedule $ruleId skipped (disabled or disconnected)")
        } finally {
            alarms.arm(rule)
        }
    }

    private suspend fun onSsid(ssid: String?) {
        if (ssid == null || !headphones.state.value.isAudioActive) return
        fireAll { it is Trigger.WifiNetwork && Ssid.matches(it.ssid, ssid) }
    }

    private suspend fun onForegroundApp(packageName: String) {
        if (!headphones.state.value.isAudioActive) return
        fireAll { it is Trigger.ForegroundApp && it.packageName == packageName }
    }

    private suspend fun fireAll(matches: (Trigger) -> Boolean) {
        automation.rules.value
            .filter { it.enabled && matches(it.trigger) }
            .forEach { runAndRefresh(it) }
    }

    private suspend fun runAndRefresh(rule: AutomationRule) {
        if (runner.run(rule)) {
            // The state just changed because of us: push it to the surfaces right away.
            WidgetUpdater.update(appContext)
            ModeTileService.requestRefresh(appContext)
        }
    }

    private companion object {
        const val TAG = "Earshot/Automation"
        const val CONNECT_SETTLE_MILLIS = 2_000L
    }
}

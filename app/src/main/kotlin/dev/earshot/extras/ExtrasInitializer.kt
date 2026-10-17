package dev.earshot.extras

import android.content.Context
import dev.earshot.AppContainer
import dev.earshot.extras.alerts.LowBatteryAlert
import dev.earshot.extras.automation.AutomationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * One-call bootstrap for everything in `extras/`: the automation engine, the low-battery
 * alert and the widget/tile refresher. Idempotent and process-wide, so the tile service,
 * widget receiver and schedule receiver can all call it defensively when they are the first
 * component to wake the process.
 *
 * Integration: `ExtrasInitializer.start(this, container)` from `EarshotApp.onCreate()` (or
 * lazily from `AppContainer`).
 */
object ExtrasInitializer {
    class Extras internal constructor(
        val engine: AutomationEngine,
        val lowBatteryAlert: LowBatteryAlert,
        val refresher: SurfaceRefresher,
    )

    @Volatile private var extras: Extras? = null

    fun start(context: Context, container: AppContainer): Extras {
        extras?.let { return it }
        synchronized(this) {
            extras?.let { return it }
            val appContext = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val engine = AutomationEngine(appContext, scope, container.headphones, container.tuning, container.automation)
            val alert = LowBatteryAlert(appContext, scope, container.headphones, container.automation)
            val refresher = SurfaceRefresher(appContext, scope, container.headphones, container.tuning)
            engine.start()
            alert.start()
            refresher.start()
            return Extras(engine, alert, refresher).also { extras = it }
        }
    }

    /** The running instance, or null before [start]. */
    fun current(): Extras? = extras
}

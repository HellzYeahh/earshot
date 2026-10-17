package dev.earshot.extras

import android.content.Context
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneRepository
import dev.earshot.extras.tile.ModeTileService
import dev.earshot.extras.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the widget and the Quick Settings tile in step with repository state, throttled to one
 * redraw per [THROTTLE_MILLIS]. `conflate` + `delay` gives a leading-edge update plus the
 * latest trailing value, which is what a battery/mode display wants; a full `RemoteViews`
 * push per state tick would be wasteful (the fake repository alone ticks on every command).
 */
class SurfaceRefresher(
    context: Context,
    private val scope: CoroutineScope,
    private val headphones: HeadphoneRepository,
    private val tuning: AudioTuningRepository,
) {
    private val appContext = context.applicationContext
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(
                headphones.state.map { Key(it.isConnected, it.connection.status, it.batteryPercent, it.isCharging, it.currentModeIndex, it.modes.size, it.isBusy) },
                tuning.state.map { it.activePreset?.id },
            ) { a, b -> a to b }
                .distinctUntilChanged()
                .drop(1) // the initial value is what the surfaces already show
                .conflate()
                .collect {
                    WidgetUpdater.update(appContext)
                    ModeTileService.requestRefresh(appContext)
                    delay(THROTTLE_MILLIS)
                }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private data class Key(
        val connected: Boolean, val status: ConnectionStatus, val battery: Int?,
        val charging: Boolean?, val mode: Int?, val modeCount: Int, val busy: Boolean,
    )

    private companion object { const val THROTTLE_MILLIS = 2_000L }
}

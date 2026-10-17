package dev.earshot.extras.tile

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dev.earshot.appContainer
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.extras.ExtrasInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: shows connection + current mode, tap cycles modes (or connects).
 * Long-press opens the app (system default for a tile whose service has a launcher activity).
 *
 * Declared as an *active* tile in the manifest, so the system only binds us while the panel
 * is open or when [requestRefresh] asks; between those windows the tile keeps the last state
 * we wrote. `startActivityAndCollapse` is deliberately not used: a tap should never leave the
 * shade.
 */
class ModeTileService : TileService() {
    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val container = applicationContext.appContainer
        ExtrasInitializer.start(applicationContext, container) // in case the tile is the first thing to wake the process
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { listening = it }
        scope.launch {
            combine(container.headphones.state, container.automation.preferences) { s, p -> s to p.tileCycleModes }
                .collect { (state, cycle) -> render(state, cycle) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val container = applicationContext.appContainer
        val headphones = container.headphones
        val state = headphones.state.value
        if (!state.isConnected) {
            if (state.connection.status != ConnectionStatus.CONNECTING) headphones.connect()
            return
        }
        val order = TileCycle.order(container.automation.preferences.value.tileCycleModes, state.modes)
        val next = TileCycle.next(state.currentModeIndex, order) ?: return
        // The listening scope dies with the shade; use a detached one so the command completes.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { headphones.setMode(next) }
                .onFailure { Log.w(TAG, "Tile setMode($next) failed: ${it.message}") }
        }
    }

    private fun render(state: HeadphoneState, cycle: List<Int>) {
        val tile = qsTile ?: return
        val connected = state.isConnected
        val label: String
        val subtitle: String?
        when {
            connected -> {
                label = state.currentMode?.name?.takeIf { it.isNotBlank() } ?: "Headphones"
                subtitle = state.batteryPercent?.let { "$it%" } ?: state.connection.deviceName
            }
            state.connection.status == ConnectionStatus.CONNECTING -> { label = "Connecting…"; subtitle = null }
            state.connection.status == ConnectionStatus.NO_PERMISSION -> { label = "Connect"; subtitle = "Open Earshot" }
            else -> { label = "Connect"; subtitle = state.connection.deviceName }
        }
        tile.state = when {
            connected -> Tile.STATE_ACTIVE
            state.connection.status == ConnectionStatus.NO_PAIRED_DEVICE -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.contentDescription = buildString {
            append(label); subtitle?.let { append(", ").append(it) }
            if (connected && cycle.size > 1) append(". Tap to switch mode")
        }
        runCatching { tile.updateTile() }
    }

    companion object {
        private const val TAG = "Earshot/Tile"

        /** Ask the system to bind the tile so [onStartListening] re-renders it with fresh state. */
        fun requestRefresh(context: Context) {
            runCatching {
                requestListeningState(context.applicationContext, ComponentName(context.applicationContext, ModeTileService::class.java))
            }
        }
    }
}

package dev.earshot.extras.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dev.earshot.MainActivity
import dev.earshot.appContainer
import dev.earshot.core.AppPreferences
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.extras.ExtrasInitializer
import dev.earshot.extras.HeadphoneLabels
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
 * we wrote. A mode switch never leaves the shade; only on a model without listening modes, where
 * there is nothing to cycle, does a tap open the app (`startActivityAndCollapse`).
 */
class ModeTileService : TileService() {
    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val container = applicationContext.appContainer
        ExtrasInitializer.start(applicationContext, container) // in case the tile is the first thing to wake the process
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { listening = it }
        scope.launch {
            combine(container.headphones.state, container.automation.preferences) { s, p -> s to p }
                .collect { (state, prefs) -> render(state, cycleOrder(prefs, state)) }
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
        // A model without listening modes (NC 700, basic-tier firmware) has nothing to cycle:
        // cycling the QC Ultra's preset slots there would only send commands that fail. Open the
        // app instead, where its controls are.
        if (!state.capabilities.listeningModes) {
            openApp()
            return
        }
        val order = cycleOrder(container.automation.preferences.value, state)
        val next = TileCycle.next(state.currentModeIndex, order) ?: return
        // The listening scope dies with the shade; use a detached one so the command completes.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { headphones.setMode(next) }
                .onFailure { Log.w(TAG, "Tile setMode($next) failed: ${it.message}") }
        }
    }

    /**
     * Opens Earshot from a tap (only used for models without modes). Android 14 takes a
     * PendingIntent here; older versions the deprecated Intent overload.
     */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }.onFailure { Log.w(TAG, "Tile could not open the app: ${it.message}") }
    }

    /** The slots a tap cycles through: only ones with a real name, so the tile never lands on "Mode 5". */
    private fun cycleOrder(prefs: AppPreferences, state: HeadphoneState): List<Int> =
        TileCycle.order(prefs, state).filter { HeadphoneLabels.modeName(state, it) != null }

    private fun render(state: HeadphoneState, cycle: List<Int>) {
        val tile = qsTile ?: return
        val connected = state.isConnected
        val name = HeadphoneLabels.displayName(state)
        val buds = HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(state))
        val label: String
        val subtitle: String?
        when {
            connected -> {
                label = state.currentMode?.name?.takeIf { it.isNotBlank() }
                    ?: name ?: if (buds) "Earbuds" else "Headphones"
                subtitle = HeadphoneLabels.batteryShort(state) ?: name
            }
            state.connection.status == ConnectionStatus.CONNECTING -> { label = "Connecting…"; subtitle = null }
            state.connection.status == ConnectionStatus.NO_PERMISSION -> { label = "Connect"; subtitle = "Open Earshot" }
            else -> { label = "Connect"; subtitle = name }
        }
        // From the resolved order, not the saved list: with nothing chosen the favourites or the
        // first modes still cycle, and a choice made on another headset may resolve to nothing here.
        val canCycle = connected && state.capabilities.listeningModes && cycle.any { it != state.currentModeIndex }
        tile.state = when {
            connected -> Tile.STATE_ACTIVE
            state.connection.status == ConnectionStatus.NO_PAIRED_DEVICE -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.contentDescription = buildString {
            append(label); subtitle?.let { append(", ").append(it) }
            if (canCycle) append(". Tap to switch mode")
            if (connected && !state.capabilities.listeningModes) append(". Tap to open Earshot")
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

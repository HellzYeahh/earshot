package dev.earshot.extras.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import dev.earshot.appContainer
import dev.earshot.core.ModeResolver
import dev.earshot.extras.ExtrasInitializer

/**
 * Widget button callbacks. Glance runs these in a short-lived worker, so each one does its
 * command and then forces a redraw rather than waiting for the engine's throttled refresh.
 */
class SetModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val index = parameters[MODE_INDEX] ?: return
        val container = context.appContainer
        ExtrasInitializer.start(context, container)
        // The slot belongs to the headset the widget was drawn for; a button left over from another
        // one would switch this headset to whatever its own slot is.
        val now = container.headphones.state.value.connection.deviceAddress
        if (!ModeResolver.slotStillApplies(parameters[ADDRESS], now)) {
            Log.i(TAG, "Widget button for another headset ignored")
        } else {
            runCatching { container.headphones.setMode(index) }
                .onFailure { Log.w(TAG, "Widget setMode($index) failed: ${it.message}") }
        }
        WidgetUpdater.update(context)
    }

    companion object {
        val MODE_INDEX = ActionParameters.Key<Int>("mode_index")
        /** The headset whose slot [MODE_INDEX] is; absent on buttons drawn by an older build. */
        val ADDRESS = ActionParameters.Key<String>("mode_address")
        private const val TAG = "Earshot/Widget"

        fun parameters(index: Int, address: String?): ActionParameters =
            if (address == null) actionParametersOf(MODE_INDEX to index)
            else actionParametersOf(MODE_INDEX to index, ADDRESS to address)
    }
}

class ConnectAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = context.appContainer
        ExtrasInitializer.start(context, container)
        container.headphones.connect()
        WidgetUpdater.update(context)
    }
}

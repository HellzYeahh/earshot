package dev.earshot.extras.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import dev.earshot.appContainer
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
        runCatching { container.headphones.setMode(index) }
            .onFailure { Log.w(TAG, "Widget setMode($index) failed: ${it.message}") }
        WidgetUpdater.update(context)
    }

    companion object {
        val MODE_INDEX = ActionParameters.Key<Int>("mode_index")
        private const val TAG = "Earshot/Widget"
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

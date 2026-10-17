package dev.earshot.extras.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll

/** Redraws every placed Earshot widget from the current repository state. Safe when none is placed. */
object WidgetUpdater {
    suspend fun update(context: Context) {
        runCatching { EarshotWidget().updateAll(context.applicationContext) }
            .onFailure { Log.w("Earshot/Widget", "updateAll failed", it) }
    }
}

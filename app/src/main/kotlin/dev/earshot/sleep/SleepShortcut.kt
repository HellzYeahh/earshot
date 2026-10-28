package dev.earshot.sleep

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.earshot.MainActivity
import dev.earshot.R

/**
 * "Start sleep" in the launcher's long-press menu on Earshot's icon (docs/sleep-mode.md §6, quick
 * start): opens the app on the Sleep tab and starts tonight's plan. It goes through
 * [MainActivity] rather than straight to the service: Android 17 lets audio start only from a
 * visible app (or a notification / tile tap).
 */
object SleepShortcut {
    const val ACTION_START = "dev.earshot.action.START_SLEEP"
    private const val ID = "sleep_start"

    /** Idempotent; called at process start. */
    fun publish(context: Context) {
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION_START)
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(context.getString(R.string.sleep_rt_shortcut_short))
            .setLongLabel(context.getString(R.string.sleep_rt_shortcut_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_sleep_shortcut))
            .setIntent(intent)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }
}

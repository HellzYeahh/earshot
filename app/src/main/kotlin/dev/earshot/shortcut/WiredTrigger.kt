package dev.earshot.shortcut

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Wired and USB-C headsets: a long press of play/pause sends
 * `android.speech.action.VOICE_SEARCH_HANDS_FREE` while the phone is locked or the screen is off
 * (unlocked it opens search / the assist app instead, which Earshot leaves alone). The manifest's
 * activity-alias for it ships disabled; the experimental switch on the Shortcuts remapper screen
 * enables it, so Earshot never shows up in that chooser unless asked to.
 */
object WiredTrigger {
    const val ACTION_VOICE_SEARCH_HANDS_FREE = "android.speech.action.VOICE_SEARCH_HANDS_FREE"
    private const val ALIAS = "dev.earshot.shortcut.WiredHeadsetTrigger"

    private fun component(context: Context) = ComponentName(context.packageName, ALIAS)

    fun isEnabled(context: Context): Boolean =
        runCatching { context.packageManager.getComponentEnabledSetting(component(context)) }
            .getOrNull() == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
    }
}

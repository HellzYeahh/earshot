package dev.earshot.shortcut

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The headset gesture's entry: the manifest's `VOICE_COMMAND` activity-alias for
 * [ShortcutTriggerActivity], which ships disabled. A new `VOICE_COMMAND` handler makes Android drop
 * the user's "Always" choice and show its chooser on the next press of *any* headset, also for
 * people who never open Controls (and a chooser on a locked phone lets the voice request time
 * out). So the alias is on only while something needs it ([needed]); [ShortcutEngine] keeps it in
 * step with the settings and "Detect a button".
 */
object HeadsetTrigger {
    private const val ALIAS = "dev.earshot.shortcut.HeadsetTrigger"

    /**
     * An Earshot action other than the factory "Phone assistant" is chosen (as the default or for
     * a headset), or "Detect a button" is listening. Headphone modes need no trigger: the Bose
     * button then sends no voice request.
     */
    fun needed(settings: ShortcutSettings, detecting: Boolean): Boolean =
        detecting || settings.defaultAction.needsTrigger() || settings.overrides.any { it.action.needsTrigger() }

    private fun ShortcutAction.needsTrigger(): Boolean = isEarshotAction && this != ShortcutSettings.FACTORY_DEFAULT

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

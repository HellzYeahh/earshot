package dev.earshot.call

/**
 * Android's "Disable absolute volume" switch, read and written through the Shizuku shell.
 *
 * Two places can hold it, and which one a build honours varies, so both are read and written:
 *  - `settings global bluetooth_disable_absolute_volume` (1/0), the key several OEM builds use;
 *  - the system property `persist.bluetooth.disableabsvol` (true/false), which AOSP's Developer
 *    options toggle sets. The shell user often may not set `persist.bluetooth.*`, so that write is
 *    best effort.
 * Either way the Bluetooth stack only looks at it when a headset connects, hence "reconnect to apply".
 */
object AbsoluteVolumeSetting {
    const val GLOBAL_KEY = "bluetooth_disable_absolute_volume"
    const val PROPERTY = "persist.bluetooth.disableabsvol"
    private const val SEPARATOR = "--earshot--"
    const val PROP_OK = "prop-ok"
    const val PROP_SKIPPED = "prop-skipped"

    /** One shell round trip for both sources. */
    const val READ_COMMAND: String = "settings get global $GLOBAL_KEY; echo $SEPARATOR; getprop $PROPERTY"

    fun writeCommand(disabled: Boolean): String {
        val global = if (disabled) "1" else "0"
        return "settings put global $GLOBAL_KEY $global && " +
            "{ setprop $PROPERTY $disabled 2>/dev/null && echo $PROP_OK || echo $PROP_SKIPPED; }"
    }

    /**
     * Parses [READ_COMMAND]'s output. The global setting wins when it is set; an unset setting
     * ("null") falls back to the property; neither set means absolute volume is on (not disabled).
     */
    fun parse(output: String): Boolean {
        val parts = output.split(SEPARATOR)
        val global = parts.getOrNull(0)?.trim()?.lowercase()
        val prop = parts.getOrNull(1)?.trim()?.lowercase()
        parseFlag(global)?.let { return it }
        parseFlag(prop)?.let { return it }
        return false
    }

    private fun parseFlag(v: String?): Boolean? = when (v) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null // "null", "", anything odd
    }
}

package dev.earshot.call

/**
 * Android constants mapped to call concepts. Values are the public SDK constants, written out so
 * this stays testable on the JVM and so the reason for each choice sits next to it.
 */
object CallSignals {
    // AudioManager modes.
    private const val MODE_IN_CALL = 2          // telephony call (also VoLTE / Wi-Fi calling)
    private const val MODE_IN_COMMUNICATION = 3 // VoIP: WhatsApp, Meet, Teams, Signal ...
    // MODE_CALL_SCREENING (4), MODE_CALL_REDIRECT (5) and MODE_COMMUNICATION_REDIRECT (6) are not
    // calls the user hears in the headphones, so they don't count.

    // AudioDeviceInfo types.
    private const val TYPE_BLUETOOTH_SCO = 7
    private const val TYPE_BLE_HEADSET = 26 // API 31

    fun isCallMode(mode: Int): Boolean = mode == MODE_IN_CALL || mode == MODE_IN_COMMUNICATION

    fun routeFor(deviceType: Int?): CallRoute = when (deviceType) {
        null -> CallRoute.UNKNOWN
        TYPE_BLUETOOTH_SCO -> CallRoute.BLUETOOTH_SCO
        TYPE_BLE_HEADSET -> CallRoute.LE_AUDIO
        else -> CallRoute.OTHER
    }

    fun isHeadsetRoute(route: CallRoute): Boolean = route == CallRoute.BLUETOOTH_SCO || route == CallRoute.LE_AUDIO

    /** Address match tolerant of case and of a missing side (unknown counts as "same"). */
    fun sameAddress(a: String?, b: String?): Boolean = a.isNullOrBlank() || b.isNullOrBlank() || a.equals(b, ignoreCase = true)
}

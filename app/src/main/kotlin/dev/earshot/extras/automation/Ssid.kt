package dev.earshot.extras.automation

/**
 * SSID normalisation. `WifiInfo.getSSID()` returns the name wrapped in double quotes when it
 * is printable UTF-8, a bare hex string otherwise, and the literal `<unknown ssid>` when the
 * app lacks location permission (or location services are off, or the app is in the
 * background without background location). Rules store what the user typed.
 */
object Ssid {
    const val UNKNOWN = "<unknown ssid>"

    /** Null when the platform is hiding the name from us or there is no Wi-Fi. */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        var s = raw.trim()
        if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s = s.substring(1, s.length - 1)
        if (s.isEmpty() || s == UNKNOWN || s == "0x") return null
        return s
    }

    /** User-typed rule SSID vs. what the OS reports; quotes/whitespace tolerant, case-insensitive. */
    fun matches(ruleSsid: String, currentSsid: String?): Boolean {
        val a = normalize(ruleSsid) ?: return false
        val b = normalize(currentSsid) ?: return false
        return a.equals(b, ignoreCase = true)
    }
}

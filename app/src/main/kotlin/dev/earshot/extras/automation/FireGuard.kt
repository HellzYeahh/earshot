package dev.earshot.extras.automation

/**
 * Suppresses re-firing the same rule within [windowMillis]. Triggers are noisy: a Wi-Fi
 * callback can report the same SSID several times while capabilities settle, the usage-stats
 * poller may see the same app twice, and a late alarm re-arm can overlap. Pure, so testable.
 */
class FireGuard(private val windowMillis: Long = 5_000L) {
    private val lastFired = HashMap<String, Long>()

    /** True if [ruleId] may fire at [nowMillis]; records the firing when it may. */
    @Synchronized
    fun shouldFire(ruleId: String, nowMillis: Long): Boolean {
        val last = lastFired[ruleId]
        if (last != null && nowMillis - last < windowMillis) return false
        lastFired[ruleId] = nowMillis
        return true
    }

    @Synchronized
    fun reset() = lastFired.clear()
}

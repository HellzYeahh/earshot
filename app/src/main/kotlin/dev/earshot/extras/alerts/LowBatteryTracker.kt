package dev.earshot.extras.alerts

/**
 * Once-per-discharge-cycle decision for the low-battery alert. Pure so it is testable.
 *
 * Notifies when the level first drops to or below the threshold; re-arms only when the level
 * climbs back above `threshold + [hysteresis]` or the headphones start charging. The
 * hysteresis stops a reading that hovers around the threshold from re-notifying.
 */
class LowBatteryTracker(private val hysteresis: Int = 5) {
    var notified: Boolean = false
        private set

    /** Returns true exactly when a notification should be shown for this sample. */
    fun onSample(percent: Int?, charging: Boolean?, threshold: Int?): Boolean {
        if (threshold == null) { notified = false; return false }
        if (charging == true) { notified = false; return false }
        if (percent == null) return false
        if (percent > threshold + hysteresis) { notified = false; return false }
        if (percent <= threshold && !notified) { notified = true; return true }
        return false
    }
}

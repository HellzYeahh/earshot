package dev.earshot.core

/**
 * How far Earshot's support for the connected headset goes, the one label every brand shares
 * (1.0 plan item 4 "Honest support", roadmap 0.9 B3). Derived from [HeadphoneState] alone, so no
 * screen needs to know which driver is behind it:
 *  - no driver ([HeadphoneCapabilities.ownSettings] false): [BATTERY_ONLY] when Earshot still reads
 *    its battery itself ([HeadphoneCapabilities.batteryOnly], AirPods and Beats), else [ANY_HEADSET];
 *  - a driver that only reads the battery (QuietComfort 35): [BATTERY_ONLY];
 *  - a driver whose model Earshot has run on for real ([HeadphoneCapabilities.tested]): [TESTED];
 *  - every other driver: [EXPERIMENTAL]. Owners' reports move a model from there to Tested.
 */
enum class SupportTier {
    /** Earshot has run on this model's real hardware. */
    TESTED,

    /** A driver exists, but Earshot hasn't run on this model's real hardware yet. */
    EXPERIMENTAL,

    /** The battery is read; none of the headset's own settings can be changed. */
    BATTERY_ONLY,

    /**
     * No driver: only what works through Android for any headset. Earshot changes nothing on it
     * (the standard battery read and the universal remote's connect and disconnect go through Android).
     */
    ANY_HEADSET,
    ;

    /** An owner's report is what this tier needs (the report loop, roadmap 0.9 B8). */
    val wantsReports: Boolean get() = this == EXPERIMENTAL

    companion object {
        /**
         * The connected headset's tier; null while there is none to show: not connected, or a
         * driver that hasn't read the model yet. Drivers publish the model's info and capabilities
         * together; before that the capabilities are the QC Ultra 2 defaults and say nothing about
         * this headset. Every driver publishes at least an identity once its first read is over,
         * even a failed one, so a headset whose reads keep failing still gets its tier (and an
         * Experimental one its "Send a report"). A headset without a driver never gets info, and needs none.
         */
        fun of(state: HeadphoneState): SupportTier? {
            if (!state.isConnected) return null
            val caps = state.capabilities
            if (caps.ownSettings && state.info == null) return null
            return of(caps)
        }

        /** The tier these capabilities describe, whatever the connection does. */
        fun of(caps: HeadphoneCapabilities): SupportTier = when {
            !caps.ownSettings -> if (caps.batteryOnly) BATTERY_ONLY else ANY_HEADSET
            caps.batteryOnly -> BATTERY_ONLY
            caps.tested -> TESTED
            else -> EXPERIMENTAL
        }
    }
}

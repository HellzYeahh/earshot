package dev.earshot.distribution

/**
 * The GitHub / F-Droid / IzzyOnDroid build (flavour `foss`, app ID `dev.earshot.github`; the
 * one-time 0.9.1 bridge is `dev.earshot`): everything free, no store code.
 */
object Distribution {
    /** Where this build comes from, for About and the connection report. */
    const val NAME = "GitHub"

    /** True only in the Google Play build, the one place store-only features may live. */
    const val IS_PLAY = false

    /**
     * The older Earshot this build replaces but can't update in place: builds up to 0.9.0 (and
     * the 0.9.1 bridge) were `dev.earshot` signed with a debug key (docs/release-signing.md,
     * "Moving existing users").
     */
    val LEGACY_PACKAGE: String? = "dev.earshot"

    /**
     * The last debug-signed `dev.earshot` build: 0.9.1, the bridge that adds Back up and restore.
     * Raise it only if another debug-signed bridge release ships.
     */
    const val LEGACY_MAX_VERSION_CODE: Long = 11

    /** The other current edition: the Google Play build. Only one Earshot should drive a headset. */
    const val OTHER_EDITION_PACKAGE = "dev.earshot.foss"

    /** How the other edition is named in "Earshot from … is also on this phone". */
    const val OTHER_EDITION_NAME = "Google Play"
}

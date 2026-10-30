package dev.earshot.distribution

/** The GitHub / F-Droid / IzzyOnDroid build (flavour `foss`): everything free, no store code. */
object Distribution {
    /** Where this build comes from, for About and the connection report. */
    const val NAME = "GitHub"

    /** True only in the Google Play build, the one place store-only features may live. */
    const val IS_PLAY = false

    /**
     * The older Earshot this build replaces but can't update in place: builds up to 0.9.0 were
     * `dev.earshot` signed with a debug key (docs/release-signing.md, "Moving existing users").
     */
    val LEGACY_PACKAGE: String? = "dev.earshot"

    /**
     * The last debug-signed `dev.earshot` build: 0.9.1, the bridge that adds Back up and restore.
     * A `dev.earshot` above it (or not debug-signed) is the Google Play build, not the old app
     * ([LegacyApp]). Raise it only if another debug-signed bridge release ships.
     */
    const val LEGACY_MAX_VERSION_CODE: Long = 11
}

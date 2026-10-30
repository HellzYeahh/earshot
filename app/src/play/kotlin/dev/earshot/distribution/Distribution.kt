package dev.earshot.distribution

/** The Google Play build (flavour `play`). */
object Distribution {
    /** Where this build comes from, for About and the connection report. */
    const val NAME = "Google Play"

    /** True only in the Google Play build, the one place store-only features may live. */
    const val IS_PLAY = true

    /**
     * Nothing to replace: a phone with the old debug-signed `dev.earshot` can't install this build
     * at all until that is removed (same app ID, different key), so Play's listing says so.
     */
    val LEGACY_PACKAGE: String? = null

    /** Unused here (no [LEGACY_PACKAGE]); the same value as the GitHub build's, so shared code reads one constant. */
    const val LEGACY_MAX_VERSION_CODE: Long = 11
}

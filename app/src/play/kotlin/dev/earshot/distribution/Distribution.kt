package dev.earshot.distribution

/** The Google Play build (flavour `play`, app ID `dev.earshot.foss`; see app/build.gradle.kts). */
object Distribution {
    /** Where this build comes from, for About and the connection report. */
    const val NAME = "Google Play"

    /** True only in the Google Play build, the one place store-only features may live. */
    const val IS_PLAY = true

    /**
     * The pre-1.0 Earshot (`dev.earshot`, debug-signed, up to the 0.9.1 bridge). It has a different
     * app ID from this build, so the two can sit side by side: the user moves the settings over
     * (Back up and restore) and removes it ([LegacyApp]).
     */
    val LEGACY_PACKAGE: String? = "dev.earshot"

    /** The last debug-signed `dev.earshot` build (0.9.1, the bridge). Same value in both builds. */
    const val LEGACY_MAX_VERSION_CODE: Long = 11

    /** The other current edition: the GitHub build. Only one Earshot should drive a headset. */
    const val OTHER_EDITION_PACKAGE = "dev.earshot.github"

    /** How the other edition is named in "Earshot from … is also on this phone". */
    const val OTHER_EDITION_NAME = "GitHub"
}

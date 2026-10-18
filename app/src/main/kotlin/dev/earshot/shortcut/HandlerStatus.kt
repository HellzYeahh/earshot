package dev.earshot.shortcut

/**
 * Who answers the headset's assistant gesture today: what `resolveActivity(VOICE_COMMAND,
 * MATCH_DEFAULT_ONLY)` returns. With several handlers and no default Android returns its own
 * ResolverActivity (package "android"), which is the chooser the owner saw on the Galaxy.
 */
sealed interface HandlerStatus {
    data object Earshot : HandlerStatus
    data object Chooser : HandlerStatus
    data class OtherApp(val packageName: String, val label: String) : HandlerStatus
    /** Nothing resolved (should not happen while Earshot declares the filter; treated like the chooser). */
    data object Unknown : HandlerStatus

    companion object {
        fun classify(resolvedPackage: String?, resolvedClass: String?, resolvedLabel: String?, ownPackage: String): HandlerStatus = when {
            resolvedPackage.isNullOrBlank() -> Unknown
            resolvedPackage == ownPackage -> Earshot
            // "android" on the Galaxy log; newer AOSP may host the resolver in its own package.
            resolvedPackage == "android" || resolvedPackage == "com.android.intentresolver" ||
                resolvedClass?.endsWith("ResolverActivity") == true ||
                resolvedClass?.endsWith("ChooserActivity") == true -> Chooser
            else -> OtherApp(resolvedPackage, resolvedLabel?.takeIf { it.isNotBlank() } ?: resolvedPackage)
        }
    }
}

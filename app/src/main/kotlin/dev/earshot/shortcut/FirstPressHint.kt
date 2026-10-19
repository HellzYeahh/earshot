package dev.earshot.shortcut

/**
 * The single muted hint line under the remapper's dropdown ("The first time you hold the button,
 * Android asks which app to open: pick Earshot, then Always."). Shown only while it can matter:
 * an Earshot action other than Phone assistant (that one still works when another assistant
 * answers), Earshot not yet the resolved `VOICE_COMMAND` default, and no real headset press seen.
 */
object FirstPressHint {
    fun visible(choice: ShortcutAction, handler: HandlerStatus, seenHeadsetPress: Boolean): Boolean =
        choice.isEarshotAction && choice !is ShortcutAction.OpenAssistant &&
            handler != HandlerStatus.Earshot && !seenHeadsetPress
}

/**
 * Who started [ShortcutTriggerActivity]. A press counts as a real headset press unless Earshot
 * started the activity itself: its own package as the referrer, or the internal extra.
 */
object TriggerCaller {
    /** Set on any Earshot-internal start of the trigger, so it never counts as a headset press. */
    const val EXTRA_INTERNAL = "dev.earshot.shortcut.extra.INTERNAL"

    /** [referrerPackage]: the `android-app://` referrer's package, null when unknown (Bluetooth stack starts often have none). */
    fun isHeadsetPress(referrerPackage: String?, ownPackage: String, internalExtra: Boolean): Boolean =
        !internalExtra && referrerPackage != ownPackage
}

enum class TriggerRoute {
    /** Run the headset's action (no detection running). */
    RUN,
    /** A detection is listening and this was a real headset press: report it, run nothing. */
    REPORT_DETECTED,
    /** A detection is listening and Earshot started the trigger itself: it neither counts nor runs. */
    IGNORE,
}

/** What [ShortcutTriggerActivity] does with a start. Pure, so "detect mode never runs the action" is tested. */
object TriggerRouting {
    fun route(detecting: Boolean, headsetPress: Boolean): TriggerRoute = when {
        !detecting -> TriggerRoute.RUN
        headsetPress -> TriggerRoute.REPORT_DETECTED
        else -> TriggerRoute.IGNORE
    }
}

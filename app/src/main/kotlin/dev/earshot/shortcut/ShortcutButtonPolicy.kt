package dev.earshot.shortcut

import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment

/**
 * Which mode the Bose Shortcut button (`[1.9]`) must be in for the remapper's choice. Pure, so
 * every combination is unit-tested.
 *
 * Learned on the QC Ultra 2 (fw 10.12): only "Voice assistant" (Vpa, 1) makes a press reach
 * Earshot (HFP `AT+BVRA` → `VOICE_COMMAND`); Spotify Tap sends nothing at all, no `[2.12]`, no
 * BVRA. So every Earshot action needs Vpa, and the headphone-native choices are written as-is.
 * Only modes the headphones report in their supported list are ever written.
 */
object ShortcutButtonPolicy {
    /** The headphone-native options the remapper offers, in display order (Immersive Audio, Off). */
    val NATIVE_MODES: List<ButtonAction> = listOf(ButtonAction.SPATIAL_AUDIO, ButtonAction.DISABLED)

    /** The native options to show for a button reporting [supported]; empty when the device reports none of them. */
    fun nativeOptions(supported: Collection<ButtonAction>?): List<ButtonAction> =
        NATIVE_MODES.filter { supported != null && it in supported }

    /**
     * The button mode [choice] needs, or null when nothing should be written: an Earshot action
     * needs Vpa, a [ShortcutAction.HeadphoneButton] its own (offered) mode; a mode the device
     * doesn't report is never written.
     */
    fun desiredMode(choice: ShortcutAction, supported: Collection<ButtonAction>): ButtonAction? {
        val wanted = when (choice) {
            is ShortcutAction.HeadphoneButton -> ButtonAction.fromStoredId(choice.mode).takeIf { it in NATIVE_MODES }
            else -> ButtonAction.VOICE_ASSISTANT
        }
        return wanted?.takeIf { it in supported }
    }

    /** [button] with [remembered] as its supported list when its own is empty (a frame without the mask). */
    fun withKnownSupport(button: ButtonAssignment, remembered: List<ButtonAction>?): ButtonAssignment =
        if (button.supportedActions.isEmpty() && !remembered.isNullOrEmpty()) button.copy(supportedActions = remembered) else button

    /** The write [choice] needs on [button] right now: [desiredMode] when it differs from the current mode, else null. */
    fun writeFor(choice: ShortcutAction, button: ButtonAssignment?): ButtonAction? {
        val b = button ?: return null
        return desiredMode(choice, b.supportedActions)?.takeIf { it != b.action }
    }

    /**
     * Modes that do nothing at all while Earshot holds the connection. Spotify Tap needs the Bose
     * app on the other end: on the QC Ultra 2 (fw 10.12, 2026-10-18) a press in that mode sent
     * nothing to the phone, so the owner's button was simply dead after testing it.
     */
    val DEAD_WITH_EARSHOT: Set<ButtonAction> = setOf(ButtonAction.SPOTIFY_TAP)

    /**
     * The write to make by itself when the headset at [address] connects: for a choice the user
     * actually made ([ShortcutSettings.hasExplicitChoice]), or when the button is in a mode that
     * can't work with Earshot ([DEAD_WITH_EARSHOT]); then it gets what the (default) choice needs.
     * Someone who never opened the remapper keeps an Immersive Audio or Off button from the Bose app.
     */
    fun writeOnConnect(settings: ShortcutSettings, address: String?, button: ButtonAssignment?): ButtonAction? {
        val dead = button?.action in DEAD_WITH_EARSHOT
        if (!settings.hasExplicitChoice(address) && !dead) return null
        return writeFor(settings.actionFor(address), button)
    }

    /**
     * What the remapper shows as selected for the headset at [address]. Without an explicit choice
     * a button already on a native option (Immersive Audio, Off) is shown as that option, since
     * that is what the press does; otherwise the saved (or default) choice.
     */
    fun displayedChoice(settings: ShortcutSettings, address: String?, button: ButtonAssignment?): ShortcutAction {
        if (!settings.hasExplicitChoice(address) && button != null && button.action in NATIVE_MODES) {
            return ShortcutAction.HeadphoneButton(button.action.storedId)
        }
        return settings.actionFor(address)
    }
}

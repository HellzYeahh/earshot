package dev.earshot.shortcut

import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment

/**
 * Bose `[1.9]` records after the first ("Shortcut Button 2", …; earbuds report one per bud).
 * Pure, like [ShortcutButtonPolicy], which still owns Shortcut Button 1.
 *
 * Every button on Voice assistant sends the same HFP `AT+BVRA`, so the phone can't tell them
 * apart: a second button can only *share* Shortcut Button 1's Earshot action (Voice assistant)
 * or do one of the headphones' own modes. Nothing is written for a button the user never set.
 */
object SecondaryButtonPolicy {
    /** What a secondary button can be set to, in display order: shared Earshot action, then the native modes. */
    val MODES: List<ButtonAction> = listOf(ButtonAction.VOICE_ASSISTANT) + ShortcutButtonPolicy.NATIVE_MODES

    /** The options [button] offers (only modes it reports). */
    fun options(button: ButtonAssignment): List<ButtonAction> = MODES.filter { it in button.supportedActions }

    /** The mode the saved [choice] asks for, when [button] supports it. */
    fun desiredMode(choice: ButtonChoice?, button: ButtonAssignment): ButtonAction? =
        choice?.let { ButtonAction.fromStoredId(it.mode) }?.takeIf { it in MODES && it in button.supportedActions }

    /** The write [choice] needs right now, or null (no choice, unsupported, or already there). */
    fun writeFor(choice: ButtonChoice?, button: ButtonAssignment): ButtonAction? = desiredMode(choice, button)?.takeIf { it != button.action }

    /** What the row shows: the saved choice when valid, else what the button does now. */
    fun displayed(choice: ButtonChoice?, button: ButtonAssignment): ButtonAction = desiredMode(choice, button) ?: button.action

    /** Two or more buttons on Voice assistant: they all run the same Earshot action. */
    fun sharesAssistant(buttons: List<ButtonAssignment>): Boolean = buttons.count { it.action == ButtonAction.VOICE_ASSISTANT } >= 2

    /** "right earbud" / "left earbud" for the bud records, null for the rest. */
    fun side(buttonId: Int): Side? = when (buttonId) {
        RIGHT_SHORTCUT -> Side.RIGHT
        LEFT_SHORTCUT -> Side.LEFT
        else -> null
    }

    enum class Side { RIGHT, LEFT }

    /** `ButtonId` RIGHT_SHORTCUT / LEFT_SHORTCUT in `:protocol`. */
    const val RIGHT_SHORTCUT = 0x03
    const val LEFT_SHORTCUT = 0x04
}

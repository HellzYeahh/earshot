package dev.earshot.shortcut

import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment

/** The tips the "Detect a button" sheet shows, in display order. */
enum class DetectHint {
    /** Bose touch controls are off: the Shortcut can't fire (one tap turns them on). */
    TOUCH_CONTROLS_OFF,
    /** No Bose button is on Voice assistant, so none of them can reach the phone. */
    NO_BUTTON_ON_ASSISTANT,
    /** While listening: try every control once, the power button too. Never names one control. */
    TRY_EACH_CONTROL,
    /** Ended without the gesture, Bose headset with a `[1.9]` record: Bose's name for the press, touch controls. */
    BOSE_NO_SHORTCUT,
    /** Ended without the gesture, any other headset. */
    OTHER_NO_SHORTCUT,
    /** Ended without the gesture while another app answers the gesture: it may have gone there. */
    OTHER_HANDLER,
}

/** What [DetectHints.hints] looks at. [bose]: the headset is connected through Earshot's BMAP stack. */
data class DetectHintInput(
    val listening: Boolean,
    val finished: Boolean,
    val sawAssistantGesture: Boolean,
    val bose: Boolean,
    val buttons: List<ButtonAssignment>,
    val touchControlsEnabled: Boolean?,
    val handler: HandlerStatus,
)

/**
 * Which hints apply. Pure, so each case is unit-tested. Nothing assumes which physical control
 * Bose's Shortcut sits on: the owner's QC Ultra 2 sent the gesture on a single short press of
 * the *power* button (18:44, 2026-10-18), not on the strip hold the Bose app suggests.
 */
object DetectHints {
    fun hints(input: DetectHintInput): List<DetectHint> = buildList {
        if (input.bose && input.touchControlsEnabled == false) add(DetectHint.TOUCH_CONTROLS_OFF)
        if (input.bose && input.buttons.isNotEmpty() && input.buttons.none { it.action == ButtonAction.VOICE_ASSISTANT }) {
            add(DetectHint.NO_BUTTON_ON_ASSISTANT)
        }
        if (input.sawAssistantGesture) return@buildList
        if (input.listening) add(DetectHint.TRY_EACH_CONTROL)
        if (input.finished) {
            add(if (input.bose && input.buttons.isNotEmpty()) DetectHint.BOSE_NO_SHORTCUT else DetectHint.OTHER_NO_SHORTCUT)
            if (input.handler is HandlerStatus.OtherApp) add(DetectHint.OTHER_HANDLER)
        }
    }

    /**
     * Bose's name for the press its Shortcut listens for ("Long press"), from the first record
     * on Voice assistant (else the first record); null when unknown.
     */
    fun bosePressName(buttons: List<ButtonAssignment>): String? {
        val b = buttons.firstOrNull { it.action == ButtonAction.VOICE_ASSISTANT } ?: buttons.firstOrNull() ?: return null
        return ControlClassifier.pressName(b.eventType)
    }
}

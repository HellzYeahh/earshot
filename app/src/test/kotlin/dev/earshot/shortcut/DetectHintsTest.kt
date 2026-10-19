package dev.earshot.shortcut

import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The detect sheet's hints, and the trigger never running an action while it listens. */
class DetectHintsTest {
    private val shortcut = ButtonAssignment(0x80, 9, ButtonAction.VOICE_ASSISTANT, listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.DISABLED))
    private val base = DetectHintInput(
        listening = true, finished = false, sawAssistantGesture = false, bose = true,
        buttons = listOf(shortcut), touchControlsEnabled = true, handler = HandlerStatus.Earshot,
    )

    @Test
    fun `while listening, try every control, never one hard-coded control`() {
        assertEquals(listOf(DetectHint.TRY_EACH_CONTROL), DetectHints.hints(base))
    }

    @Test
    fun `touch controls off is said up front, before anything is pressed`() {
        assertEquals(
            listOf(DetectHint.TOUCH_CONTROLS_OFF, DetectHint.TRY_EACH_CONTROL),
            DetectHints.hints(base.copy(touchControlsEnabled = false)),
        )
        // Unknown (not read, or not a Bose headset) says nothing.
        assertEquals(listOf(DetectHint.TRY_EACH_CONTROL), DetectHints.hints(base.copy(touchControlsEnabled = null)))
    }

    @Test
    fun `ended without the gesture on Bose headphones names Bose's press`() {
        val ended = base.copy(listening = false, finished = true)
        assertEquals(listOf(DetectHint.BOSE_NO_SHORTCUT), DetectHints.hints(ended))
        assertEquals("Long press", DetectHints.bosePressName(listOf(shortcut)))
        assertNull(DetectHints.bosePressName(emptyList()))
    }

    @Test
    fun `other headsets get the generic tip, and a hint when another app answers the gesture`() {
        val ended = base.copy(listening = false, finished = true, bose = false, buttons = emptyList(), touchControlsEnabled = null)
        assertEquals(listOf(DetectHint.OTHER_NO_SHORTCUT), DetectHints.hints(ended))
        assertEquals(
            listOf(DetectHint.OTHER_NO_SHORTCUT, DetectHint.OTHER_HANDLER),
            DetectHints.hints(ended.copy(handler = HandlerStatus.OtherApp("com.google.android.googlequicksearchbox", "Google"))),
        )
        // A Bose connection that reported no [1.9] record (QC35, Crusher) is treated the same way.
        assertEquals(listOf(DetectHint.OTHER_NO_SHORTCUT), DetectHints.hints(ended.copy(bose = true)))
    }

    @Test
    fun `no button on the assistant, nothing can reach the phone`() {
        val immersive = shortcut.copy(action = ButtonAction.SPATIAL_AUDIO)
        assertEquals(
            listOf(DetectHint.NO_BUTTON_ON_ASSISTANT, DetectHint.TRY_EACH_CONTROL),
            DetectHints.hints(base.copy(buttons = listOf(immersive))),
        )
    }

    @Test
    fun `once the gesture arrived only the standing warnings stay`() {
        assertEquals(emptyList(), DetectHints.hints(base.copy(sawAssistantGesture = true)))
        assertEquals(
            listOf(DetectHint.TOUCH_CONTROLS_OFF),
            DetectHints.hints(base.copy(sawAssistantGesture = true, listening = false, finished = true, touchControlsEnabled = false)),
        )
    }

    @Test
    fun `a trigger in detect mode is reported, never run`() {
        assertEquals(TriggerRoute.RUN, TriggerRouting.route(detecting = false, headsetPress = true))
        assertEquals(TriggerRoute.RUN, TriggerRouting.route(detecting = false, headsetPress = false))
        assertEquals(TriggerRoute.REPORT_DETECTED, TriggerRouting.route(detecting = true, headsetPress = true))
        // Earshot's own start of the trigger neither counts as a detection nor runs anything.
        assertEquals(TriggerRoute.IGNORE, TriggerRouting.route(detecting = true, headsetPress = false))
        val own = "dev.earshot"
        assertEquals(
            TriggerRoute.IGNORE,
            TriggerRouting.route(true, TriggerCaller.isHeadsetPress(referrerPackage = own, ownPackage = own, internalExtra = false)),
        )
        assertEquals(
            TriggerRoute.REPORT_DETECTED,
            TriggerRouting.route(true, TriggerCaller.isHeadsetPress(referrerPackage = "com.android.bluetooth", ownPackage = own, internalExtra = false)),
        )
    }
}

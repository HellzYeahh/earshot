package dev.earshot.shortcut

import dev.earshot.core.ButtonAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstPressHintTest {
    private val handlers = listOf(
        HandlerStatus.Earshot,
        HandlerStatus.Chooser,
        HandlerStatus.Unknown,
        HandlerStatus.OtherApp("com.samsung.android.bixby.agent", "Bixby Voice"),
    )
    private val needsEarshot = listOf(ShortcutAction.CycleEq, ShortcutAction.VoiceMemo, ShortcutAction.LaunchApp("a.b", "B"), ShortcutAction.LaunchApp())
    private val never = listOf(
        ShortcutAction.OpenAssistant(),
        ShortcutAction.OpenAssistant("a.b/a.b.C", "Google"),
        ShortcutAction.HeadphoneButton(ButtonAction.SPATIAL_AUDIO.wire),
        ShortcutAction.HeadphoneButton(ButtonAction.DISABLED.wire),
    )

    @Test
    fun `shown for an Earshot action until Earshot is the default or a press was seen`() {
        for (choice in needsEarshot) for (handler in handlers) for (seen in listOf(false, true)) {
            assertEquals(handler != HandlerStatus.Earshot && !seen, FirstPressHint.visible(choice, handler, seen), "$choice / $handler / seen=$seen")
        }
    }

    @Test
    fun `never shown for Phone assistant or the headphones' own modes`() {
        for (choice in never) for (handler in handlers) for (seen in listOf(false, true)) {
            assertFalse(FirstPressHint.visible(choice, handler, seen), "$choice / $handler / seen=$seen")
        }
    }

    @Test
    fun `a press counts unless Earshot started the trigger itself`() {
        val own = "dev.earshot"
        assertTrue(TriggerCaller.isHeadsetPress("com.android.bluetooth", own, internalExtra = false))
        assertTrue(TriggerCaller.isHeadsetPress("android", own, internalExtra = false)) // via Android's chooser
        assertTrue(TriggerCaller.isHeadsetPress(null, own, internalExtra = false)) // no referrer at all
        assertFalse(TriggerCaller.isHeadsetPress(own, own, internalExtra = false))
        assertFalse(TriggerCaller.isHeadsetPress("com.android.bluetooth", own, internalExtra = true))
    }
}

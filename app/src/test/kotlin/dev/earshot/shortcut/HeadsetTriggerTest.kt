package dev.earshot.shortcut

import dev.earshot.core.ButtonAction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadsetTriggerTest {
    private val headset = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `off for someone who never chose anything`() {
        assertFalse(HeadsetTrigger.needed(ShortcutSettings(), detecting = false))
    }

    @Test
    fun `on while Detect a button listens`() {
        assertTrue(HeadsetTrigger.needed(ShortcutSettings(), detecting = true))
    }

    @Test
    fun `on for an Earshot action as the default or for one headset`() {
        for (action in listOf(ShortcutAction.CycleEq, ShortcutAction.VoiceMemo, ShortcutAction.LaunchApp("a.b", "B"))) {
            assertTrue(HeadsetTrigger.needed(ShortcutSettings(defaultAction = action), detecting = false), "$action as default")
            assertTrue(HeadsetTrigger.needed(ShortcutSettings().withChoice(headset, "QC", action), detecting = false), "$action for a headset")
        }
    }

    @Test
    fun `on for a picked assistant, off for the automatic one`() {
        val picked = ShortcutAction.OpenAssistant("com.samsung.android.bixby.agent/.Voice", "Bixby")
        assertTrue(HeadsetTrigger.needed(ShortcutSettings().withChoice(headset, "QC", picked), detecting = false))
        assertFalse(HeadsetTrigger.needed(ShortcutSettings().withChoice(headset, "QC", ShortcutAction.OpenAssistant()), detecting = false))
    }

    @Test
    fun `off for headphone modes, which send no voice request`() {
        for (mode in listOf(ButtonAction.SPATIAL_AUDIO, ButtonAction.DISABLED)) {
            val settings = ShortcutSettings().withChoice(headset, "QC", ShortcutAction.HeadphoneButton(mode.storedId))
            assertFalse(HeadsetTrigger.needed(settings, detecting = false), "$mode")
        }
    }
}

package dev.earshot.ui

import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneState
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.shortcut.HandlerStatus
import dev.earshot.shortcut.HfpHeadset
import dev.earshot.shortcut.ShortcutAction
import dev.earshot.shortcut.ShortcutSettings
import dev.earshot.ui.tools.RemapperHeadset
import dev.earshot.ui.tools.ShortcutChoice
import dev.earshot.ui.tools.ShortcutsUiState
import dev.earshot.ui.tools.choice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Shortcuts remapper screen's decisions: which headset, which options, the hint. */
class RemapperLogicTest {
    private val buds = HfpHeadset("AA:BB:CC:DD:EE:01", "Pixel Buds Pro")

    @Test
    fun `the headset at the top is Earshot's, else the phone's HFP headset, else none`() {
        val bose = RemapperHeadset.resolve(FakeHeadphoneRepository.connectedState(), buds)
        assertEquals(FakeHeadphoneRepository.DEVICE_ADDRESS, bose?.address)
        assertTrue(bose!!.viaEarshot)
        assertFalse(bose.earbuds)

        val other = RemapperHeadset.resolve(HeadphoneState(), buds)
        assertEquals(RemapperHeadset(buds.address, buds.name, earbuds = true, viaEarshot = false), other)

        assertNull(RemapperHeadset.resolve(HeadphoneState(), null))
        assertTrue(RemapperHeadset.resolve(FakeHeadphoneRepository.connectedEarbudsState(), null)!!.earbuds)
    }

    @Test
    fun `dropdown offers Earshot's actions, then only the native modes the device reports`() {
        assertEquals(ShortcutChoice.EARSHOT, ShortcutChoice.options(emptyList(), ShortcutChoice.CYCLE_EQ))
        assertEquals(
            ShortcutChoice.EARSHOT + ShortcutChoice.IMMERSIVE_AUDIO + ShortcutChoice.OFF,
            ShortcutChoice.options(listOf(ButtonAction.SPATIAL_AUDIO, ButtonAction.DISABLED), ShortcutChoice.PHONE_ASSISTANT),
        )
        assertEquals(ShortcutChoice.EARSHOT + ShortcutChoice.OFF, ShortcutChoice.options(listOf(ButtonAction.DISABLED), ShortcutChoice.OFF))
        // A saved native choice stays visible while that headset isn't connected through Earshot.
        assertEquals(ShortcutChoice.EARSHOT + ShortcutChoice.IMMERSIVE_AUDIO, ShortcutChoice.options(emptyList(), ShortcutChoice.IMMERSIVE_AUDIO))
    }

    @Test
    fun `screen state ties the policy together`() {
        val hp = FakeHeadphoneRepository.connectedState() // button on Immersive Audio, QC Ultra 2 list
        val headset = RemapperHeadset.resolve(hp, null)
        val fresh = ShortcutsUiState(handler = HandlerStatus.Chooser, headset = headset, button = hp.shortcutButton)
        assertEquals(ShortcutChoice.IMMERSIVE_AUDIO, fresh.choice.choice())
        assertEquals(listOf(ButtonAction.SPATIAL_AUDIO, ButtonAction.DISABLED), fresh.nativeModes)
        assertFalse(fresh.showFirstPressHint)

        val eq = fresh.copy(settings = ShortcutSettings().withChoice(headset!!.address, headset.name, ShortcutAction.CycleEq))
        assertEquals(ShortcutAction.CycleEq, eq.choice)
        assertTrue(eq.showFirstPressHint)
        assertFalse(eq.copy(handler = HandlerStatus.Earshot).showFirstPressHint)
        assertFalse(eq.copy(settings = eq.settings.copy(seenHeadsetPress = true)).showFirstPressHint)

        // Not through Earshot: no native modes.
        assertTrue(ShortcutsUiState(headset = RemapperHeadset.resolve(HeadphoneState(), buds)).nativeModes.isEmpty())
    }
}

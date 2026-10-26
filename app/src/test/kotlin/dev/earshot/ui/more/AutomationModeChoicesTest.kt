package dev.earshot.ui.more

import dev.earshot.core.AutomationAction
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeRef
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.service.SennheiserStateMapping
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the automation editor writes when a mode is picked, and what it shows for a saved one. */
class AutomationModeChoicesTest {

    private val fake = FakeHeadphoneRepository.connectedState()
    private val bose = fake.connection.deviceAddress!!
    private val other = "AC:80:0A:00:00:01"

    @Test fun `a pick writes the meaning, the headset and the slot`() {
        val choices = AutomationModeChoices.choices(fake.modes, bose)
        // Only configured slots: the fake's empty 5..10 are not offered.
        assertEquals(listOf(0, 1, 2, 3, 4), choices.map { it.modeIndex })
        assertEquals(AutomationAction.SetMode(2, ModeRef(ModeMeaning.CUSTOM, "Immersion", bose, 2)), choices[2])
        assertEquals(AutomationAction.SetMode(0, ModeRef(ModeMeaning.QUIET, "Quiet", bose, 0)), AutomationModeChoices.default(fake.modes, bose))
    }

    @Test fun `with no headset the generic meanings are offered`() {
        val choices = AutomationModeChoices.choices(emptyList(), null)
        assertEquals(listOf(ModeMeaning.QUIET, ModeMeaning.AWARE, ModeMeaning.OFF), choices.map { it.mode?.meaning })
        // modeIndex is only for a downgraded 0.75 build.
        assertEquals(listOf(0, 1, 2), choices.map { it.modeIndex })
        assertEquals("Noise cancelling", AutomationModeChoices.label(choices[0], emptyList(), null))
    }

    @Test fun `a saved mode shows as what it means on the connected headset`() {
        val sennheiser = SennheiserStateMapping.MODES
        val choices = AutomationModeChoices.choices(sennheiser, other)
        val aware = AutomationAction.SetMode(1, ModeRef(ModeMeaning.AWARE, "Aware", bose, 1))
        val shown = AutomationModeChoices.shown(aware, choices, sennheiser, other)
        assertEquals(choices[1], shown)
        assertEquals("Transparency", AutomationModeChoices.label(shown, sennheiser, other))
        // One it doesn't have stays itself, under its saved name.
        val immersion = AutomationAction.SetMode(2, ModeRef(ModeMeaning.CUSTOM, "Immersion", bose, 2))
        assertEquals(immersion, AutomationModeChoices.shown(immersion, choices, sennheiser, other))
        assertEquals("Immersion", AutomationModeChoices.label(immersion, sennheiser, other))
        // An unbound 0.75 rule shows by slot, as 0.75 did.
        assertEquals(choices[2], AutomationModeChoices.shown(AutomationAction.SetMode(2), choices, sennheiser, other))
    }
}

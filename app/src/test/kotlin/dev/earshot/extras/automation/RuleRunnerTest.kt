package dev.earshot.extras.automation

import dev.earshot.core.AutomationAction
import dev.earshot.core.AutomationRepository
import dev.earshot.core.AutomationRule
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeRef
import dev.earshot.core.SpatialAudio
import dev.earshot.core.Trigger
import dev.earshot.core.fake.FakeAudioTuningRepository
import dev.earshot.core.fake.FakeAutomationRepository
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.service.SennheiserStateMapping
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a "set mode" rule does on each make: by meaning when bound, by slot (as 0.75) when not. */
class RuleRunnerTest {

    private val bose = "68:F2:1F:12:34:56"
    private val crusherAddress = "AC:80:0A:00:00:01"

    /** CrusherProtocol.withMode's list: slot 2 is Off, where a QC Ultra has Immersion. */
    private val crusherModes = listOf(
        ListeningMode(0, "Quiet", isPreset = true, isEditable = false, isConfigured = true),
        ListeningMode(1, "Aware", isPreset = true, isEditable = false, isConfigured = true),
        ListeningMode(2, "Off", isPreset = true, isEditable = false, isConfigured = true),
    )

    private class LoggingAutomation : AutomationRepository by FakeAutomationRepository(), AutomationLog {
        val lines = ArrayList<String>()
        override fun append(entry: String) { lines += entry }
    }

    private fun headset(modes: List<ListeningMode>, address: String, current: Int = 0) = FakeHeadphoneRepository(
        commandDelayMillis = 0,
        startConnected = true,
        connectedTemplate = {
            HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTED, "x", address), modes = modes, currentModeIndex = current)
        },
    )

    private fun rule(vararg actions: AutomationAction) = AutomationRule("r", "Rule", true, Trigger.OnConnect, actions.toList())

    @Test fun `an unbound 0_75 rule still sends its slot`() = runTest {
        val crusher = headset(crusherModes, crusherAddress)
        val log = LoggingAutomation()
        RuleRunner(crusher, FakeAudioTuningRepository(), log).run(rule(AutomationAction.SetMode(2)), "Connected")
        assertEquals(2, crusher.state.value.currentModeIndex)
        assertTrue(log.lines.single().endsWith("Connected → Off"), log.lines.single())
    }

    @Test fun `a Bose Immersion rule fails on the Crusher instead of turning it off`() = runTest {
        val crusher = headset(crusherModes, crusherAddress)
        val log = LoggingAutomation()
        val immersion = AutomationAction.SetMode(2, ModeRef(ModeMeaning.CUSTOM, "Immersion", bose, 2))
        RuleRunner(crusher, FakeAudioTuningRepository(), log).run(rule(immersion), "Connected")
        assertEquals(0, crusher.state.value.currentModeIndex)
        assertTrue(log.lines.single().endsWith("Connected → Immersion failed: Immersion isn't a mode of these headphones"), log.lines.single())
    }

    @Test fun `a bound meaning picks that headset's own slot`() = runTest {
        // Aware picked on the Bose (slot 1) is transparency on a Sennheiser, also slot 1; Off is slot 2 there.
        val sennheiser = headset(SennheiserStateMapping.MODES, crusherAddress)
        val runner = RuleRunner(sennheiser, FakeAudioTuningRepository(), LoggingAutomation())
        runner.run(rule(AutomationAction.SetMode(1, ModeRef(ModeMeaning.AWARE, "Aware", bose, 1))))
        assertEquals(1, sennheiser.state.value.currentModeIndex)
        val second = RuleRunner(sennheiser, FakeAudioTuningRepository(), LoggingAutomation())
        second.run(rule(AutomationAction.SetMode(2, ModeRef(ModeMeaning.OFF))))
        assertEquals(2, sennheiser.state.value.currentModeIndex)
        // Described with the connected headset's own name.
        assertEquals("Transparency", runner.describe(AutomationAction.SetMode(1, ModeRef(ModeMeaning.AWARE, "Aware", bose, 1))))
    }

    @Test fun `modes not loaded yet say so`() = runTest {
        val empty = headset(emptyList(), crusherAddress)
        val log = LoggingAutomation()
        RuleRunner(empty, FakeAudioTuningRepository(), log).run(rule(AutomationAction.SetMode(0, ModeRef(ModeMeaning.QUIET))), "Connected")
        assertTrue(log.lines.single().endsWith("failed: The listening modes aren't loaded yet"), log.lines.single())
    }

    @Test fun `spatial audio is read with the frozen stored code`() = runTest {
        val crusher = headset(crusherModes, crusherAddress)
        val runner = RuleRunner(crusher, FakeAudioTuningRepository(), LoggingAutomation())
        runner.run(rule(AutomationAction.SetNoise(6, true, false, SpatialAudio.HEAD.storedId)))
        assertEquals(SpatialAudio.HEAD, crusher.state.value.noise?.spatialAudio)
        assertEquals("ANC 6, Still", runner.describe(AutomationAction.SetNoise(6, true, false, 1)))
    }
}

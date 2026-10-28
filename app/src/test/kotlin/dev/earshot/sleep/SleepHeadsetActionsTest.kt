package dev.earshot.sleep

import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCapabilities
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.NightExtra
import dev.earshot.core.NoiseSettings
import dev.earshot.core.SleepEndAction
import dev.earshot.core.SleepPlan
import dev.earshot.core.VendorControl
import dev.earshot.core.VoicePrompts
import dev.earshot.core.fake.FakeHeadphoneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The night's headset steps on three kinds of headset: a Bose-like one, a Sleep A30-like one, and one with no driver. */
class SleepHeadsetActionsTest {

    /** Records commands and applies them to its own state; everything else goes to the fake. */
    private class TestHeadphones(initial: HeadphoneState, private val refusePowerOff: Boolean = true) :
        HeadphoneRepository by FakeHeadphoneRepository(commandDelayMillis = 0) {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<HeadphoneState> = _state
        val calls = ArrayList<String>()

        override suspend fun setMode(index: Int) { calls += "mode $index"; _state.update { it.copy(currentModeIndex = index) } }
        override suspend fun setNoise(settings: NoiseSettings) { calls += "noise anc=${settings.ancEnabled}"; _state.update { it.copy(noise = settings) } }
        override suspend fun setAncEnabled(enabled: Boolean) = setNoise(_state.value.noise!!.copy(ancEnabled = enabled))
        override suspend fun setTouchControls(enabled: Boolean) { calls += "touch $enabled"; _state.update { it.copy(touchControlsEnabled = enabled) } }
        override suspend fun setVoicePrompts(prompts: VoicePrompts) { calls += "prompts ${prompts.enabled}"; _state.update { it.copy(voicePrompts = prompts) } }
        override suspend fun setVendorControl(id: String, value: Int) {
            calls += "vendor $id=$value"
            _state.update { s -> s.copy(vendorControls = s.vendorControls.map { if (it.id == id) it.copy(value = value) else it }) }
        }
        override suspend fun powerOff() { calls += "power off"; if (refusePowerOff) throw HeadphoneCommandException("Power off isn't supported") }
    }

    private class TestLink(override val canConnect: Boolean = true, private val linked: Boolean = true) : HeadsetLink {
        val calls = ArrayList<String>()
        override fun isLinked(address: String) = linked
        override fun connect(address: String) { calls += "connect" }
        override fun disconnect(address: String) { calls += "disconnect" }
    }

    private val text = SleepText { id, _ -> "text-$id" }
    private val connected = ConnectionState(ConnectionStatus.CONNECTED, "Buds", "00:11:22:33:44:55")

    private val boseLike = HeadphoneState(
        connection = connected,
        modes = listOf(
            ListeningMode(0, "Quiet", isPreset = true, isEditable = false, isConfigured = true),
            ListeningMode(1, "Aware", isPreset = true, isEditable = false, isConfigured = true),
        ),
        currentModeIndex = 0,
        noise = NoiseSettings(cancellingLevel = 10, ancEnabled = true),
        touchControlsEnabled = true,
        voicePrompts = VoicePrompts(true, 3),
    )

    private val a30Like = HeadphoneState(
        connection = connected,
        modes = listOf(
            ListeningMode(0, "Noise cancelling", isPreset = true, isEditable = false, isConfigured = true),
            ListeningMode(1, "Off", isPreset = true, isEditable = false, isConfigured = true),
        ),
        currentModeIndex = 0,
        touchControlsEnabled = true,
        vendorControls = listOf(
            VendorControl("prompt_low_battery", "Low battery prompt", value = 1),
            VendorControl("prompt_anc", "Noise cancelling prompt", value = 0),
            VendorControl("listening_mode", "Listening mode", value = 0, options = listOf("Bluetooth", "Local sounds")),
        ),
        capabilities = HeadphoneCapabilities(ancToggle = false, noiseCancelling = false, liveNoiseControl = false, formFactor = FormFactor.EARBUDS),
    )

    private val generic = HeadphoneState(
        connection = connected.copy(audioLinked = true),
        capabilities = HeadphoneCapabilities(ownSettings = false, listeningModes = false, formFactor = FormFactor.EARBUDS),
        batteryPercent = 60,
    )

    @Test
    fun `bose-like headphones switch noise off with the ANC switch and get it back`() = runTest {
        val hp = TestHeadphones(boseLike)
        val actions = SleepHeadsetActions(hp, TestLink(), text)
        assertIs<SleepHeadsetActions.NoiseOffPlan.AncSwitch>(actions.noiseOffPlan(hp.state.value))
        val notes = mutableListOf<StepNote>()
        val record = actions.noiseOff(hp.state.value, RestoreRecord(), notes)
        assertEquals(false, hp.state.value.noise?.ancEnabled)
        assertEquals(true, record.ancEnabled)
        val left = actions.restore(record.copy(due = true), hp.state.value, notes)
        assertEquals(true, hp.state.value.noise?.ancEnabled)
        assertFalse(left.headsetPart)
    }

    @Test
    fun `an Off mode is chosen by meaning and the old mode comes back in the morning`() = runTest {
        val hp = TestHeadphones(a30Like)
        val actions = SleepHeadsetActions(hp, TestLink(), text)
        val notes = mutableListOf<StepNote>()
        val record = actions.noiseOff(hp.state.value, RestoreRecord(), notes)
        assertEquals(1, hp.state.value.currentModeIndex)
        assertEquals("noise cancelling", record.mode?.name?.lowercase())
        actions.restore(record, hp.state.value, notes)
        assertEquals(0, hp.state.value.currentModeIndex)
    }

    @Test
    fun `noise already off is left alone and nothing is restored`() = runTest {
        val hp = TestHeadphones(a30Like.copy(currentModeIndex = 1))
        val actions = SleepHeadsetActions(hp, TestLink(), text)
        val record = actions.noiseOff(hp.state.value, RestoreRecord(), mutableListOf())
        assertNull(record.mode)
        assertTrue(hp.calls.isEmpty())
    }

    @Test
    fun `touch lock and prompts off are remembered and undone, vendor prompts included`() = runTest {
        val hp = TestHeadphones(a30Like)
        val actions = SleepHeadsetActions(hp, TestLink(), text)
        val plan = SleepPlan(nightExtras = setOf(NightExtra.TOUCH_LOCK, NightExtra.PROMPTS_OFF))
        val record = actions.applyNightExtras(plan, hp.state.value, RestoreRecord(), mutableListOf())
        assertEquals(false, hp.state.value.touchControlsEnabled)
        // Only the prompt that was on is switched off (and so restored); the dropdown isn't a prompt.
        assertEquals(mapOf("prompt_low_battery" to 1), record.vendorPrompts)
        assertEquals(listOf("touch false", "vendor prompt_low_battery=0"), hp.calls)
        actions.restore(record, hp.state.value, mutableListOf())
        assertEquals(true, hp.state.value.touchControlsEnabled)
        assertEquals(1, hp.state.value.vendorControls.first { it.id == "prompt_low_battery" }.value)
    }

    @Test
    fun `restore waits for the right headset`() = runTest {
        val hp = TestHeadphones(boseLike.copy(connection = connected.copy(deviceAddress = "AA:AA:AA:AA:AA:AA")))
        val actions = SleepHeadsetActions(hp, TestLink(), text)
        val record = RestoreRecord(address = "00:11:22:33:44:55", touchControlsEnabled = true)
        assertEquals(record, actions.restore(record, hp.state.value, mutableListOf()))
        assertTrue(hp.calls.isEmpty())
    }

    @Test
    fun `a headset without a driver can't do headset steps but says why`() {
        val hp = TestHeadphones(generic)
        val abilities = SleepHeadsetActions(hp, TestLink(linked = false), text).abilities(hp.state.value)
        assertFalse(abilities.noiseOff.available)
        assertFalse(abilities.touchLock.available)
        assertFalse(abilities.powerOff.available)
        assertFalse(abilities.disconnect.available)
        assertTrue(abilities.noiseOff.note != null && abilities.disconnect.note != null)
        assertTrue(abilities.audioConnected)
        assertEquals(288, abilities.minutesLeft) // 60 % of ~8 h, a guess
    }

    @Test
    fun `power off that the driver refuses falls back to disconnect`() = runTest {
        val hp = TestHeadphones(a30Like)
        val link = TestLink()
        val notes = mutableListOf<StepNote>()
        SleepHeadsetActions(hp, link, text).endAction(SleepEndAction.POWER_OFF, hp.state.value, notes)
        assertEquals(listOf("power off"), hp.calls)
        assertEquals(listOf("disconnect"), link.calls)
    }

    @Test
    fun `disconnect without a link is a note, not a crash`() = runTest {
        val hp = TestHeadphones(generic)
        val link = TestLink(linked = false)
        val notes = mutableListOf<StepNote>()
        SleepHeadsetActions(hp, link, text).endAction(SleepEndAction.DISCONNECT, hp.state.value, notes)
        assertTrue(link.calls.isEmpty())
        assertTrue(notes.single().isProblem)
    }
}

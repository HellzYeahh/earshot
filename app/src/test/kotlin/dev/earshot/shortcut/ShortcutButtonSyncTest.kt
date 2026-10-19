package dev.earshot.shortcut

import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.fake.FakeHeadphoneRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutButtonSyncTest {
    private val address = FakeHeadphoneRepository.DEVICE_ADDRESS

    /** The fake QC Ultra 2 (button on Immersive Audio) with every `[1.9]` write counted. */
    private class Counting(val fake: FakeHeadphoneRepository, var refuse: Boolean = false) : HeadphoneRepository by fake {
        val writes = mutableListOf<ButtonAction>()
        override suspend fun setShortcutButton(action: ButtonAction) {
            writes += action
            if (refuse) throw HeadphoneCommandException("The headphones ignored the change")
            fake.setShortcutButton(action)
        }
    }

    private fun TestScope.setUp(settings: ShortcutSettings, refuse: Boolean = false): Counting {
        val repo = Counting(FakeHeadphoneRepository(backgroundScope, commandDelayMillis = 0, startConnected = true), refuse)
        ShortcutButtonSync(repo, { settings }, backgroundScope, log = {}).start()
        testScheduler.runCurrent()
        return repo
    }

    private fun TestScope.reconnect(repo: Counting) {
        repo.fake.disconnect()
        testScheduler.runCurrent()
        repo.fake.connect()
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
    }

    @Test
    fun `an Earshot choice puts the button on voice assistant once per connection`() = runTest {
        val repo = setUp(ShortcutSettings().withChoice(address, "QC", ShortcutAction.CycleEq))
        assertEquals(listOf(ButtonAction.VOICE_ASSISTANT), repo.writes)
        assertEquals(ButtonAction.VOICE_ASSISTANT, repo.state.value.shortcutButton?.action)

        // Changed back elsewhere (the Bose app) while connected: left alone until the next connect.
        repo.fake.setShortcutButton(ButtonAction.SPATIAL_AUDIO)
        testScheduler.runCurrent()
        assertEquals(1, repo.writes.size)

        reconnect(repo)
        assertEquals(listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.VOICE_ASSISTANT), repo.writes)
    }

    @Test
    fun `no write without a choice of the user's, or when the mode already matches`() = runTest {
        assertTrue(setUp(ShortcutSettings()).writes.isEmpty())
        assertTrue(setUp(ShortcutSettings().withChoice(address, "QC", ShortcutAction.HeadphoneButton(ButtonAction.SPATIAL_AUDIO.wire))).writes.isEmpty())
    }

    @Test
    fun `a refused write is not retried within the connection`() = runTest {
        val repo = setUp(ShortcutSettings(defaultAction = ShortcutAction.VoiceMemo), refuse = true)
        assertEquals(1, repo.writes.size)
        repo.fake.setShortcutButton(ButtonAction.DISABLED)
        testScheduler.runCurrent()
        assertEquals(1, repo.writes.size)
    }

    @Test
    fun `a choice from the remapper is applied right away and its error surfaces`() = runTest {
        val repo = setUp(ShortcutSettings())
        val sync = ShortcutButtonSync(repo, { ShortcutSettings() }, backgroundScope, log = {})
        sync.applyChoice(ShortcutAction.HeadphoneButton(ButtonAction.DISABLED.wire))
        assertEquals(ButtonAction.DISABLED, repo.state.value.shortcutButton?.action)
        sync.applyChoice(ShortcutAction.HeadphoneButton(ButtonAction.DISABLED.wire))
        assertEquals(listOf(ButtonAction.DISABLED), repo.writes) // already there: no second write

        repo.refuse = true
        assertFailsWith<HeadphoneCommandException> { sync.applyChoice(ShortcutAction.LaunchApp("a.b", "B")) }
    }

    @Test
    fun `a 3-byte echo without the mask doesn't block the next write`() = runTest {
        val fake = FakeHeadphoneRepository(backgroundScope, commandDelayMillis = 0, startConnected = true)
        val shown = MutableStateFlow(fake.state.value)
        // Like a firmware whose SET_GET echo is only [button, event, action].
        val repo = object : HeadphoneRepository by fake {
            override val state: StateFlow<HeadphoneState> = shown
            override suspend fun setShortcutButton(action: ButtonAction) {
                shown.update { it.copy(shortcutButton = it.shortcutButton?.copy(action = action, supportedActions = emptyList())) }
            }
        }
        val sync = ShortcutButtonSync(repo, { ShortcutSettings() }, backgroundScope, log = {})
        sync.button(repo.state.value) // the full read seen first
        sync.applyChoice(ShortcutAction.HeadphoneButton(ButtonAction.DISABLED.wire))
        assertTrue(repo.state.value.shortcutButton!!.supportedActions.isEmpty())
        sync.applyChoice(ShortcutAction.CycleEq)
        assertEquals(ButtonAction.VOICE_ASSISTANT, repo.state.value.shortcutButton?.action)
    }

    /** Earbuds with a record per bud; every per-record write is logged with its button id and event. */
    private class Buds(val fake: FakeHeadphoneRepository) : HeadphoneRepository by fake {
        val modes = listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.DISABLED, ButtonAction.SPATIAL_AUDIO)
        private val right = dev.earshot.core.ButtonAssignment(0x03, 9, ButtonAction.VOICE_ASSISTANT, modes)
        private val left = dev.earshot.core.ButtonAssignment(0x04, 5, ButtonAction.SPATIAL_AUDIO, modes)
        val shown = MutableStateFlow(fake.state.value.copy(shortcutButton = right, shortcutButtons = listOf(right, left)))
        override val state: StateFlow<HeadphoneState> = shown
        val writes = mutableListOf<Triple<Int, Int, ButtonAction>>()
        override suspend fun setShortcutButton(action: ButtonAction) = setShortcutButton(right.buttonId, right.eventType, action)
        override suspend fun setShortcutButton(buttonId: Int, eventType: Int, action: ButtonAction) {
            writes += Triple(buttonId, eventType, action)
            shown.update { s ->
                val records = s.shortcutButtons.map { if (it.buttonId == buttonId) it.copy(action = action) else it }
                s.copy(shortcutButtons = records, shortcutButton = records.first())
            }
        }
    }

    @Test
    fun `each bud's record is written with its own id and event, only when the user set it`() = runTest {
        val buds = Buds(FakeHeadphoneRepository(backgroundScope, commandDelayMillis = 0, startConnected = true))
        val settings = ShortcutSettings().withButtonChoice(address, 0x04, ButtonAction.VOICE_ASSISTANT.wire)
        ShortcutButtonSync(buds, { settings }, backgroundScope, log = {}).start()
        testScheduler.runCurrent()
        // The right bud is already on Voice assistant (no explicit choice anyway); the left gets its saved mode.
        assertEquals(listOf(Triple(0x04, 5, ButtonAction.VOICE_ASSISTANT)), buds.writes)
        assertTrue(SecondaryButtonPolicy.sharesAssistant(buds.state.value.allShortcutButtons))

        val sync = ShortcutButtonSync(buds, { settings }, backgroundScope, log = {})
        assertEquals(listOf(0x03, 0x04), sync.buttons(buds.state.value).map { it.buttonId })
        sync.applySecondary(0x04, ButtonAction.DISABLED)
        sync.applySecondary(0x04, ButtonAction.DISABLED) // already there
        sync.applySecondary(0x04, ButtonAction.SPOTIFY_TAP) // not reported by the bud
        sync.applySecondary(0x42, ButtonAction.DISABLED) // no such record
        assertEquals(Triple(0x04, 5, ButtonAction.DISABLED), buds.writes.last())
        assertEquals(2, buds.writes.size)
        assertEquals(ButtonAction.VOICE_ASSISTANT, buds.state.value.shortcutButton?.action, "the right bud untouched")
    }
}

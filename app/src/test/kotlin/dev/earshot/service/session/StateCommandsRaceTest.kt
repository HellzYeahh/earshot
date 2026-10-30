package dev.earshot.service.session

import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A [Mutex] that runs [beforeUnlock] (once) right before it lets go: the window between a
 * command's last drain and its unlock, where a push from the collector's thread used to park
 * itself with nobody left to apply it.
 */
private class WindowMutex(private val inner: Mutex = Mutex()) : Mutex by inner {
    var beforeUnlock: () -> Unit = {}

    override fun unlock(owner: Any?) {
        val hook = beforeUnlock
        beforeUnlock = {}
        hook()
        inner.unlock(owner)
    }
}

/** The two push races of [StateCommands] found by the pre-release audit (R27). */
class StateCommandsRaceTest {

    private fun connectedState() = MutableStateFlow(HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTED, "X", "A")))

    @Test fun `a push between a command's last drain and its unlock is applied, not left parked`() = runTest {
        val state = connectedState()
        val mutex = WindowMutex()
        val commands = StateCommands<String, Int>(state, mutex, { "link" }, RecordingLog(), onPush = { b, s -> s.copy(batteryPercent = b) })
        // The headset's mode or battery push lands just as the command finishes.
        mutex.beforeUnlock = { commands.push(50) }

        commands.run("setVolume") { _, s -> s.copy(volume = 12) }

        assertEquals(12, state.value.volume)
        assertEquals(50, state.value.batteryPercent, "applied once the mutex was free, without waiting for another push")
        assertFalse(mutex.isLocked)
    }

    @Test fun `a push applies under the mutex, so no command can take its snapshot in between`() = runTest {
        val state = connectedState()
        val mutex = Mutex()
        val heldWhileApplying = ArrayList<Boolean>()
        val commands = StateCommands<String, Int>(state, mutex, { "link" }, RecordingLog(), onPush = { b, s ->
            heldWhileApplying += mutex.isLocked
            s.copy(batteryPercent = b)
        })

        commands.push(60)
        commands.push(61)

        assertEquals(listOf(true, true), heldWhileApplying)
        assertFalse(mutex.isLocked, "released after the push")
        assertEquals(61, state.value.batteryPercent)
        // And a command still runs normally afterwards.
        commands.run("refresh") { _, s -> s.copy(volume = 3) }
        assertEquals(3, state.value.volume)
        assertEquals(61, state.value.batteryPercent)
    }

    @Test fun `a push under a plain lock holder still waits for it`() = runTest {
        val state = connectedState()
        val mutex = Mutex()
        val commands = StateCommands<String, Int>(state, mutex, { "link" }, RecordingLog(), onPush = { b, s -> s.copy(batteryPercent = b) })
        mutex.lock()
        commands.push(40)
        assertNull(state.value.batteryPercent, "parked while the lock is held")
        mutex.unlock()
        commands.push(41)
        assertEquals(41, state.value.batteryPercent, "the older push went first, the newer one last")
    }
}

package dev.earshot.service.session

import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The replace-state command wrapper Bose and the Crusher share: a push under a command is merged
 * after the command's result instead of being overwritten by it; the busy flag; the error order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateCommandsTest {

    /** A push sets the battery; a command sets the volume from its snapshot (and carries the snapshot's battery). */
    private class Rig(connected: Boolean = true) {
        val state = MutableStateFlow(HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTED, "X", "A")))
        val mutex = Mutex()
        val log = RecordingLog()
        var link: String? = if (connected) "link" else null
        val commands = StateCommands<String, Int>(
            state, mutex,
            requireConnection = { link ?: throw HeadphoneCommandException("Headphones not connected") },
            log = log,
            onPush = { battery, s -> if (battery < 0) null else s.copy(batteryPercent = battery) },
            mapError = { e -> (e as? UnsupportedOperationException)?.let { HeadphoneCommandException("mapped: ${it.message}", it) } },
        )
    }

    @Test fun `a push without a command applies at once`() = runTest {
        val r = Rig()
        r.commands.push(55)
        assertEquals(55, r.state.value.batteryPercent)
        r.commands.push(-1)
        assertEquals(55, r.state.value.batteryPercent, "null from onPush = ignored")
    }

    @Test fun `a push parked under a plain lock holder never overwrites a newer one`() = runTest {
        val r = Rig()
        // The Bose Lab console holds the lock outside run().
        r.mutex.lock()
        r.commands.push(40)
        r.mutex.unlock()
        assertNull(r.state.value.batteryPercent, "still parked")
        r.commands.push(35)
        assertEquals(35, r.state.value.batteryPercent, "the older push went first")
        // And the console drains on its way out.
        r.mutex.lock()
        r.commands.push(30)
        r.commands.drain()
        r.mutex.unlock()
        assertEquals(30, r.state.value.batteryPercent)
    }

    @Test fun `a push during a command is merged after its result, not overwritten by it`() = runTest {
        val r = Rig()
        r.commands.push(70)
        val gate = CompletableDeferred<Unit>()
        val job = async {
            r.commands.run("setVolume") { c, s ->
                assertEquals("link", c)
                gate.await()
                s.copy(volume = 12) // built from the snapshot: battery 70
            }
        }
        runCurrent()
        assertTrue(r.state.value.isBusy)
        r.commands.push(50) // the headset's own push while the command waits for its reply
        assertEquals(70, r.state.value.batteryPercent, "parked, not applied under the command")
        gate.complete(Unit)
        job.await()
        assertEquals(12, r.state.value.volume)
        assertEquals(50, r.state.value.batteryPercent, "the push survives the command's result")
        assertFalse(r.state.value.isBusy)
    }

    @Test fun `a push during a failed command is still applied`() = runTest {
        val r = Rig()
        val gate = CompletableDeferred<Unit>()
        val job = async { runCatching { r.commands.run("fails") { _, _ -> gate.await(); throw HeadphoneCommandException("refused") } } }
        runCurrent()
        r.commands.push(33)
        gate.complete(Unit)
        assertEquals("refused", job.await().exceptionOrNull()?.message)
        assertEquals(33, r.state.value.batteryPercent)
    }

    @Test fun `the result keeps the connection and the busy flag the repository owns`() = runTest {
        val r = Rig()
        r.commands.run("refresh") { _, _ -> HeadphoneState(batteryPercent = 10) }
        assertEquals(ConnectionStatus.CONNECTED, r.state.value.connection.status)
        assertEquals(10, r.state.value.batteryPercent)
        assertTrue(r.state.value.lastUpdatedMillis > 0)
    }

    @Test fun `showBusy false leaves the busy flag alone`() = runTest {
        val r = Rig()
        var busyInside: Boolean? = null
        r.commands.run("pollBattery", showBusy = false) { _, s -> busyInside = r.state.value.isBusy; s }
        assertEquals(false, busyInside)
        r.commands.run("setMode") { _, s -> busyInside = r.state.value.isBusy; s }
        assertEquals(true, busyInside)
    }

    @Test fun `errors pass through, map, or are wrapped with their own message and logged`() = runTest {
        val r = Rig()
        val own = HeadphoneCommandException("own")
        assertSame(own, assertFailsWith<HeadphoneCommandException> { r.commands.run("a") { _, _ -> throw own } })

        val mapped = assertFailsWith<HeadphoneCommandException> { r.commands.run("b") { _, _ -> throw UnsupportedOperationException("x") } }
        assertEquals("mapped: x", mapped.message)

        val wrapped = assertFailsWith<HeadphoneCommandException> { r.commands.run("c") { _, _ -> throw IllegalStateException("boom") } }
        assertEquals("boom", wrapped.message)
        assertIs<IllegalStateException>(wrapped.cause)
        assertEquals("Command failed", assertFailsWith<HeadphoneCommandException> { r.commands.run("d") { _, _ -> throw IllegalStateException() } }.message)
        assertEquals(listOf("W c failed", "W d failed"), r.log.lines, "a mapped or own error is not logged")
        assertFalse(r.state.value.isBusy)
    }

    @Test fun `no link, no command`() = runTest {
        val r = Rig(connected = false)
        var ran = false
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { r.commands.run("x") { _, s -> ran = true; s } }.message)
        assertFalse(ran)
        assertFalse(r.state.value.isBusy)
    }

    @Test fun `reset drops pushes parked for the previous session`() = runTest {
        val r = Rig()
        r.mutex.lock()
        r.commands.push(20)
        r.commands.reset()
        r.mutex.unlock()
        r.commands.run("refresh") { _, s -> s }
        assertNull(r.state.value.batteryPercent)
    }

    @Test fun `a push that throws is logged and ignored`() = runTest {
        val state = MutableStateFlow(HeadphoneState())
        val log = RecordingLog()
        val commands = StateCommands<String, Int>(state, Mutex(), { "link" }, log, onPush = { _, _ -> error("bad frame") })
        commands.push(1)
        assertEquals(HeadphoneState(), state.value)
        assertEquals(listOf("W onUnsolicited failed for 1: bad frame"), log.lines)
    }
}

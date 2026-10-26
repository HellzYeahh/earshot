package dev.earshot.conformance

import dev.earshot.bt.BtLog
import dev.earshot.bt.ConnectionManager
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.JblLog
import dev.earshot.bt.SennheiserLog
import dev.earshot.bt.SonyLog
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.service.ProtocolLog
import dev.earshot.virtual.DemoDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Roadmap F2, phase 1: one contract every driver's virtual device must pass, one row per
 * [DemoDevice] with a wire protocol (the generic AirPods demo sends nothing and has no row).
 *
 *  - C1 connect: a name, a battery in 0..100, mode slots numbered from 0 without repeats and the
 *    current one among them; no modes means the mode UI is off.
 *  - C2 refresh: two refreshes agree, and everything the phone wrote was a read.
 *  - C3 modes: each configured mode switches the unit and reads back; an impossible slot is refused
 *    with a sentence before anything is written, and the link stays up.
 *  - C4 battery: a level the unit pushes reaches the state without a request; where the stack
 *    has no push (a skipped row says why) the on-demand read is what counts, and every row checks it.
 *  - C5 guard: the stack's forbidden writes are refused before the socket, and the link stays up.
 *  - C6 link loss: the far end goes away, the connection closes, the next command says so.
 *  - C7 reconnect: a new connection to the same unit finds the same product in the mode it was
 *    left in, with its pushes renewed (one protocol object across both, as the app keeps one).
 *  - C8 identity: the link factory, the router's name rule and the rig agree on the family, and
 *    the busy message names the maker's app.
 *
 * Session level only: the repositories need an Android Context, so each rig repeats its
 * repository's steps (see [ConformanceRig]); repository-level checks and the router follow A6.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class DriverConformanceTest(private val device: DemoDevice) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun devices(): List<DemoDevice> = DemoDevice.entries.filterNot { it.isGeneric }

        /** A level every unit can report: Bose drains to it, Soundcore's 10 % and 20 % steps both hit it. */
        const val BATTERY = 60

        /** Virtual milliseconds a dropped link gets to close. */
        const val CLOSE_TIMEOUT_MS = 10_000L

        /** Virtual milliseconds a stack gets to fail a command on a closed link (Sony resends for 7.5 s). */
        const val DEAD_CALL_LIMIT_MS = 30_000L
    }

    @Before
    fun quiet() {
        BtLog.sink = { _, _, _ -> }
        SennheiserLog.sink = { _, _, _ -> }
        SonyLog.sink = { _, _, _ -> }
        JblLog.sink = { _, _, _ -> }
        ProtocolLog.sink = { _, _, _ -> }
    }

    /** Build the rig, skip [check] where the stack cannot have it (with the rig's reason), then run [body] in virtual time. */
    private fun conformance(check: Check?, body: suspend TestScope.(ConformanceRig) -> Unit) {
        val scope = TestScope()
        val rig = assertNotNull(rigFor(device, scope), "$device has no conformance rig")
        check?.let { c -> rig.notApplicable[c]?.let { reason -> assumeTrue("$device: $c not applicable: $reason", false) } }
        scope.runTest { body(rig) }
    }

    private suspend fun ConformanceRig.connected(): HeadphoneState {
        connect()
        return settle()
    }

    @Test
    fun `C1 connect reads a coherent state`() = conformance(Check.CONNECT) { rig ->
        val s = rig.connected()
        assertTrue(rig.link.isOpen, "$device: open after connect")
        val name = s.info?.modelName ?: s.info?.productName
        assertFalse(name.isNullOrBlank(), "$device: a model or product name")
        val battery = assertNotNull(s.batteryPercent, "$device: a battery level")
        assertTrue(battery in 0..100, "$device: battery $battery")
        val indices = s.modes.map { it.index }
        assertEquals(indices.distinct(), indices, "$device: mode slots repeat")
        if (indices.isEmpty()) {
            assertFalse(s.capabilities.listeningModes, "$device: no modes, so no mode UI")
        } else {
            assertEquals(0, indices.min(), "$device: slots start at 0")
            assertTrue(s.currentModeIndex in indices, "$device: current mode ${s.currentModeIndex} not in $indices")
        }
    }

    @Test
    fun `C2 refresh is stable and only reads`() = conformance(Check.REFRESH) { rig ->
        rig.connected()
        rig.spy.clear()
        val first = rig.refresh()
        val second = rig.refresh()
        assertEquals(first.copy(lastUpdatedMillis = 0), second.copy(lastUpdatedMillis = 0), "$device: two refreshes disagree")
        val sent = rig.decode(rig.spy.all())
        assertTrue(sent.isNotEmpty(), "$device: a refresh asks the headset something")
        assertTrue(sent.all { it.isRead }, "$device: a refresh wrote ${sent.filterNot { it.isRead }.map { it.label }}")
    }

    @Test
    fun `C3 every mode switches the unit and an impossible one is refused before the wire`() = conformance(Check.MODES) { rig ->
        val s = rig.connected()
        for (index in s.modes.filter { it.isConfigured }.map { it.index }) {
            val after = rig.setMode(index)
            rig.settle()
            assertEquals(index, after.currentModeIndex, "$device: state after setMode($index)")
            assertEquals(index, rig.deviceModeIndex(), "$device: the unit after setMode($index)")
            assertEquals(index, rig.refresh().currentModeIndex, "$device: a refresh after setMode($index)")
        }
        // 99 and -1 exist on no product; with no modes at all, every slot is impossible.
        val impossible = if (s.modes.isEmpty()) listOf(0, 1, 99, -1) else listOf(99, -1)
        for (index in impossible) {
            rig.settle()
            rig.spy.clear()
            val e = assertFailsWith<HeadphoneCommandException>("$device: setMode($index)") { rig.setMode(index) }
            assertFalse(e.message.isNullOrBlank(), "$device: setMode($index) needs a sentence")
            rig.settle()
            assertTrue(rig.spy.all().isEmpty(), "$device: setMode($index) wrote ${rig.decode(rig.spy.all()).map { it.label }}")
            assertTrue(rig.link.isOpen, "$device: a refused mode is not a dead link")
        }
    }

    @Test
    fun `C4 a battery push reaches the state without a request`() = conformance(Check.BATTERY_PUSH) { rig ->
        rig.connected()
        rig.spy.clear()
        assertTrue(rig.pushBattery(BATTERY), "$device: the stack has a battery push (else list it as not applicable)")
        val s = rig.settle()
        assertEquals(BATTERY, s.batteryPercent, "$device: pushed battery")
        val sent = rig.decode(rig.spy.all())
        assertTrue(sent.isEmpty(), "$device: a push needed requests ${sent.map { it.label }}")
    }

    @Test
    fun `C4b the battery read on demand reaches the state and only reads`() = conformance(null) { rig ->
        rig.connected()
        rig.spy.clear()
        val s = rig.pollBattery(BATTERY)
        assertEquals(BATTERY, s.batteryPercent, "$device: polled battery")
        val sent = rig.decode(rig.spy.all())
        assertTrue(sent.isNotEmpty(), "$device: the poll asked the headset")
        assertTrue(sent.all { it.isRead }, "$device: the poll wrote ${sent.filterNot { it.isRead }.map { it.label }}")
    }

    @Test
    fun `C5 the write guard refuses before the socket and keeps the link`() = conformance(Check.GUARD) { rig ->
        rig.connected()
        assertTrue(rig.forbiddenWrites.isNotEmpty(), "$device: name the writes the guard must refuse")
        for ((label, attempt) in rig.forbiddenWrites) {
            rig.settle()
            rig.spy.clear()
            val e = assertFailsWith<HeadphoneCommandException>("$device: $label was not refused") { attempt() }
            assertFalse(e.message.isNullOrBlank(), "$device: $label needs a sentence")
            rig.settle()
            assertTrue(rig.spy.all().isEmpty(), "$device: $label reached the socket: ${rig.decode(rig.spy.all()).map { it.label }}")
            assertTrue(rig.link.isOpen, "$device: refusing $label is not a dead link")
        }
    }

    @Test
    fun `C6 a dropped link closes and the next command says so`() = conformance(Check.LINK_LOSS) { rig ->
        rig.connected()
        val link = rig.link
        rig.dropLink()
        withTimeout(CLOSE_TIMEOUT_MS) { link.awaitClosed() }
        assertFalse(link.isOpen, "$device: closed after the far end went away")
        assertFailsWith<HeadphoneCommandException>("$device: refresh on a dead link") { rig.refresh() }
        assertFailsWith<HeadphoneCommandException>("$device: setMode on a dead link") { rig.setMode(0) }
        // Past the repository's guard: the stack itself must fail on the closed link, in bounded time.
        rig.repositoryGuard = false
        suspend fun failsInTime(what: String, call: suspend () -> Any) {
            val start = testScheduler.currentTime
            val result = runCatching { withTimeout(DEAD_CALL_LIMIT_MS) { call() } }
            assertTrue(result.isFailure, "$device: the stack's $what worked on a dead link")
            assertTrue(testScheduler.currentTime - start < DEAD_CALL_LIMIT_MS, "$device: the stack's $what hung on a dead link")
        }
        failsInTime("refresh") { rig.refresh() }
        failsInTime("setMode") { rig.setMode(0) }
    }

    @Test
    fun `C7 a reconnect finds the same headset in the mode it was left in`() = conformance(Check.RECONNECT) { rig ->
        val first = rig.connected()
        val target = first.modes.filter { it.isConfigured }.map { it.index }.firstOrNull { it != first.currentModeIndex }
        if (target != null) rig.setMode(target)
        rig.settle()
        val before = identity(rig.state)
        val old = rig.link
        rig.dropLink()
        withTimeout(CLOSE_TIMEOUT_MS) { old.awaitClosed() }

        val second = rig.connected()
        assertNotSame(old, rig.link, "$device: a new connection")
        assertTrue(rig.link.isOpen, "$device: open after the reconnect")
        assertEquals(before, identity(second), "$device: the same product after the reconnect")
        if (target != null) {
            assertEquals(target, second.currentModeIndex, "$device: the mode it was left in")
            assertEquals(target, rig.deviceModeIndex(), "$device: the unit kept the mode")
        }
        rig.subscribed()?.let { assertTrue(it, "$device: pushes renewed on the new connection") }
    }

    @Test
    fun `C8 the link factory, the router and the rig agree on the family`() = conformance(Check.IDENTITY) { rig ->
        assertEquals(rig.family, rig.factory.family, "$device: link factory family")
        assertNotEquals(ConnectionManager.GENERIC_COMPETING_APP, rig.factory.competingApp, "$device: the busy message names the maker's app")
        assertEquals(rig.family, DeviceFamily.forName(rig.bluetoothName), "$device: '${rig.bluetoothName}' routes elsewhere")
    }

    private fun identity(s: HeadphoneState) = listOf(
        s.info?.modelName, s.info?.productName, s.info?.firmwareVersion, s.info?.serialNumber,
        s.capabilities.formFactor, s.modes.map { it.index to it.name },
    )
}

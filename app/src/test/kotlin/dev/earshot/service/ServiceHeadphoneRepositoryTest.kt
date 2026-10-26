package dev.earshot.service

import dev.earshot.bt.BmapConnection
import dev.earshot.bt.BmapRequester
import dev.earshot.bt.BtLog
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.DeviceCandidate
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCandidate
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapException
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.service.session.FakeForegroundHost
import dev.earshot.service.session.FakeLinkSource
import dev.earshot.service.session.RecordingLog
import dev.earshot.service.session.SilentTransport
import dev.earshot.service.session.recordEvents
import dev.earshot.virtual.VirtualQcUltra2
import dev.earshot.virtual.VirtualTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Characterization of [ServiceHeadphoneRepository], the Bose repository, written against the 0.75
 * logic before it moved onto the shared session base, and kept green through the move. It drives
 * the repository itself (a fake link source, the real [BmapConnection] over a [VirtualTransport] to
 * a [VirtualQcUltra2], the real [QcUltra2Protocol] behind a thin hook), because every Bose
 * end-to-end test sits one layer below and would stay green if push deferral, the busy flag, the
 * silent-link close or the Disconnected edge broke.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceHeadphoneRepositoryTest {

    @Before
    fun quiet() {
        ProtocolLog.sink = { _, _, _ -> }
        BtLog.sink = { _, _, _ -> }
    }

    /** The real protocol, with hooks the tests use to act or fail at a chosen point. */
    private class HookedProtocol(val real: QcUltra2Protocol) : HeadphoneProtocol by real {
        var beforeMultipoint: suspend () -> Unit = {}
        var refreshFailure: Exception? = null
        var setModeFailure: Exception? = null
        var printFailure: Exception? = null
        var busyProbe: () -> Boolean = { false }
        val busyDuringPoll = mutableListOf<Boolean>()

        override suspend fun refresh(c: BmapRequester, current: HeadphoneState): HeadphoneState {
            refreshFailure?.let { refreshFailure = null; throw it }
            return real.refresh(c, current)
        }

        override suspend fun setMode(c: BmapRequester, current: HeadphoneState, index: Int): HeadphoneState {
            setModeFailure?.let { throw it }
            return real.setMode(c, current, index)
        }

        override suspend fun setMultipoint(c: BmapRequester, current: HeadphoneState, enabled: Boolean): HeadphoneState {
            beforeMultipoint()
            return real.setMultipoint(c, current, enabled)
        }

        override suspend fun pollBattery(c: BmapRequester, current: HeadphoneState): HeadphoneState {
            busyDuringPoll += busyProbe()
            return real.pollBattery(c, current)
        }

        override suspend fun printConsole(c: BmapRequester, command: String): String {
            printFailure?.let { throw it }
            return real.printConsole(c, command)
        }
    }

    private class Rig(
        val device: VirtualQcUltra2,
        val conn: BmapConnection,
        val link: FakeLinkSource<BmapConnection>,
        val foreground: FakeForegroundHost,
        val protocol: HookedProtocol,
        val log: RecordingLog,
        val repo: ServiceHeadphoneRepository,
        val events: List<HeadphoneEvent>,
    )

    private fun TestScope.rig(
        device: VirtualQcUltra2 = VirtualQcUltra2(clock = { testScheduler.currentTime }),
        transport: ByteTransport = VirtualTransport(device, random = Random(7)),
        candidates: List<DeviceCandidate> = emptyList(),
    ): Rig {
        val conn = BmapConnection(transport, backgroundScope, minWriteGapMs = 0, clock = { testScheduler.currentTime })
        val link = FakeLinkSource<BmapConnection>(deviceName = "Bose QC Ultra Headphones", deviceAddress = "68:F2:1F:12:34:56")
        val foreground = FakeForegroundHost()
        val protocol = HookedProtocol(QcUltra2Protocol(localAddress = { "C4:0F:08:11:22:33" }, clock = { testScheduler.currentTime }))
        val log = RecordingLog()
        val repo = ServiceHeadphoneRepository(protocol, link, { candidates }, foreground, backgroundScope, log)
        protocol.busyProbe = { repo.state.value.isBusy }
        return Rig(device, conn, link, foreground, protocol, log, repo, recordEvents(repo))
    }

    private fun TestScope.waitFor(limitMs: Long = 300_000, condition: () -> Boolean) {
        var waited = 0L
        while (!condition() && waited < limitMs) { advanceTimeBy(100); runCurrent(); waited += 100 }
        assertTrue(condition(), "condition not reached within $limitMs ms")
    }

    private fun TestScope.connected(r: Rig = rig()): Rig {
        r.link.open(r.conn)
        waitFor { HeadphoneEvent.Connected in r.events }
        return r
    }

    // ---- Connect --------------------------------------------------------------------------------

    @Test fun `connect subscribes, reads everything, emits Connected once, and with pushes on polls nothing`() = runTest {
        val r = connected()
        val s = r.repo.state.value
        assertEquals(ConnectionStatus.CONNECTED, s.connection.status)
        assertTrue(s.livePushEnabled)
        assertEquals(72, s.batteryPercent)
        assertEquals("Bose QC Ultra 2 HP", s.info?.productName)
        assertFalse(s.isBusy)
        assertEquals(listOf(HeadphoneEvent.Connected), r.events)
        assertTrue(r.device.notifyBlocks.isNotEmpty(), "[9.2] subscribed")

        val batteryReads = r.device.requestsTo(QcUltra2.BATTERY)
        advanceTimeBy(130_000); runCurrent()
        assertEquals(batteryReads, r.device.requestsTo(QcUltra2.BATTERY), "pushes on: no battery poll")
        assertTrue(r.protocol.busyDuringPoll.isEmpty())
        r.conn.close()
    }

    @Test fun `without the push subscription the battery is polled every minute, busy flag up`() = runTest {
        // The unit loses 1 % every 45 s (virtual time), so only a fresh read shows the new figure.
        val device = VirtualQcUltra2(clock = { testScheduler.currentTime }, batteryDrainIntervalMs = 45_000)
            .apply { silentFunctions = setOf(QcUltra2.NOTIFY_BY_FBLOCK) }
        val r = connected(rig(device))
        assertFalse(r.repo.state.value.livePushEnabled)
        assertTrue(r.events.none { it is HeadphoneEvent.Error }, "a refused [9.2] is not an error: ${r.events}")
        val shown = r.repo.state.value.batteryPercent

        val reads = device.requestsTo(QcUltra2.BATTERY)
        advanceTimeBy(55_000); runCurrent()
        assertEquals(reads, device.requestsTo(QcUltra2.BATTERY))
        assertEquals(shown, r.repo.state.value.batteryPercent, "nothing pushed, nothing polled yet")
        waitFor(20_000) { device.requestsTo(QcUltra2.BATTERY) == reads + 1 && !r.repo.state.value.isBusy }
        assertTrue(device.batteryPercent < shown!!, "the unit drained meanwhile")
        assertEquals(device.batteryPercent, r.repo.state.value.batteryPercent)
        assertEquals(listOf(true), r.protocol.busyDuringPoll, "the poll goes through command(): the busy flag is up")

        waitFor(70_000) { device.requestsTo(QcUltra2.BATTERY) == reads + 2 }
        r.conn.close()
    }

    @Test fun `a refresh that fails on a live link is an Error, and the session still connects`() = runTest {
        val r = rig()
        r.protocol.refreshFailure = HeadphoneCommandException("boom")
        connected(r)
        assertEquals(2, r.events.size, "${r.events}")
        assertEquals("Connected, but reading settings failed: boom", assertIs<HeadphoneEvent.Error>(r.events[0]).message)
        assertEquals(HeadphoneEvent.Connected, r.events[1])
        assertTrue(r.conn.isOpen)
        r.conn.close()
    }

    @Test fun `a silent link is closed during the push subscription and never reported connected`() = runTest {
        val transport = SilentTransport()
        val r = rig(transport = transport)
        r.link.open(r.conn)
        waitFor { !r.conn.isOpen }
        advanceTimeBy(1_000); runCurrent()
        assertFalse(HeadphoneEvent.Connected in r.events, "${r.events}")
        assertTrue(r.events.none { it is HeadphoneEvent.Error }, "a busy channel is the manager's message: ${r.events}")
        assertTrue(r.log.lines.any { it.startsWith("W push subscription failed: ") }, "${r.log.lines}")
        assertNotNull(r.conn.awaitClosed(), "closed as unresponsive, not locally")
    }

    // ---- Pushes ---------------------------------------------------------------------------------

    @Test fun `a push without a command applies at once`() = runTest {
        val r = connected()
        assertEquals(22, r.repo.state.value.volume)
        r.device.swipeVolume(2)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(24, r.repo.state.value.volume)
        r.conn.close()
    }

    @Test fun `a push during a command is merged after its result instead of being overwritten`() = runTest {
        val r = connected()
        assertEquals(true, r.repo.state.value.multipointEnabled)
        r.protocol.beforeMultipoint = {
            r.device.swipeVolume(3) // the user swipes while the command runs
            delay(1_000)            // the push arrives (150 ms + a tick) while the command holds the lock
            assertEquals(22, r.repo.state.value.volume, "parked, not applied under the command")
        }
        r.repo.setMultipoint(false)
        assertEquals(false, r.repo.state.value.multipointEnabled)
        assertEquals(25, r.repo.state.value.volume, "the push survived the command's snapshot")
        assertFalse(r.repo.state.value.isBusy)
        r.conn.close()
    }

    // ---- Commands -------------------------------------------------------------------------------

    @Test fun `commands reach the headphones with the busy flag up and down again`() = runTest {
        val r = connected()
        r.repo.setMode(1)
        assertEquals(1, r.device.currentMode)
        assertEquals(1, r.repo.state.value.currentModeIndex)
        assertFalse(r.repo.state.value.isBusy)
        r.repo.refresh()
        assertNotNull(r.repo.labProfile())
        r.conn.close()
    }

    @Test fun `errors - a device ERROR maps, anything else is wrapped and logged, own errors pass`() = runTest {
        val r = connected()
        r.protocol.setModeFailure = BmapException(QcUltra2.CURRENT_MODE, BmapError.OP_NOT_SUPPORTED, 5)
        val mapped = assertFailsWith<HeadphoneCommandException> { r.repo.setMode(1) }
        assertEquals(HeadphoneCommandException.FIRMWARE_UNSUPPORTED_MESSAGE, mapped.message)
        assertTrue(mapped.notSupportedByFirmware)

        r.protocol.setModeFailure = IllegalStateException("boom")
        val wrapped = assertFailsWith<HeadphoneCommandException> { r.repo.setMode(1) }
        assertEquals("boom", wrapped.message)
        assertIs<IllegalStateException>(wrapped.cause)

        val own = HeadphoneCommandException("refused")
        r.protocol.setModeFailure = own
        assertSame(own, assertFailsWith<HeadphoneCommandException> { r.repo.setMode(1) })

        assertEquals(listOf("W setMode failed"), r.log.lines.filter { it.contains("setMode") }, "only the wrapped one is logged")
        assertFalse(r.repo.state.value.isBusy)

        r.protocol.printFailure = BmapException(QcUltra2.CURRENT_MODE, BmapError.OP_NOT_SUPPORTED, 5)
        assertEquals(HeadphoneCommandException.FIRMWARE_UNSUPPORTED_MESSAGE, assertFailsWith<HeadphoneCommandException> { r.repo.printConsole("output") }.message)
        r.conn.close()
    }

    @Test fun `pairing mode says so`() = runTest {
        val r = connected()
        r.repo.enterPairingMode()
        runCurrent()
        assertEquals(HeadphoneEvent.Info("Pairing mode on"), r.events.last())
        r.conn.close()
    }

    @Test fun `power off disconnects on real headphones, not in the demo`() = runTest {
        val r = connected()
        r.repo.powerOff()
        runCurrent()
        assertEquals(1, r.link.disconnects)
        assertEquals(1, r.foreground.stops)

        val demo = rig()
        demo.link.isDemoSession = true
        connected(demo)
        demo.repo.powerOff()
        runCurrent()
        assertEquals(0, demo.link.disconnects, "the virtual unit comes back by itself")
        assertEquals(0, demo.foreground.stops)
    }

    // ---- Link edges and guards --------------------------------------------------------------------

    @Test fun `the link going drops the data and fires Disconnected once, then commands are refused`() = runTest {
        val r = connected()
        r.conn.close()
        r.link.drop()
        runCurrent()
        assertEquals(HeadphoneState(connection = r.link.state.value), r.repo.state.value)
        assertEquals(listOf(HeadphoneEvent.Connected, HeadphoneEvent.Disconnected), r.events)
        r.link.status(ConnectionStatus.CONNECTING)
        runCurrent()
        r.link.status(ConnectionStatus.DISCONNECTED)
        runCurrent()
        assertEquals(1, r.events.count { it == HeadphoneEvent.Disconnected })

        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { r.repo.setMode(0) }.message)
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { r.repo.printConsole("output") }.message)
        assertNull(r.repo.labProfile())
    }

    @Test fun `the foreground service and the picker follow the shared rules`() = runTest {
        val r = rig(candidates = listOf(DeviceCandidate("68:F2:1F:12:34:56", "Bose QC Ultra Headphones", looksLikeBose = true, isAudioDevice = true, isSaved = false)))
        r.foreground.allowed = false
        r.repo.connect()
        assertEquals(0, r.foreground.starts)
        r.foreground.allowed = true
        r.repo.connect()
        assertEquals(1, r.foreground.starts)
        assertEquals(2, r.link.connects)
        r.foreground.allowed = false
        r.repo.chooseDevice("68:F2:1F:12:34:56", "Bose QC Ultra Headphones")
        assertEquals(2, r.foreground.starts)
        assertEquals(listOf<Pair<String, String?>>("68:F2:1F:12:34:56" to "Bose QC Ultra Headphones"), r.link.chosen)
        r.repo.releaseConnection()
        assertEquals(0, r.foreground.stops)
        r.repo.disconnect()
        assertEquals(1, r.foreground.stops)
        assertEquals(2, r.link.disconnects)
        assertEquals(
            listOf(HeadphoneCandidate("68:F2:1F:12:34:56", "Bose QC Ultra Headphones", looksLikeBose = true, isAudioDevice = true)),
            r.repo.deviceCandidates(),
        )
    }
}

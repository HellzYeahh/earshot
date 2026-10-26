package dev.earshot.service.session

import dev.earshot.bt.DeviceCandidate
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCandidate
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shared session base on its own: state mapping, the Disconnected edge, one session per link
 * with its end, the not-connected guard, the poll, the foreground-service rules and the
 * "not available" answers. Each vendor's own repository test covers its session body.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryTest {

    private class TestRepository(
        link: FakeLinkSource<FakeLink>,
        foreground: ForegroundHost,
        scope: CoroutineScope,
        log: SessionLog,
        notConnected: String = SessionRepository.NOT_CONNECTED,
        candidates: List<DeviceCandidate> = emptyList(),
        private val pollMs: Long = 1_000L,
        private val message: ((String) -> String)? = null,
    ) : SessionRepository<FakeLink>(link, { candidates }, foreground, scope, log, notConnected) {
        val sessions = mutableListOf<FakeLink>()
        val ended = mutableListOf<FakeLink>()
        var polls = 0
        var failPolls = false
        var stopPolling = false

        init { start() }

        fun startAgain() = start()

        override suspend fun CoroutineScope.runSession(conn: FakeLink) {
            sessions += conn
            mutableEvents.tryEmit(HeadphoneEvent.Connected)
            pollWhileOpen(conn, pollMs, stopWhen = { stopPolling }) {
                polls++
                if (failPolls) throw IllegalStateException("no answer")
            }
        }

        override fun onSessionEnd(conn: FakeLink) { ended += conn }

        fun setBattery(percent: Int) = mutableState.update { it.copy(batteryPercent = percent) }
        fun link(checkOpen: Boolean = true): FakeLink = requireConnection(checkOpen)

        override fun unsupportedMessage(feature: String): String = message?.invoke(feature) ?: super.unsupportedMessage(feature)
        override suspend fun refresh() { requireConnection() }
        override suspend fun setMode(index: Int) { requireConnection() }
        override suspend fun setNoise(settings: NoiseSettings) { requireConnection() }
    }

    private class Rig(val link: FakeLinkSource<FakeLink>, val foreground: FakeForegroundHost, val log: RecordingLog, val repo: TestRepository)

    private fun TestScope.rig(
        notConnected: String = SessionRepository.NOT_CONNECTED,
        candidates: List<DeviceCandidate> = emptyList(),
        message: ((String) -> String)? = null,
    ): Rig {
        val link = FakeLinkSource<FakeLink>()
        val foreground = FakeForegroundHost()
        val log = RecordingLog()
        val repo = TestRepository(link, foreground, backgroundScope, log, notConnected, candidates, message = message)
        runCurrent()
        return Rig(link, foreground, log, repo)
    }

    @Test fun `connection state flows into the state, data is dropped only on the CONNECTED edge, Disconnected fires once`() = runTest {
        val r = rig()
        val events = recordEvents(r.repo)

        r.link.status(ConnectionStatus.CONNECTING)
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTING, r.repo.state.value.connection.status)
        r.repo.setBattery(40)
        r.link.status(ConnectionStatus.DISCONNECTED)
        runCurrent()
        assertEquals(40, r.repo.state.value.batteryPercent, "never connected: nothing stale to drop")
        assertTrue(events.isEmpty())

        val conn = FakeLink()
        r.link.open(conn)
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, r.repo.state.value.connection.status)
        assertEquals("Test headset", r.repo.state.value.connection.deviceName)
        r.repo.setBattery(70)

        r.link.drop()
        runCurrent()
        assertEquals(HeadphoneState(connection = r.link.state.value), r.repo.state.value, "stale headset data is gone")
        assertNull(r.repo.state.value.batteryPercent)
        assertEquals(listOf(HeadphoneEvent.Connected, HeadphoneEvent.Disconnected), events)

        r.link.status(ConnectionStatus.CONNECTING)
        runCurrent()
        r.link.status(ConnectionStatus.DISCONNECTED)
        runCurrent()
        assertEquals(1, events.count { it == HeadphoneEvent.Disconnected }, "only the CONNECTED edge counts")
    }

    @Test fun `one session per open link, ended before the next one starts and when the link goes`() = runTest {
        val r = rig()
        val first = FakeLink()
        r.link.open(first)
        runCurrent()
        assertEquals(listOf(first), r.repo.sessions)
        assertTrue(r.repo.ended.isEmpty(), "the session lives while its poll runs")

        val second = FakeLink()
        r.link.open(second)
        runCurrent()
        assertEquals(listOf(first, second), r.repo.sessions)
        assertEquals(listOf(first), r.repo.ended)

        r.link.drop()
        runCurrent()
        assertEquals(listOf(first, second), r.repo.ended)
        assertEquals(2, r.repo.sessions.size, "no session without a link")
    }

    @Test fun `commands need an open link, with the driver's own wording`() = runTest {
        val r = rig(notConnected = "Earbuds not connected")
        assertEquals("Earbuds not connected", assertFailsWith<HeadphoneCommandException> { r.repo.refresh() }.message)

        val conn = FakeLink()
        r.link.open(conn)
        runCurrent()
        r.repo.refresh()
        assertSame(conn, r.repo.link())

        conn.close()
        assertFailsWith<HeadphoneCommandException> { r.repo.setMode(1) }
        assertSame(conn, r.repo.link(checkOpen = false), "a driver that never checked isOpen gets the closed link")
        assertEquals(SessionRepository.NOT_CONNECTED, assertFailsWith<HeadphoneCommandException> { rig().repo.setNoise(NoiseSettings()) }.message)
    }

    @Test fun `the poll runs every period while the link is open, survives a failed read and stops when the link closes`() = runTest {
        val r = rig()
        val conn = FakeLink()
        r.link.open(conn)
        runCurrent()
        advanceTimeBy(999); runCurrent()
        assertEquals(0, r.repo.polls)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, r.repo.polls)

        r.repo.failPolls = true
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, r.repo.polls)
        assertEquals(listOf("D battery poll failed: no answer"), r.log.lines)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(3, r.repo.polls, "a failed read does not end the poll")

        conn.close()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(3, r.repo.polls)
        assertEquals(listOf(conn), r.repo.ended, "the poll was the last child: the session is over")
    }

    @Test fun `stopWhen ends the poll at the next tick`() = runTest {
        val r = rig()
        r.link.open(FakeLink())
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, r.repo.polls)
        r.repo.stopPolling = true
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, r.repo.polls)
    }

    @Test fun `the foreground service starts on connect only with the permission, on a device choice always, and stops on disconnect`() = runTest {
        val r = rig()
        r.foreground.allowed = false
        r.repo.connect()
        assertEquals(0, r.foreground.starts)
        assertEquals(1, r.link.connects)

        r.foreground.allowed = true
        r.repo.connect()
        assertEquals(1, r.foreground.starts)
        assertEquals(2, r.link.connects)

        r.foreground.allowed = false
        r.repo.chooseDevice("AA:BB:CC:DD:EE:FF", "Picked")
        assertEquals(2, r.foreground.starts, "the user picked a device: the service starts regardless")
        assertEquals(listOf<Pair<String, String?>>("AA:BB:CC:DD:EE:FF" to "Picked"), r.link.chosen)

        r.repo.releaseConnection()
        assertEquals(1, r.link.disconnects)
        assertEquals(0, r.foreground.stops, "another stack takes over and keeps the service")

        r.repo.disconnect()
        assertEquals(2, r.link.disconnects)
        assertEquals(1, r.foreground.stops)
    }

    @Test fun `candidates map one to one`() = runTest {
        val r = rig(candidates = listOf(DeviceCandidate("AA:BB:CC:DD:EE:FF", "Bose QC Ultra", looksLikeBose = true, isAudioDevice = true, isSaved = true)))
        assertEquals(listOf(HeadphoneCandidate("AA:BB:CC:DD:EE:FF", "Bose QC Ultra", looksLikeBose = true, isAudioDevice = true)), r.repo.deviceCandidates())
    }

    @Test fun `what a headset lacks is refused with the driver's sentence`() = runTest {
        val crusherStyle = rig()
        assertEquals("Custom modes isn't available on these headphones", assertFailsWith<HeadphoneCommandException> { crusherStyle.repo.saveCustomMode(4, "x", NoiseSettings()) }.message)
        assertEquals("Power off isn't available on these headphones", assertFailsWith<HeadphoneCommandException> { crusherStyle.repo.powerOff() }.message)
        assertEquals("Renaming isn't available on these headphones", assertFailsWith<HeadphoneCommandException> { crusherStyle.repo.setDeviceName("x") }.message)

        val oneSentence = rig(message = { "Not on this driver" })
        assertEquals("Not on this driver", assertFailsWith<HeadphoneCommandException> { oneSentence.repo.setMultipoint(true) }.message)
        // Members with a default in HeadphoneRepository keep it.
        assertEquals("Not supported by this connection", assertFailsWith<HeadphoneCommandException> { oneSentence.repo.setAutoOff(20) }.message)
    }

    @Test fun `start runs once`() = runTest {
        assertFailsWith<IllegalStateException> { rig().repo.startAgain() }
    }
}

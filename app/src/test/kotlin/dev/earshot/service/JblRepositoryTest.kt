package dev.earshot.service

import dev.earshot.bt.BtLog
import dev.earshot.bt.JblConnection
import dev.earshot.bt.JblLog
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.NoiseSettings
import dev.earshot.protocol.jbl.JblCommands as C
import dev.earshot.protocol.jbl.JblReads
import dev.earshot.protocol.jbl.JblWriteGuard
import dev.earshot.service.session.FakeForegroundHost
import dev.earshot.service.session.FakeLinkSource
import dev.earshot.service.session.SessionRepository
import dev.earshot.service.session.recordEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [JblHeadphoneRepository] itself, over a fake link source and the real [JblConnection] to a
 * scripted Wave Buds 2: the session must stay usable for the whole connection, and a failed command
 * must leave the screen showing what the headset reports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JblRepositoryTest {

    @Before
    fun quiet() {
        JblLog.sink = { _, _, _ -> }
        BtLog.sink = { _, _, _ -> }
    }

    private class Rig(val unit: ScriptedJbl, val link: FakeLinkSource<JblConnection>, val conn: JblConnection, val repo: JblHeadphoneRepository)

    private suspend fun TestScope.connected(
        deviceName: String = "JBL Wave Buds 2",
        greet: Boolean = true,
        configure: ScriptedJbl.() -> Unit = {},
    ): Pair<Rig, List<HeadphoneEvent>> {
        val unit = ScriptedJbl({ testScheduler.currentTime }, backgroundScope).apply(configure)
        if (greet) unit.greet()
        val link = FakeLinkSource<JblConnection>(deviceName = deviceName)
        val repo = JblHeadphoneRepository(link, { emptyList() }, FakeForegroundHost(), backgroundScope)
        val events = recordEvents(repo)
        val conn = JblConnection(unit, backgroundScope, clock = { testScheduler.currentTime })
        link.open(conn)
        var waited = 0
        while (HeadphoneEvent.Connected !in events && waited < 60_000) { advanceTimeBy(100); runCurrent(); waited += 100 }
        assertTrue(HeadphoneEvent.Connected in events, "the session connected")
        return Rig(unit, link, conn, repo) to events
    }

    @Test fun `connect publishes the headset's state and Connected`() = runTest {
        val (r, events) = connected()
        val s = r.repo.state.value
        assertEquals(ConnectionStatus.CONNECTED, s.connection.status)
        assertEquals("JBL Wave Buds 2", s.info?.productName)
        assertEquals("2.7.0", s.info?.firmwareVersion)
        assertEquals(listOf(0, 1, 2, 3), s.modes.map { it.index })
        assertEquals(JblStateMapping.MODE_AMBIENT, s.currentModeIndex)
        assertEquals(100, s.batteryPercent)
        assertEquals(listOf(JblStateMapping.EQ_PRESET, JblStateMapping.VOICE_PROMPTS), s.vendorControls.map { it.id })
        assertTrue(s.capabilities.listeningModes)
        assertTrue(s.capabilities.supportNote!!.startsWith("Experimental:"))
        assertFalse(s.isBusy)
        assertTrue(events.none { it is HeadphoneEvent.Error }, "init read everything: $events")
        assertTrue(r.unit.written.all { JblWriteGuard.isAllowed(it) })
        r.conn.close()
    }

    @Test fun `commands, refresh and the Lab work after Connected`() = runTest {
        val (r, events) = connected { answersLevel = true }
        assertEquals(JblStateMapping.ANC_LEVEL, r.repo.state.value.vendorControls.first().id, "the level answered here")

        r.repo.setMode(JblStateMapping.MODE_ANC)
        runCurrent()
        assertEquals(C.WIRE_ANC, r.unit.active)
        assertEquals(JblStateMapping.MODE_ANC, r.repo.state.value.currentModeIndex)
        assertFalse(r.repo.state.value.isBusy)

        r.repo.setVendorControl(JblStateMapping.EQ_PRESET, 3)
        assertEquals(3, r.unit.eqCategory)
        assertEquals(3, r.repo.state.value.vendorControls.first { it.id == JblStateMapping.EQ_PRESET }.value)

        r.repo.setVendorControl(JblStateMapping.ANC_LEVEL, 6)
        assertEquals(6, r.repo.state.value.vendorControls.first { it.id == JblStateMapping.ANC_LEVEL }.value)

        r.unit.tlv[C.FID_BATTERY_LEFT] = listOf(0, 55)
        r.repo.refresh()
        assertEquals(55, r.repo.state.value.batteryPercent)
        assertTrue(events.none { it is HeadphoneEvent.Error }, "$events")

        val profile = assertNotNull(r.repo.labProfile(), "the Lab sees the session")
        assertEquals("JBL H-AA", profile.stack)
        val readings = r.repo.scanLab(listOf(profile.groups.first().id), deep = false).toList()
        assertTrue(readings.isNotEmpty())
        assertTrue(r.unit.written.all { JblWriteGuard.isAllowed(it) })
        r.conn.close()
    }

    @Test fun `the Lab sends each documented read once, as a read, and reports what stayed silent`() = runTest {
        val (r, _) = connected()
        r.unit.written.clear()
        val profile = assertNotNull(r.repo.labProfile())
        assertEquals(listOf("Device", "Sound", "Settings"), profile.groups.map { it.name })
        assertEquals(JblReads.ALL.size, profile.groups.sumOf { it.readCount })
        assertEquals(JblReads.ALL.size, profile.deepReadCount)
        assertTrue("never list" in profile.safetyNote)

        val readings = r.repo.scanLab(emptyList(), deep = true).toList()
        assertEquals(JblReads.ALL.size, readings.size)
        assertEquals(JblReads.ALL.map { it.frame }, r.unit.written, "each read went out once, in the list's order")
        assertTrue(r.unit.written.all { JblWriteGuard.isRead(it) })

        val mode = readings.first { it.knownName == "Listening mode" }
        assertEquals(0x9111, mode.function)
        assertEquals("91 11", mode.address)
        assertEquals("12010002010300", mode.frames.single().payloadHex)
        assertFalse(mode.frames.single().isError)
        val level = readings.first { it.knownName == "Noise-cancelling level" }
        assertTrue(level.frames.single().isError, "the Wave Buds 2 never answers the level read")
        assertEquals("no answer", level.frames.single().errorName)
        assertEquals("Sound", level.groupName)
        r.conn.close()
    }

    @Test fun `a Lab scan of one group sends only that group`() = runTest {
        val (r, _) = connected()
        r.unit.written.clear()
        val readings = r.repo.scanLab(listOf(JblReads.GROUP_SETTINGS), deep = false).toList()
        val expected = JblReads.ALL.filter { it.group == JblReads.GROUP_SETTINGS }
        assertEquals(expected.map { it.name }, readings.map { it.knownName })
        assertEquals(expected.map { it.frame }, r.unit.written)
        r.conn.close()
    }

    @Test fun `the poll keeps the session`() = runTest {
        val (r, _) = connected()
        r.unit.written.clear()
        r.unit.tlv[C.FID_BATTERY_LEFT] = listOf(0, 61)
        advanceTimeBy(5 * 60_000L + 1_000); runCurrent()
        assertTrue(r.unit.written.any { it.command == C.INFO }, "the battery poll ran")
        assertEquals(61, r.repo.state.value.batteryPercent)
        r.repo.setMode(JblStateMapping.MODE_ANC)
        runCurrent()
        assertEquals(C.WIRE_ANC, r.unit.active)
        r.conn.close()
    }

    @Test fun `a push reaches the state without a request`() = runTest {
        val (r, _) = connected()
        r.unit.written.clear()
        r.unit.push(JblCaptured.modeStatus(listOf(1, 2, 3), 3))
        runCurrent()
        assertEquals(JblStateMapping.MODE_TALKTHRU, r.repo.state.value.currentModeIndex)
        assertTrue(r.unit.written.isEmpty())
        r.conn.close()
    }

    @Test fun `a refused mode throws and the screen shows what the headset reports`() = runTest {
        val (r, _) = connected { declineModes = true }
        val e = assertFailsWith<HeadphoneCommandException> { r.repo.setMode(JblStateMapping.MODE_OFF) }
        assertTrue(e.message!!.startsWith("The headphones stayed in Ambient Aware"), e.message)
        assertEquals(JblStateMapping.MODE_AMBIENT, r.repo.state.value.currentModeIndex)
        assertFalse(r.repo.state.value.isBusy, "the busy flag came down")
        assertTrue(r.conn.isOpen)
        // An impossible slot is refused before anything is written.
        r.unit.written.clear()
        assertFailsWith<HeadphoneCommandException> { r.repo.setMode(99) }
        assertTrue(r.unit.written.isEmpty())
        r.conn.close()
    }

    @Test fun `a headset that never says who it is still connects, with an error to read`() = runTest {
        val (r, events) = connected(greet = false) { answersDeviceInfo = false }
        val error = events.filterIsInstance<HeadphoneEvent.Error>().single()
        assertEquals("Connected, but reading the headphones failed: The headphones didn't answer", error.message)
        assertEquals(ConnectionStatus.CONNECTED, r.repo.state.value.connection.status)
        assertTrue(r.repo.state.value.modes.isEmpty())
        r.conn.close()
    }

    @Test fun `once the link goes, state is cleared and commands and the Lab say not connected`() = runTest {
        val (r, events) = connected()
        r.conn.close()
        r.link.drop()
        runCurrent()
        assertTrue(HeadphoneEvent.Disconnected in events)
        assertEquals(ConnectionStatus.DISCONNECTED, r.repo.state.value.connection.status)
        assertTrue(r.repo.state.value.modes.isEmpty())
        assertNull(r.repo.state.value.batteryPercent)
        assertEquals(SessionRepository.NOT_CONNECTED, assertFailsWith<HeadphoneCommandException> { r.repo.setMode(1) }.message)
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { r.repo.refresh() }.message)
        assertFailsWith<HeadphoneCommandException> { r.repo.setVendorControl(JblStateMapping.VOICE_PROMPTS, 0) }
        assertNull(r.repo.labProfile())
        assertFailsWith<HeadphoneCommandException> { r.repo.scanLab(listOf(1), deep = false).toList() }
    }

    @Test fun `commands before any link say not connected`() = runTest {
        val link = FakeLinkSource<JblConnection>()
        val repo = JblHeadphoneRepository(link, { emptyList() }, FakeForegroundHost(), backgroundScope)
        runCurrent()
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { repo.setMode(0) }.message)
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { repo.setVendorControl(JblStateMapping.ANC_LEVEL, 3) }.message)
        assertNull(repo.labProfile())
    }

    @Test fun `connect and disconnect go to the link and the service`() = runTest {
        val link = FakeLinkSource<JblConnection>()
        val foreground = FakeForegroundHost()
        val repo = JblHeadphoneRepository(link, { emptyList() }, foreground, backgroundScope)
        repo.connect()
        assertEquals(1, link.connects)
        assertEquals(1, foreground.starts)
        repo.disconnect()
        assertEquals(1, link.disconnects)
        assertEquals(1, foreground.stops)
    }

    @Test fun `what JBL headsets lack gets the driver's sentence`() = runTest {
        val (r, _) = connected()
        val expected = JblHeadphoneRepository.UNSUPPORTED
        assertEquals(expected, assertFailsWith<HeadphoneCommandException> { r.repo.powerOff() }.message)
        assertEquals(expected, assertFailsWith<HeadphoneCommandException> { r.repo.setDeviceName("x") }.message)
        assertEquals(expected, assertFailsWith<HeadphoneCommandException> { r.repo.setFavorites(setOf(0)) }.message)
        assertEquals(expected, assertFailsWith<HeadphoneCommandException> { r.repo.setNoise(NoiseSettings(cancellingLevel = 5, ancEnabled = true)) }.message)
        assertEquals(expected, assertFailsWith<HeadphoneCommandException> { r.repo.setAutoAnswer(true) }.message)
        assertEquals(expected, assertFailsWith<HeadphoneCommandException> { r.repo.enterPairingMode() }.message)
        assertEquals("Earshot's JBL driver (Experimental) doesn't support that setting yet", expected)
        // Refused in the driver, so nothing went out beyond the connect reads.
        assertTrue(r.unit.written.all { JblWriteGuard.isAllowed(it) })
        r.conn.close()
    }
}

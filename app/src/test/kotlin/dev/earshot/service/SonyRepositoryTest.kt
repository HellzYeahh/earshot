package dev.earshot.service

import dev.earshot.bt.BtLog
import dev.earshot.bt.SonyConnection
import dev.earshot.bt.SonyLinkFactory
import dev.earshot.bt.SonyLog
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneEvent
import dev.earshot.service.session.FakeForegroundHost
import dev.earshot.service.session.FakeLinkSource
import dev.earshot.service.session.SessionRepository
import dev.earshot.service.session.recordEvents
import dev.earshot.virtual.VirtualSony
import dev.earshot.virtual.VirtualSonyTransport
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
 * [SonyHeadphoneRepository] itself, over a fake link source and the real [SonyConnection] to a
 * [VirtualSony]: the session must stay usable for the whole connection. In 0.75 it was cleared as
 * soon as the poll was launched, so every command after Connected said "Headphones not connected"
 * and nothing below the repository noticed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SonyRepositoryTest {

    @Before
    fun quiet() {
        SonyLog.sink = { _, _, _ -> }
        BtLog.sink = { _, _, _ -> }
    }

    private class Rig(val unit: VirtualSony, val link: FakeLinkSource<SonyConnection>, val conn: SonyConnection, val repo: SonyHeadphoneRepository)

    /** Opened the way the manager opens it: the factory's socket plus its probe, then CONNECTED. */
    private suspend fun TestScope.connected(unit: VirtualSony = VirtualSony()): Pair<Rig, List<HeadphoneEvent>> {
        val link = FakeLinkSource<SonyConnection>(deviceName = "WH-1000XM5")
        val repo = SonyHeadphoneRepository(link, { emptyList() }, FakeForegroundHost(), backgroundScope, clock = { testScheduler.currentTime })
        val events = recordEvents(repo)
        val factory = SonyLinkFactory()
        val conn = factory.adopt(VirtualSonyTransport(unit), backgroundScope)
        factory.probe(conn)
        link.open(conn)
        var waited = 0
        while (HeadphoneEvent.Connected !in events && waited < 60_000) { advanceTimeBy(100); runCurrent(); waited += 100 }
        assertTrue(HeadphoneEvent.Connected in events, "the session connected")
        return Rig(unit, link, conn, repo) to events
    }

    @Test fun `commands, refresh and the Lab work after Connected`() = runTest {
        val (r, events) = connected()
        assertEquals(ConnectionStatus.CONNECTED, r.repo.state.value.connection.status)
        assertEquals(70, r.repo.state.value.batteryPercent)
        assertEquals(0, r.repo.state.value.currentModeIndex)

        r.repo.setMode(1)
        assertEquals(1, r.unit.ncMode)
        assertEquals(1, r.repo.state.value.currentModeIndex)
        r.repo.setVendorControl("ambient_level", 5)
        assertEquals(5, r.unit.ambientLevel)
        r.repo.refresh()

        val profile = assertNotNull(r.repo.labProfile(), "the Lab sees the session")
        val readings = r.repo.scanLab(listOf(profile.groups.first().id), deep = false).toList()
        assertTrue(readings.isNotEmpty())
        assertTrue(events.none { it is HeadphoneEvent.Error }, "init read everything: $events")
        r.conn.close()
    }

    @Test fun `the poll keeps the session, and power off still reaches the headset`() = runTest {
        val (r, _) = connected()
        val before = r.unit.received.size
        advanceTimeBy(125_000); runCurrent()
        assertTrue(r.unit.received.size > before, "the battery poll ran")
        r.repo.setMode(2)
        assertFalse(r.unit.ncEffect)
        assertEquals(2, r.repo.state.value.currentModeIndex)
        r.repo.powerOff()
        runCurrent()
        assertTrue(r.unit.poweredOff)
    }

    @Test fun `once the link goes, commands and the Lab say not connected`() = runTest {
        val (r, events) = connected()
        r.conn.close()
        r.link.drop()
        runCurrent()
        assertTrue(HeadphoneEvent.Disconnected in events)
        assertEquals(SessionRepository.NOT_CONNECTED, assertFailsWith<HeadphoneCommandException> { r.repo.setMode(1) }.message)
        assertFailsWith<HeadphoneCommandException> { r.repo.refresh() }
        assertNull(r.repo.labProfile())
        assertNull(r.repo.state.value.batteryPercent, "stale data dropped")
    }

    @Test fun `what Sony headphones lack gets the driver's sentence`() = runTest {
        val (r, _) = connected()
        assertEquals(SonyHeadphoneRepository.UNSUPPORTED, assertFailsWith<HeadphoneCommandException> { r.repo.setEq(HeadphoneEq()) }.message)
        assertEquals(SonyHeadphoneRepository.UNSUPPORTED, assertFailsWith<HeadphoneCommandException> { r.repo.enterPairingMode() }.message)
        assertEquals(SonyHeadphoneRepository.UNSUPPORTED, assertFailsWith<HeadphoneCommandException> { r.repo.setDeviceName("x") }.message)
        r.conn.close()
    }
}

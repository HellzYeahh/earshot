package dev.earshot.service

import dev.earshot.bt.BtLog
import dev.earshot.bt.SennheiserConnection
import dev.earshot.bt.SennheiserLinkFactory
import dev.earshot.bt.SennheiserLog
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.NoiseSettings
import dev.earshot.protocol.sennheiser.SennheiserCommands
import dev.earshot.service.session.FakeForegroundHost
import dev.earshot.service.session.FakeLinkSource
import dev.earshot.service.session.SessionRepository
import dev.earshot.service.session.recordEvents
import dev.earshot.virtual.VirtualSennheiser
import dev.earshot.virtual.VirtualSennheiserTransport
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
 * [SennheiserHeadphoneRepository] itself, over a fake link source and the real
 * [SennheiserConnection] to a [VirtualSennheiser] Momentum 4: the session must stay usable for the
 * whole connection (0.75 cleared it right after the poll was launched, so every command after
 * Connected said "Headphones not connected").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SennheiserRepositoryTest {

    @Before
    fun quiet() {
        SennheiserLog.sink = { _, _, _ -> }
        BtLog.sink = { _, _, _ -> }
    }

    private class Rig(val unit: VirtualSennheiser, val link: FakeLinkSource<SennheiserConnection>, val conn: SennheiserConnection, val repo: SennheiserHeadphoneRepository)

    private suspend fun TestScope.connected(unit: VirtualSennheiser = VirtualSennheiser()): Pair<Rig, List<HeadphoneEvent>> {
        val link = FakeLinkSource<SennheiserConnection>(deviceName = "MOMENTUM 4")
        val repo = SennheiserHeadphoneRepository(link, { emptyList() }, FakeForegroundHost(), backgroundScope)
        val events = recordEvents(repo)
        val conn = SennheiserLinkFactory().adopt(VirtualSennheiserTransport(unit), backgroundScope)
        link.open(conn)
        var waited = 0
        while (HeadphoneEvent.Connected !in events && waited < 60_000) { advanceTimeBy(100); runCurrent(); waited += 100 }
        assertTrue(HeadphoneEvent.Connected in events, "the session connected")
        return Rig(unit, link, conn, repo) to events
    }

    @Test fun `commands, refresh and the Lab work after Connected`() = runTest {
        val (r, events) = connected(VirtualSennheiser(level = 30))
        assertEquals(ConnectionStatus.CONNECTED, r.repo.state.value.connection.status)
        assertEquals(80, r.repo.state.value.batteryPercent)
        assertTrue(events.none { it is HeadphoneEvent.Error }, "init read everything: $events")

        r.repo.setMode(SennheiserStateMapping.MODE_TRANSPARENCY)
        runCurrent()
        assertTrue(r.unit.transparencyOn)
        assertEquals(SennheiserStateMapping.MODE_TRANSPARENCY, r.repo.state.value.currentModeIndex)
        assertFalse(r.repo.state.value.isBusy)

        r.repo.setMode(SennheiserStateMapping.MODE_OFF)
        runCurrent()
        assertFalse(r.unit.ancOn)
        r.repo.setNoise(NoiseSettings(cancellingLevel = 7, ancEnabled = true))
        runCurrent()
        assertEquals(30, r.unit.level)
        r.repo.refresh()

        val profile = assertNotNull(r.repo.labProfile(), "the Lab sees the session")
        val readings = r.repo.scanLab(listOf(profile.groups.first().id), deep = false).toList()
        assertTrue(readings.isNotEmpty())
        r.conn.close()
    }

    @Test fun `the poll keeps the session`() = runTest {
        val (r, _) = connected()
        r.unit.received.clear()
        advanceTimeBy(10 * 60_000L + 1_000); runCurrent()
        assertTrue(r.unit.received.any { it.command == SennheiserCommands.BATTERY_GET }, "the battery poll ran")
        r.repo.setMode(SennheiserStateMapping.MODE_ANC)
        runCurrent()
        assertTrue(r.unit.ancOn)
        r.conn.close()
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
    }

    @Test fun `what Sennheiser headsets lack gets the driver's sentence`() = runTest {
        val (r, _) = connected()
        assertEquals(SennheiserHeadphoneRepository.UNSUPPORTED, assertFailsWith<HeadphoneCommandException> { r.repo.powerOff() }.message)
        assertEquals(SennheiserHeadphoneRepository.UNSUPPORTED, assertFailsWith<HeadphoneCommandException> { r.repo.setDeviceName("x") }.message)
        assertEquals(SennheiserHeadphoneRepository.UNSUPPORTED, assertFailsWith<HeadphoneCommandException> { r.repo.setFavorites(setOf(0)) }.message)
        r.conn.close()
    }
}

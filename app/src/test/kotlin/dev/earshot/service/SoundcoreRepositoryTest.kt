package dev.earshot.service

import dev.earshot.bt.BtLog
import dev.earshot.bt.SoundcoreConnection
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.NoiseSettings
import dev.earshot.protocol.soundcore.SoundcoreCommands
import dev.earshot.protocol.soundcore.SoundcoreModel
import dev.earshot.protocol.soundcore.SoundcorePacket
import dev.earshot.service.session.FakeForegroundHost
import dev.earshot.service.session.FakeLinkSource
import dev.earshot.service.session.RecordingLog
import dev.earshot.service.session.SilentTransport
import dev.earshot.service.session.recordEvents
import dev.earshot.virtual.VirtualSleepA30
import dev.earshot.virtual.VirtualSleepA30Transport
import dev.earshot.virtual.VirtualSoundcore
import dev.earshot.virtual.VirtualSoundcoreTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertTrue

/**
 * [SoundcoreHeadphoneRepository] itself, over a fake link source and the real
 * [SoundcoreConnection]: the Sleep A30 path (tested on a real pair, so its observable behaviour is
 * pinned here), a newer model on the vendor table, and the driver's own quirks: an init failure is
 * an Error event but still Connected, "Earbuds not connected", and commands that never checked
 * whether the link is still open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundcoreRepositoryTest {

    @Before
    fun quiet() {
        BtLog.sink = { _, _, _ -> }
    }

    private class Rig(
        val link: FakeLinkSource<SoundcoreConnection>,
        val conn: SoundcoreConnection,
        val repo: SoundcoreHeadphoneRepository,
        val events: List<HeadphoneEvent>,
        val log: RecordingLog,
    )

    private suspend fun TestScope.connected(conn: (TestScope) -> SoundcoreConnection): Rig {
        val link = FakeLinkSource<SoundcoreConnection>(deviceName = "Soundcore Sleep A30")
        val log = RecordingLog()
        val repo = SoundcoreHeadphoneRepository(link, { emptyList() }, FakeForegroundHost(), backgroundScope, log)
        val events = recordEvents(repo)
        val c = conn(this)
        link.open(c)
        var waited = 0
        while (HeadphoneEvent.Connected !in events && waited < 60_000) { advanceTimeBy(100); runCurrent(); waited += 100 }
        assertTrue(HeadphoneEvent.Connected in events, "the session connected")
        return Rig(link, c, repo, events, log)
    }

    private suspend fun TestScope.sleepA30(unit: VirtualSleepA30): Pair<Rig, VirtualSleepA30Transport> {
        val transport = VirtualSleepA30Transport(unit)
        return connected { SoundcoreConnection(transport, it.backgroundScope, minWriteGapMs = 0) } to transport
    }

    // ---- Sleep A30 ---------------------------------------------------------------------------

    @Test fun `Sleep A30 - connect, noise cancelling off and on, tap controls, refresh`() = runTest {
        val unit = VirtualSleepA30(ancOn = true, leftBattery = 7, rightBattery = 5)
        val (r, _) = sleepA30(unit)
        val s = r.repo.state.value
        assertEquals(ConnectionStatus.CONNECTED, s.connection.status)
        assertEquals(60, s.batteryPercent)
        assertEquals(SoundcoreStateMapping.MODE_ANC, s.currentModeIndex)
        assertTrue(r.events.none { it is HeadphoneEvent.Error })

        r.repo.setMode(SoundcoreStateMapping.MODE_OFF)
        assertFalse(unit.ancOn)
        assertEquals(SoundcoreStateMapping.MODE_OFF, r.repo.state.value.currentModeIndex)
        assertFalse(r.repo.state.value.isBusy)
        r.repo.setNoise(NoiseSettings(ancEnabled = true))
        assertTrue(unit.ancOn)
        r.repo.setTouchControls(false)
        assertTrue(unit.tapControlsDisabled)
        assertEquals(false, r.repo.state.value.touchControlsEnabled)
        assertEquals(
            "The Sleep A30 has two modes: noise cancelling and off",
            assertFailsWith<HeadphoneCommandException> { r.repo.setMode(2) }.message,
        )
        unit.leftBattery = 3
        r.repo.refresh()
        assertEquals(40, r.repo.state.value.batteryPercent)
        assertNotNull(r.repo.labProfile())
        assertTrue(r.log.lines.any { it.startsWith("I noise cancelling → ") }, "writes are logged by name: ${r.log.lines}")
        r.conn.close()
    }

    @Test fun `Sleep A30 - pushes apply by themselves and the state is re-read every minute`() = runTest {
        val unit = VirtualSleepA30()
        val (r, transport) = sleepA30(unit)
        transport.push(SoundcorePacket(SoundcoreCommands.PUSH_BATTERY, byteArrayOf(2, 3), inbound = true))
        runCurrent()
        assertEquals(30, r.repo.state.value.batteryPercent)

        val reads = unit.received.count { it.command == SoundcoreCommands.REQUEST_STATE }
        advanceTimeBy(60_001); runCurrent()
        assertEquals(reads + 1, unit.received.count { it.command == SoundcoreCommands.REQUEST_STATE })
        assertEquals(70, r.repo.state.value.batteryPercent, "the poll read the earbuds' own figure back")
        r.conn.close()
    }

    @Test fun `silent earbuds - the read failure is reported, Connected still fires, the link stays`() = runTest {
        val transport = SilentTransport()
        val r = connected { SoundcoreConnection(transport, it.backgroundScope, minWriteGapMs = 0) }
        val error = r.events.filterIsInstance<HeadphoneEvent.Error>().single()
        assertTrue(error.message.startsWith("Connected, but reading the earbuds failed: "), error.message)
        assertEquals(HeadphoneEvent.Connected, r.events.last())
        assertTrue(r.conn.isOpen, "Soundcore never closes a silent link itself")
        r.conn.close()
    }

    @Test fun `not connected - the earbuds' wording, and a closed link is left to the connection`() = runTest {
        val unit = VirtualSleepA30()
        val (r, _) = sleepA30(unit)
        r.conn.close()
        runCurrent()
        // The link is still published: these never checked isOpen, so the closed connection answers.
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { r.repo.refresh() }.message)
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { r.repo.setMode(0) }.message)
        r.link.drop()
        runCurrent()
        assertEquals("Earbuds not connected", assertFailsWith<HeadphoneCommandException> { r.repo.refresh() }.message)
        assertEquals("Earbuds not connected", assertFailsWith<HeadphoneCommandException> { r.repo.setMode(0) }.message)
        assertTrue(HeadphoneEvent.Disconnected in r.events)
    }

    // ---- A newer model on the vendor table -----------------------------------------------------

    @Test fun `Liberty 4 NC - three modes through the layout writes, touch controls refused`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val r = connected { SoundcoreConnection(VirtualSoundcoreTransport(unit), it.backgroundScope, minWriteGapMs = 0, model = unit.model) }
        assertEquals(SoundcoreModelMapping.MODE_NC, r.repo.state.value.currentModeIndex)
        assertTrue(r.conn.writesUnlocked)

        r.repo.setMode(SoundcoreModelMapping.MODE_TRANSPARENCY)
        assertEquals(SoundcoreModelMapping.MODE_TRANSPARENCY, r.repo.state.value.currentModeIndex)
        r.repo.setNoise(NoiseSettings(ancEnabled = false))
        assertEquals(SoundcoreModelMapping.MODE_NORMAL, r.repo.state.value.currentModeIndex)

        assertEquals(SoundcoreHeadphoneRepository.UNSUPPORTED, assertFailsWith<HeadphoneCommandException> { r.repo.setTouchControls(false) }.message)
        assertEquals(SoundcoreHeadphoneRepository.UNSUPPORTED, assertFailsWith<HeadphoneCommandException> { r.repo.setEq(HeadphoneEq()) }.message)
        assertEquals(SoundcoreHeadphoneRepository.UNSUPPORTED, assertFailsWith<HeadphoneCommandException> { r.repo.powerOff() }.message)
        r.conn.close()
    }
}

package dev.earshot.service

import dev.earshot.bt.BragiConnection
import dev.earshot.bt.BragiTrustStore
import dev.earshot.bt.BtLog
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.core.SecureSessionStatus
import dev.earshot.core.Sidetone
import dev.earshot.protocol.bragi.BragiHandshake
import dev.earshot.protocol.bragi.HearingMode
import dev.earshot.service.session.FakeForegroundHost
import dev.earshot.service.session.FakeLinkSource
import dev.earshot.service.session.RecordingLog
import dev.earshot.service.session.recordEvents
import dev.earshot.virtual.VirtualCrusher
import dev.earshot.virtual.VirtualCrusherTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * [CrusherHeadphoneRepository] itself, over a fake link source and the real [BragiConnection] to a
 * [VirtualCrusher]: what the repository adds on top of [CrusherProtocol]. Pushes under a command are
 * merged after its result (StateCommands), a silent link is closed without Connected, the battery
 * poll runs without the busy flag, and the secure-session pairing goes through the state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrusherRepositoryTest {

    @Before
    fun quiet() {
        BtLog.sink = { _, _, _ -> }
        ProtocolLog.sink = { _, _, _ -> }
    }

    private class MemoryTrust : BragiTrustStore {
        private val id by lazy { BragiHandshake.generateIdentity() }
        private val servers = HashMap<String, ByteArray>()
        override fun identity() = id
        override fun trustedServer(address: String) = servers[address.uppercase()]
        override fun trust(address: String, serverPoint: ByteArray) { servers[address.uppercase()] = serverPoint }
        override fun forget(address: String) { servers.remove(address.uppercase()) }
    }

    private class Rig(
        val device: VirtualCrusher,
        val transport: VirtualCrusherTransport,
        val conn: BragiConnection,
        val link: FakeLinkSource<BragiConnection>,
        val repo: CrusherHeadphoneRepository,
        val trust: MemoryTrust,
        val events: List<HeadphoneEvent>,
    )

    private val address = "00:11:22:33:44:55"

    private fun TestScope.rig(device: VirtualCrusher = VirtualCrusher(), accepts: (ByteArray) -> Boolean = { true }): Rig {
        val transport = VirtualCrusherTransport(device, acceptsRequest = accepts)
        val conn = BragiConnection(transport, backgroundScope, clock = { testScheduler.currentTime })
        val link = FakeLinkSource<BragiConnection>(deviceName = "Crusher ANC 2", deviceAddress = address)
        val trust = MemoryTrust()
        val repo = CrusherHeadphoneRepository(
            CrusherProtocol(), link, { emptyList() }, FakeForegroundHost(), backgroundScope, trust,
            savedAddress = { address }, log = RecordingLog(),
        )
        return Rig(device, transport, conn, link, repo, trust, recordEvents(repo))
    }

    private fun TestScope.waitFor(limitMs: Long = 120_000, condition: () -> Boolean) {
        var waited = 0L
        while (!condition() && waited < limitMs) { advanceTimeBy(100); runCurrent(); waited += 100 }
        assertTrue(condition(), "condition not reached within $limitMs ms")
    }

    private fun TestScope.connected(device: VirtualCrusher = VirtualCrusher()): Rig {
        val r = rig(device)
        r.link.open(r.conn)
        waitFor { HeadphoneEvent.Connected in r.events }
        return r
    }

    @Test fun `connect reads the headphones, commands and the Lab work`() = runTest {
        val r = connected()
        val s = r.repo.state.value
        assertEquals(ConnectionStatus.CONNECTED, s.connection.status)
        assertEquals(68, s.batteryPercent)
        assertEquals(listOf("Quiet", "Aware", "Off"), s.modes.map { it.name })
        assertTrue(r.events.none { it is HeadphoneEvent.Error })

        r.repo.setMode(1)
        assertEquals(HearingMode.AT, r.device.hearingMode)
        assertEquals(1, r.repo.state.value.currentModeIndex)
        r.repo.setCrusherBass(intensity = 8)
        assertEquals(8, r.device.crusherIntensity)
        assertEquals(8, r.repo.state.value.crusher?.intensity)
        r.repo.refresh()
        assertFalse(r.repo.state.value.isBusy)
        assertNotNull(r.repo.labProfile())
        r.conn.close()
    }

    @Test fun `what the Crusher lacks says so, feature by feature`() = runTest {
        val r = connected()
        assertEquals("Custom modes isn't available on these headphones", assertFailsWith<HeadphoneCommandException> { r.repo.saveCustomMode(4, "x", NoiseSettings()) }.message)
        assertEquals("The headphones' own equaliser isn't available on these headphones", assertFailsWith<HeadphoneCommandException> { r.repo.setEq(HeadphoneEq()) }.message)
        assertEquals("Power off isn't available on these headphones", assertFailsWith<HeadphoneCommandException> { r.repo.powerOff() }.message)
        assertEquals("Switching devices isn't available on these headphones", assertFailsWith<HeadphoneCommandException> { r.repo.switchToDevice("AA:BB:CC:DD:EE:FF") }.message)
        assertEquals("Only read-only calls are available in the Lab", assertFailsWith<HeadphoneCommandException> { r.repo.readRaw(0x7F, 0x7F) }.message)
        assertEquals("Not supported by this connection", assertFailsWith<HeadphoneCommandException> { r.repo.setAutoOff(20) }.message)
        r.conn.close()
    }

    @Test fun `a push without a command applies at once`() = runTest {
        val r = connected()
        r.transport.push(r.device.setHearingModeOnDevice(HearingMode.AT)) // the user pressed the button
        runCurrent()
        assertEquals(1, r.repo.state.value.currentModeIndex)
        r.conn.close()
    }

    @Test fun `a push during a command is merged after its result instead of being overwritten`() = runTest {
        val r = connected()
        assertEquals(0, r.repo.state.value.currentModeIndex)
        val command = async { r.repo.setSidetone(Sidetone.HIGH) }
        runCurrent() // the request is out, its reply is still on the way
        assertTrue(r.repo.state.value.isBusy)
        r.transport.push(r.device.setHearingModeOnDevice(HearingMode.AT))
        runCurrent()
        assertEquals(0, r.repo.state.value.currentModeIndex, "parked while the command holds the lock")
        command.await()
        assertEquals(Sidetone.HIGH, r.repo.state.value.sidetone)
        assertEquals(1, r.repo.state.value.currentModeIndex, "the push survived the command's snapshot")
        assertFalse(r.repo.state.value.isBusy)
        r.conn.close()
    }

    @Test fun `the battery is polled every minute without the busy flag`() = runTest {
        val r = connected()
        val busy = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.repo.state.collect { busy += it.isBusy } }
        r.device.batteryPercent = 50
        advanceTimeBy(59_000); runCurrent()
        assertEquals(68, r.repo.state.value.batteryPercent)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(50, r.repo.state.value.batteryPercent)
        assertFalse(true in busy, "the poll never greys out the controls")
        r.conn.close()
    }

    @Test fun `a silent link is closed and never reported connected`() = runTest {
        val r = rig(accepts = { false })
        r.link.open(r.conn)
        waitFor { !r.conn.isOpen }
        runCurrent()
        assertFalse(HeadphoneEvent.Connected in r.events, "${r.events}")
        assertTrue(r.events.none { it is HeadphoneEvent.Error }, "a busy channel is the manager's message, not an error: ${r.events}")
    }

    @Test fun `the secure session asks the user through the state, then connects`() = runTest {
        val r = rig(VirtualCrusher(authenticatedAccess = true))
        r.link.open(r.conn)
        waitFor { r.repo.state.value.pairing != null }
        assertEquals(r.device.lastPasscode, r.repo.state.value.pairing?.passcode)
        assertFalse(HeadphoneEvent.Connected in r.events)
        r.repo.answerPairing(true)
        waitFor { HeadphoneEvent.Connected in r.events }
        assertNull(r.repo.state.value.pairing)
        assertEquals(SecureSessionStatus.SECURE, r.repo.state.value.secureSession.status)
        assertNotNull(r.trust.trustedServer(address))

        r.repo.forgetPairing()
        assertNull(r.trust.trustedServer(address))
        r.conn.close()
    }

    @Test fun `once the link goes, commands and the Lab say not connected`() = runTest {
        val r = connected()
        r.conn.close()
        r.link.drop()
        runCurrent()
        assertTrue(HeadphoneEvent.Disconnected in r.events)
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { r.repo.setMode(0) }.message)
        assertNull(r.repo.labProfile())
        assertEquals(HeadphoneState(connection = r.link.state.value), r.repo.state.value)
    }
}

package dev.earshot.virtual

import dev.earshot.bt.BmapConnection
import dev.earshot.bt.BmapRequester
import dev.earshot.bt.BtLog
import dev.earshot.bt.ReplyMode
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.qcultra2.ModeConfigs
import dev.earshot.protocol.qcultra2.PRODUCT_ID_QC_ULTRA_1
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.service.ProtocolLog
import dev.earshot.service.QcUltra2Protocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real protocol (and, where the watchdog matters, the real connection) against the virtual
 * unit switched into the older-firmware behaviours the QC Ultra 1st Gen and other untested models
 * may have: silent unknown functions, no `[31.10]`, a refused block 31.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OlderFirmwareEndToEndTest {

    /** The connection's reply matching without the socket and the timing; silence is a timeout. */
    private class DirectRequester(val device: VirtualQcUltra2) : BmapRequester {
        override val isOpen: Boolean get() = device.isPoweredOn

        override suspend fun request(packet: BmapPacket, mode: ReplyMode, timeoutMs: Long): List<BmapPacket> {
            if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
            val frames = device.handle(packet)
            device.drainDeferred()
            return when (mode) {
                ReplyMode.SINGLE -> frames.firstOrNull { it.matches(packet.address) && it.operator != BmapOperator.PROCESSING }?.let { listOf(it) }
                ReplyMode.MULTI -> frames.filter { it.block == packet.block }.ifEmpty { null }
            } ?: throw HeadphoneCommandException("${packet.address}: no reply from the headphones")
        }

        override suspend fun send(packet: BmapPacket) {
            device.handle(packet)
        }
    }

    private val protocol = QcUltra2Protocol()

    @Before
    fun silenceLog() {
        ProtocolLog.sink = { _, _, _ -> }
        BtLog.sink = { _, _, _ -> }
    }

    @Test
    fun `a firmware that ignores unknown functions keeps the connection and they are skipped afterwards`() = runTest {
        val silent = setOf(QcUltra2.AUTO_PLAY_PAUSE, QcUltra2.AUTO_ANSWER, QcUltra2.VOICE_PROMPTS) // asked back to back
        val device = VirtualQcUltra2().apply { silentFunctions = silent }
        val transport = VirtualTransport(device, replyDelayMs = 20L..20L, chunkGapMs = 10L, tickIntervalMs = 3_600_000L, sendGreeting = false, random = Random(3))
        val conn = BmapConnection(transport, backgroundScope, minWriteGapMs = 0, clock = { testScheduler.currentTime })

        var s = protocol.onConnected(conn, HeadphoneState())
        s = protocol.refresh(conn, s)

        // Until 0.3.0 the third silent read closed the link ("stopped answering") and the manager
        // reconnected into the same wall. Now [0.1] settles it and the refresh carries on.
        assertTrue(conn.isOpen)
        assertEquals(silent, conn.silentFunctions)
        assertEquals(HeadphoneEq(bass = 0, mid = -2, treble = -6), s.eq)
        assertEquals(QcUltra2.MODE_SLOT_COUNT, s.modes.size, "everything after the silent reads was still read")
        assertEquals(72, s.batteryPercent)

        val asked = silent.associateWith(device::requestsTo)
        protocol.refresh(conn, s)
        assertEquals(asked, silent.associateWith(device::requestsTo), "absent for the rest of the connection")
        assertTrue(conn.isOpen)
        conn.close()
    }

    @Test
    fun `a dead link is still recognised when 0_1 is silent too`() = runTest {
        val device = VirtualQcUltra2().apply {
            silentFunctions = setOf(QcUltra2.AUTO_PLAY_PAUSE, QcUltra2.AUTO_ANSWER, QcUltra2.VOICE_PROMPTS, QcUltra2.BMAP_VERSION)
        }
        val transport = VirtualTransport(device, replyDelayMs = 20L..20L, chunkGapMs = 10L, tickIntervalMs = 3_600_000L, sendGreeting = false, random = Random(3))
        val conn = BmapConnection(transport, backgroundScope, minWriteGapMs = 0, clock = { testScheduler.currentTime })
        val e = assertFailsWith<HeadphoneCommandException> { protocol.refresh(conn, HeadphoneState()) }
        assertEquals(BmapConnection.MESSAGE_STOPPED_ANSWERING, e.message)
        assertFalse(conn.isOpen)
    }

    @Test
    fun `firmware without block 31 gives a usable state with the modes hidden`() = runTest {
        val device = VirtualQcUltra2().apply {
            // bosectl #44: a unit with goodyear's id (NC 700, catalogued since 0.4.0) whose [0.2] lists
            // block 31 but refuses every request in it. The wolverine profile underneath also refuses
            // the [1.5] write, so the level ends up read-only.
            productId = 0x4024
            productName = "Bose NC 700 HP"
            refuseAudioModesBlock = true
        }
        val r = DirectRequester(device)
        var s = protocol.onConnected(r, HeadphoneState())
        // The unit still pushes [31.3] on connect.
        for (frame in BmapPacket.decodeAll(device.greeting())) s = protocol.onUnsolicited(frame, s) ?: s
        s = protocol.refresh(r, s)

        assertFalse(s.capabilities.listeningModes)
        assertFalse(s.capabilities.editableModes)
        assertTrue(s.modes.isEmpty())
        assertEquals(0, s.currentModeIndex)
        assertEquals(72, s.batteryPercent)
        assertEquals(HeadphoneEq(bass = 0, mid = -2, treble = -6), s.eq)
        assertEquals(10, s.noise?.cancellingLevel, "[1.5]: Quiet = most cancelling")
        assertEquals(1, device.requestsToBlock(31), "one FblockNotSupp, no error spam")

        s = protocol.refresh(r, s)
        assertEquals(1, device.requestsToBlock(31))
        val e = assertFailsWith<HeadphoneCommandException> { protocol.setMode(r, s, 1) }
        assertEquals(QcUltra2Protocol.MODES_UNAVAILABLE_MESSAGE, e.message)
        // The [1.5] write is tried once, refused (OpNotSupp), and the level is read-only after that.
        val noise = assertFailsWith<HeadphoneCommandException> { protocol.setNoise(r, s, NoiseSettings(cancellingLevel = 2)) }
        assertEquals(QcUltra2Protocol.NOISE_READ_ONLY_MESSAGE, noise.message)
        assertFalse(protocol.refresh(r, s).capabilities.liveNoiseControl)
        assertEquals(1, device.requestsToBlock(31))
    }

    @Test
    fun `without 31_10 noise edits go through the 48-byte slot of the active custom mode`() = runTest {
        // Both the QC Ultra 1st Gen guess and a QC Ultra 2 catalogue entry must survive it.
        for (id in listOf(PRODUCT_ID_QC_ULTRA_1, QcUltra2.PRODUCT_ID)) {
            val device = VirtualQcUltra2().apply { productId = id; withoutAudioSettings = true }
            val r = DirectRequester(device)
            val p = QcUltra2Protocol()
            var s = p.refresh(r, p.onConnected(r, HeadphoneState()))
            assertFalse(s.capabilities.liveNoiseControl, "0x%04x".format(id))
            assertTrue(s.capabilities.listeningModes)
            assertTrue(s.capabilities.ancToggle, "48-byte slots carry the ANC byte")
            assertEquals(10, s.noise?.cancellingLevel, "Quiet's slot settings")

            // Built-in mode: explained, nothing written.
            assertFailsWith<HeadphoneCommandException> { p.setNoise(r, s, NoiseSettings(cancellingLevel = 3)) }

            s = p.setMode(r, s, 4) // "Home"
            s = p.setNoise(r, s, NoiseSettings(cancellingLevel = 3, ancEnabled = true, windBlock = false))
            assertEquals(3, s.noise?.cancellingLevel)
            assertEquals(4, s.currentModeIndex)
            val slot = ModeConfigs.parse(device.slots[4])!!
            assertEquals(7, slot.cncWire) // level 3 → wire 7
            assertFalse(slot.windBlock)
            assertEquals("Home", slot.name)
            assertEquals(7, device.live.cncWire, "the active slot's rewrite is what the unit now plays")
            assertEquals(1, device.requestsTo(QcUltra2.AUDIO_SETTINGS), "[31.10] asked once, never written")
        }
    }

    @Test
    fun `a silent 31_10 drops live noise only on unverified models`() = runTest {
        // On the verified QC Ultra 2 one slow reply must not switch noise edits to slot writes
        // (which refuse the built-in modes); on the 1st Gen silence means the function is missing.
        for ((id, live) in listOf(QcUltra2.PRODUCT_ID to true, PRODUCT_ID_QC_ULTRA_1 to false)) {
            val device = VirtualQcUltra2().apply { productId = id; silentFunctions = setOf(QcUltra2.AUDIO_SETTINGS) }
            val r = DirectRequester(device)
            val p = QcUltra2Protocol()
            val s = p.refresh(r, p.onConnected(r, HeadphoneState()))
            assertEquals(live, s.capabilities.liveNoiseControl, "0x%04x".format(id))
        }
    }
}

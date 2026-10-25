package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.airoha.RaceFrameBuffer
import dev.earshot.protocol.airoha.RaceIds
import dev.earshot.protocol.airoha.RacePacket
import dev.earshot.protocol.airoha.RaceVendor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RaceConnection] against a small Airoha device: replies by id, the guard before the link, silence, pushes. */
@OptIn(ExperimentalCoroutinesApi::class)
class RaceConnectionTest {

    @Before
    fun quiet() {
        BtLog.sink = { _, _, _ -> }
    }

    /** Answers every command with a `5B` response `00` + [answers]`[id]`, the battery read with a `5D` indication. */
    private class VirtualAiroha : ByteTransport {
        var silent = false
        val received = mutableListOf<RacePacket>()
        private val frames = RaceFrameBuffer()
        private val out = Channel<ByteArray>(Channel.UNLIMITED)

        override val isConnected: Boolean get() = !out.isClosedForSend
        override val incoming: Flow<ByteArray> = out.receiveAsFlow()

        override suspend fun write(bytes: ByteArray) {
            for (cmd in frames.feed(bytes)) {
                received += cmd
                if (silent) continue
                val reply = when (cmd.id) {
                    RaceIds.TWS_BATTERY -> RacePacket(RacePacket.TYPE_INDICATION, cmd.id, byteArrayOf(0, cmd.payload[0], 70))
                    else -> RacePacket(RacePacket.TYPE_RESPONSE, cmd.id, byteArrayOf(0, 2))
                }
                out.trySend(reply.encode())
            }
        }

        fun push(packet: RacePacket) {
            out.trySend(packet.encode())
        }

        override fun close() {
            out.close()
        }
    }

    private fun TestScope.connect(unit: VirtualAiroha, vendor: RaceVendor = RaceVendor.JBL) = RaceConnection(unit, vendor, backgroundScope)

    @Test fun `the probe read gets its reply`() = runTest {
        val unit = VirtualAiroha()
        val conn = connect(unit)
        AirohaRaceLink.probe(conn)
        assertEquals(RaceIds.MMI_GET_ENUM, unit.received.single().id)
        assertContentEquals(byteArrayOf(0, 0), unit.received.single().payload)
        conn.close()
    }

    @Test fun `the battery read is answered by an indication`() = runTest {
        val conn = connect(VirtualAiroha())
        val reply = conn.request(RaceIds.TWS_BATTERY, byteArrayOf(1))
        assertTrue(reply.isSuccess)
        assertContentEquals(byteArrayOf(1, 70), reply.body)
        conn.close()
    }

    @Test fun `refused commands never reach the headset`() = runTest {
        val unit = VirtualAiroha()
        val conn = connect(unit)
        for (id in listOf(0x1C00, 0x0A01, 0x0D01, 0x0093, 0x1E07)) {
            assertFailsWith<HeadphoneCommandException> { conn.request(id) }
        }
        assertFailsWith<HeadphoneCommandException>("the tone needs the user's tap") { conn.request(RaceIds.FIND_ME, byteArrayOf(1, 1, 0)) }
        assertTrue(unit.received.isEmpty())
        assertTrue(conn.isOpen, "a refusal is not a broken link")
        conn.request(RaceIds.FIND_ME, byteArrayOf(1, 1, 0), userInitiated = true)
        assertEquals(1, unit.received.size)
        conn.close()
    }

    @Test fun `three silent commands close the link as busy`() = runTest {
        val unit = VirtualAiroha().apply { silent = true }
        val conn = connect(unit)
        repeat(RaceConnection.SILENT_REQUESTS_BEFORE_DEAD) {
            assertFailsWith<HeadphoneCommandException> { conn.request(RaceIds.ROLE_QUERY_B, timeoutMs = 200) }
        }
        assertFalse(conn.isOpen)
        assertIs<UnresponsiveLinkException>(conn.awaitClosed())
    }

    @Test fun `unasked indications go to the pushes`() = runTest {
        val unit = VirtualAiroha()
        val conn = connect(unit)
        unit.push(RacePacket(RacePacket.TYPE_INDICATION, RaceIds.ANC_PASSTHROUGH, byteArrayOf(0, 0x0A, 1)))
        val push = conn.pushes.first()
        assertEquals(RaceIds.ANC_PASSTHROUGH, push.id)
        conn.close()
    }

    @Test fun `RACE is never opened on Sony, Bose or Bragi headsets`() {
        val spp = listOf(AirohaRaceLink.SPP_SERVICE)
        assertNotNull(AirohaRaceLink.refusal("WH-1000XM5", null, spp, overLe = false))
        assertNotNull(AirohaRaceLink.refusal("Bose QC Earbuds", null, spp, overLe = false))
        assertNotNull(AirohaRaceLink.refusal("Crusher ANC 2", DeviceFamily.BRAGI, spp, overLe = false))
        assertNotNull(AirohaRaceLink.refusal("Alex's buds", DeviceFamily.SONY, spp, overLe = true), "a renamed Sony stays a Sony")
        assertNull(AirohaRaceLink.refusal("JBL Tour Pro 3", null, spp, overLe = false))
        assertNull(AirohaRaceLink.refusal("JBL Tour Pro 3", null, emptyList(), overLe = false), "no cached record: SDP decides")
        assertNotNull(AirohaRaceLink.refusal("JBL Tour Pro 3", null, listOf(UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")), overLe = false))
        assertNull(AirohaRaceLink.refusal("JBL Tour Pro 3", null, emptyList(), overLe = true))
    }
}

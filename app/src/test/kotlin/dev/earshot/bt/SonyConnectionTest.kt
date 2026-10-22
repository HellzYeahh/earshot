package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.sony.SonyMessage
import dev.earshot.protocol.sony.SonyNcAsm
import dev.earshot.protocol.sony.SonyProtocolInfo
import dev.earshot.protocol.sony.SonyTable
import dev.earshot.protocol.sony.TandemDataType
import dev.earshot.protocol.sony.TandemFrame
import dev.earshot.protocol.sony.TandemFrameBuffer
import dev.earshot.virtual.VirtualSony
import dev.earshot.virtual.VirtualSonyTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Tandem link rules in [SonyConnection]: ACK and 1-bit sequence, resends, the start delay, the guard. */
@OptIn(ExperimentalCoroutinesApi::class)
class SonyConnectionTest {

    @Before
    fun quiet() {
        SonyLog.sink = { _, _, _ -> }
        BtLog.sink = { _, _, _ -> }
    }

    private fun TestScope.connect(unit: VirtualSony): SonyConnection = SonyConnection(VirtualSonyTransport(unit), backgroundScope)

    private val protocolInfo = SonyMessage(SonyTable.T1, SonyProtocolInfo.GET)
    private val ncParam = SonyMessage(SonyTable.T1, SonyNcAsm.getParam(0x17))

    @Test fun `sequence numbers alternate and follow the headset's ACKs`() = runTest {
        val unit = VirtualSony()
        val conn = connect(unit)
        repeat(3) { conn.request(protocolInfo) }
        assertEquals(listOf(0, 1, 0), unit.phoneDataSeqs, "each ACK's seq (1 − sent) becomes the next seq")
        // Every reply frame was ACKed with 1 − its seq: the unit sent seq 0, 1, 0.
        assertEquals(listOf(1, 0, 1), unit.phoneAckSeqs)
        conn.close()
    }

    @Test fun `nothing is sent during the first 500 ms`() = runTest {
        val unit = VirtualSony()
        val conn = connect(unit)
        val start = testScheduler.currentTime
        conn.request(protocolInfo)
        assertTrue(testScheduler.currentTime - start >= SonyConnection.OPEN_DELAY_MS)
        val before = testScheduler.currentTime
        conn.request(protocolInfo)
        assertTrue(testScheduler.currentTime - before < SonyConnection.OPEN_DELAY_MS, "only the first send waits")
        conn.close()
    }

    @Test fun `a lost ACK is recovered by a resend after 750 ms, and the duplicate is not processed twice`() = runTest {
        val unit = VirtualSony()
        val conn = connect(unit)
        conn.request(protocolInfo)
        unit.dropAcks = 1
        val before = testScheduler.currentTime
        val reply = conn.request(ncParam)
        assertEquals(0x67, reply.command)
        assertTrue(testScheduler.currentTime - before >= SonyConnection.ACK_TIMEOUT_MS, "waited for the ACK once")
        assertEquals(listOf(0, 1, 1), unit.phoneDataSeqs, "the resend kept its seq")
        assertEquals(1, unit.received.count { it.command == 0x66 }, "the unit saw the duplicate and ignored it")
        // The link carries on: the next frame uses the seq the late ACK gave.
        conn.request(protocolInfo)
        assertEquals(0, unit.phoneDataSeqs.last())
        conn.close()
    }

    @Test fun `a lost frame is resent until the headset sees it`() = runTest {
        val unit = VirtualSony()
        val conn = connect(unit)
        unit.dropIncoming = 2
        val reply = conn.request(protocolInfo)
        assertEquals(0x01, reply.command)
        assertTrue(testScheduler.currentTime >= SonyConnection.OPEN_DELAY_MS + 2 * SonyConnection.ACK_TIMEOUT_MS)
        conn.close()
    }

    @Test fun `no ACK after ten resends drops the link`() = runTest {
        val unit = VirtualSony()
        unit.silent = true
        val transport = VirtualSonyTransport(unit)
        val conn = SonyConnection(transport, backgroundScope)
        val closed = async { conn.awaitClosed() }
        assertFailsWith<HeadphoneCommandException> { conn.send(protocolInfo) }
        assertFalse(conn.isOpen)
        assertIs<UnresponsiveLinkException>(closed.await())
        assertEquals(
            SonyConnection.OPEN_DELAY_MS + (SonyConnection.MAX_RESENDS + 1) * SonyConnection.ACK_TIMEOUT_MS,
            testScheduler.currentTime,
            "750 ms per try, 1 + 10 tries, after the 500 ms start delay",
        )
        assertFalse(transport.isConnected)
    }

    @Test fun `refused messages never reach the socket`() = runTest {
        val unit = VirtualSony()
        val conn = connect(unit)
        for (p in listOf("c4 01 00", "f8 09 01", "24 03 ff", "30 00", "3e 00", "0f 00", "68 40 01 01 00 00 00")) {
            val payload = p.split(" ").map { it.toInt(16).toByte() }.toByteArray()
            assertFailsWith<HeadphoneCommandException>(p) { conn.send(SonyMessage(SonyTable.T1, payload)) }
        }
        assertFailsWith<HeadphoneCommandException> {
            conn.send(SonyMessage(SonyTable.T2, byteArrayOf(0x3C, 0x00, 0x02) + "AA:BB:CC:DD:EE:FF".toByteArray()))
        }
        assertTrue(unit.received.isEmpty(), "nothing was written: ${unit.received}")
        assertTrue(unit.phoneDataSeqs.isEmpty())
        conn.close()
    }

    /** A transport the test drives byte by byte. */
    private class ScriptedTransport : ByteTransport {
        val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val written = mutableListOf<TandemFrame>()
        private val decoder = TandemFrameBuffer()
        var onData: (TandemFrame) -> Unit = {}
        override var isConnected = true
        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()
        override suspend fun write(bytes: ByteArray) {
            for (f in decoder.feed(bytes)) {
                written += f
                if (!f.isAck) onData(f)
            }
        }
        override fun close() { isConnected = false; inbound.close() }
        fun inject(type: Int, seq: Int, vararg payload: Int) {
            inbound.trySend(TandemFrame(type, seq, ByteArray(payload.size) { payload[it].toByte() }).encode())
        }
    }

    @Test fun `received frames are ACKed with 1 - seq, resends dropped, notifications always kept`() = runTest {
        val t = ScriptedTransport()
        val conn = SonyConnection(t, backgroundScope)
        val got = mutableListOf<Int>()
        backgroundScope.launch { conn.unsolicited.collect { got += it.command } }
        testScheduler.runCurrent()
        t.inject(TandemDataType.DATA_MDR, 0, 0x23, 0x00, 0x46, 0x00) // RET battery, seq 0
        t.inject(TandemDataType.DATA_MDR, 0, 0x23, 0x00, 0x46, 0x00) // the same again: a resend
        t.inject(TandemDataType.DATA_MDR, 1, 0x25, 0x00, 0x45, 0x00) // NTFY battery, seq 1
        t.inject(TandemDataType.DATA_MDR, 1, 0x25, 0x00, 0x44, 0x00) // NTFY with the same seq: still kept
        t.inject(TandemDataType.SHOT_MDR, 0, 0x25, 0x00, 0x43, 0x00) // SHOT: delivered, never ACKed
        testScheduler.runCurrent()
        assertEquals(listOf(0x23, 0x25, 0x25, 0x25), got)
        assertEquals(listOf(1, 1, 0, 0), t.written.filter { it.isAck }.map { it.seq })
        conn.close()
    }

    @Test fun `an ACK carrying the current seq is stale and ignored`() = runTest {
        val t = ScriptedTransport()
        var tries = 0
        t.onData = { f ->
            tries++
            // First try: a stale ACK (same seq). The resend gets the proper one.
            t.inject(TandemDataType.ACK, if (tries == 1) f.seq else f.seq xor 1)
        }
        val conn = SonyConnection(t, backgroundScope)
        conn.send(protocolInfo)
        assertEquals(2, tries)
        assertEquals(listOf(0, 0), t.written.filter { !it.isAck }.map { it.seq })
        conn.send(protocolInfo)
        assertEquals(1, t.written.last { !it.isAck }.seq, "the proper ACK's seq is the next seq")
        conn.close()
    }
}

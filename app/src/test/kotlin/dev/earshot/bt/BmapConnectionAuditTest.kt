package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scriptable [ByteTransport] for the audit fixes below (same shape as BmapConnectionTest's). */
private class AuditTransport : ByteTransport {
    val written = ArrayList<ByteArray>()
    var onWrite: (ByteArray) -> Unit = {}
    /** When true, write() never returns: the stack's outbound buffer is full and the peer is not reading. */
    var stallWrites = false
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)

    override var isConnected: Boolean = true
    override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        if (stallWrites) awaitCancellation()
        written += bytes
        onWrite(bytes)
    }

    override fun close() {
        isConnected = false
        inbound.close()
    }

    fun deliver(hex: String) { inbound.trySend(hex.replace(" ", "").hexToBytes()) }
}

/**
 * The pre-release audit's BmapConnection fixes: no personal data in the frame logs (R09), a
 * bounded send (R25), pushes inside a MULTI dump (R29) and the frame-buffer reset done by the
 * reader without cutting a late frame (R30).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BmapConnectionAuditTest {
    private val logLines = ArrayList<String>()

    @Before
    fun captureLog() {
        BtLog.sink = { _, msg, _ -> synchronized(logLines) { logLines += msg } }
    }

    @After
    fun silenceLog() {
        BtLog.sink = { _, _, _ -> }
    }

    private val battery = FunctionAddress(2, 2)
    private val currentMode = FunctionAddress(31, 3)
    private val modeConfig = FunctionAddress(31, 6)

    // ── R09: logs ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `frames that can carry personal data are logged by size only`() {
        val laptop = "9445608505a7"
        val name = "Jane's Laptop".toByteArray()
        val personal = listOf(
            BmapPacket(4, 4, BmapOperator.STATUS, "03 c40f08112233 $laptop".hexToBytes()), // paired list
            BmapPacket(4, 5, BmapOperator.STATUS, "$laptop 00 0101".hexToBytes() + name),      // paired device's name
            BmapPacket(4, 5, BmapOperator.GET, laptop.hexToBytes()),                             // the MAC asked about
            BmapPacket(4, 9, BmapOperator.STATUS, "2cda46000002".hexToBytes()),                  // this phone's address
            BmapPacket(4, 12, BmapOperator.START, "82 $laptop".hexToBytes()),                    // routing
            BmapPacket(5, 1, BmapOperator.STATUS, "000f01 $laptop".hexToBytes()),                // active source
            BmapPacket(0, 7, BmapOperator.STATUS, "SN123456".toByteArray()),                     // serial number
            BmapPacket(1, 2, BmapOperator.STATUS, byteArrayOf(0) + "Jane's QC".toByteArray()),  // the user's name for it
            BmapPacket(18, 24, BmapOperator.STATUS, ByteArray(16) { 0x5a }),                     // a key
            BmapPacket(22, 2, BmapOperator.STATUS, "console text".toByteArray()),               // firmware console
            BmapPacket(40, 1, BmapOperator.STATUS, "0102".hexToBytes()),                         // unknown block (Lab)
        )
        for (p in personal) {
            val line = p.describeForLog()
            assertEquals("${p.address} ${p.operator.name}: ${p.payload.size} B", line)
            assertFalse(p.payload.toHex() in line, line)
        }
    }

    @Test
    fun `settings, battery, modes and errors stay readable`() {
        assertEquals("[2.2] STATUS: 50ffff00", BmapPacket(2, 2, BmapOperator.STATUS, "50ffff00".hexToBytes()).describeForLog())
        assertEquals("[0.5] STATUS: 31302e3132", BmapPacket(0, 5, BmapOperator.STATUS, "10.12".toByteArray()).describeForLog())
        assertEquals("[31.10] SET_GET: 0500000001", BmapPacket(31, 10, BmapOperator.SET_GET, "0500000001".hexToBytes()).describeForLog())
        assertEquals("[1.7] GET: ", BmapPacket(1, 7, BmapOperator.GET).describeForLog())
        // An error on a personal function keeps its code, which is all its payload holds.
        assertEquals("[4.5] ERROR: INVALID_DATA", BmapPacket(4, 5, BmapOperator.ERROR, byteArrayOf(6)).describeForLog())
        assertEquals("[18.3] ERROR: FBLOCK_SPECIFIC (ff01)", BmapPacket(18, 3, BmapOperator.ERROR, "ff01".hexToBytes()).describeForLog())
    }

    @Test
    fun `a paired device's name and address never reach the log or the connection report`() = runTest {
        LogRing.clear()
        val t = AuditTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        val laptop = "d00dfeed4e55"
        val nameHex = "Quokk".toByteArray().toHex()
        // 0x0e = 14 bytes: MAC (6), flags + Class of Device (3), five bytes of name.
        t.onWrite = { t.deliver("0405030e $laptop 000101 $nameHex") }
        c.request(BmapPacket(4, 5, BmapOperator.GET, laptop.hexToBytes()))

        val lines = synchronized(logLines) { logLines.toList() } + LogRing.tail(400)
        assertTrue(lines.any { "[4.5] GET: 6 B" in it }, lines.joinToString("\n"))
        assertTrue(lines.any { "[4.5] STATUS: 14 B" in it }, lines.joinToString("\n"))
        assertTrue(lines.none { laptop in it || nameHex in it }, lines.joinToString("\n"))
    }

    // ── R25: send ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `send on a write that never completes closes the link instead of pinning the mutex`() = runTest {
        val t = AuditTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        t.stallWrites = true

        val e = assertFailsWith<HeadphoneCommandException> { c.send(BmapPacket.start(FunctionAddress(7, 4), byteArrayOf(0))) }
        assertTrue("lost" in e.message.orEmpty(), e.message)
        assertFalse(c.isOpen)
        assertFalse(t.isConnected)
        assertEquals("write stalled", (c.closeCause as IOException).message)
        assertTrue(c.awaitClosed() is IOException) // the manager sees a dropped link and reconnects
        // The next caller is not stuck behind the stalled one.
        assertFailsWith<HeadphoneCommandException> { c.send(BmapPacket.start(FunctionAddress(4, 8), byteArrayOf(1))) }
    }

    @Test
    fun `send still writes once and returns on a healthy link`() = runTest {
        val t = AuditTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        c.send(BmapPacket.start(FunctionAddress(7, 4), byteArrayOf(0)))
        assertEquals(listOf("0704050100"), t.written.map { it.toHex() })
        assertTrue(c.isOpen)
    }

    // ── R29: pushes inside a MULTI dump ───────────────────────────────────────────────────

    @Test
    fun `a mode push that lands inside the slot dump still reaches unsolicited`() = runTest {
        val t = AuditTransport()
        val c = BmapConnection(t, backgroundScope, quietGapMs = 400, minWriteGapMs = 0)
        val pushes = ArrayList<BmapPacket>()
        backgroundScope.launch { c.unsolicited.collect { pushes += it } }
        runCurrent()

        t.onWrite = { t.deliver("1f060301 00") }
        backgroundScope.launch {
            delay(100); t.deliver("1f030301 02") // the mode button pressed during the dump
            delay(100); t.deliver("1f060301 01")
        }
        val reply = c.request(BmapPacket.start(modeConfig), ReplyMode.MULTI)

        assertEquals(listOf(0, 1), reply.filter { it.address == modeConfig }.map { it.payload[0].toInt() })
        runCurrent()
        assertEquals(listOf(currentMode), pushes.map { it.address })
        assertEquals(2, pushes.single().payload[0].toInt())
    }

    @Test
    fun `a GetAll dump is the reply only and is not replayed as pushes`() = runTest {
        val t = AuditTransport()
        val c = BmapConnection(t, backgroundScope, quietGapMs = 400, minWriteGapMs = 0)
        val pushes = ArrayList<BmapPacket>()
        backgroundScope.launch { c.unsolicited.collect { pushes += it } }
        runCurrent()

        // [31.1] GetAll: PROCESSING on [31.1], then other functions' STATUS frames.
        t.onWrite = { t.deliver("1f010700") }
        backgroundScope.launch {
            delay(50); t.deliver("1f030301 02")
            delay(50); t.deliver("1f060301 00")
        }
        val reply = c.request(BmapPacket.start(FunctionAddress(31, 1)), ReplyMode.MULTI)

        assertEquals(listOf(currentMode, modeConfig), reply.map { it.address })
        runCurrent()
        assertTrue(pushes.isEmpty(), "$pushes")
    }

    // ── R30: the frame-buffer reset after a timeout ───────────────────────────────────────

    @Test
    fun `a reply whose tail arrives just after the timeout is completed, not cut`() = runTest {
        val t = AuditTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0, clock = { testScheduler.currentTime })
        val pushes = ArrayList<BmapPacket>()
        backgroundScope.launch { c.unsolicited.collect { pushes += it } }
        runCurrent()

        // The battery reply starts 10 ms before the 1 s timeout; its last three bytes come 10 ms after it.
        t.onWrite = { backgroundScope.launch { delay(990); t.deliver("02020304 50") } }
        assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(battery), timeoutMs = 1_000) }
        delay(10)
        t.deliver("ffff00")
        runCurrent()

        assertEquals(listOf("50ffff00"), pushes.filter { it.address == battery }.map { it.payload.toHex() })
        assertTrue(c.isOpen)
    }

    @Test
    fun `bytes that sat through a timeout are still dropped before the next reply`() = runTest {
        val t = AuditTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0, clock = { testScheduler.currentTime })
        t.onWrite = { t.deliver("0202") } // half a header, then silence

        assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(battery), timeoutMs = 1_000) }
        t.onWrite = { t.deliver("02020304 50ffff00") }
        val reply = c.request(BmapPacket.get(battery)).single()

        assertEquals(BmapOperator.STATUS, reply.operator)
        assertEquals("50ffff00", reply.payload.toHex())
        assertTrue(synchronized(logLines) { logLines.any { "resetting frame buffer" in it } })
    }
}

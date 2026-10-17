package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.hexToBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scriptable [ByteTransport]: the test decides what the "headphones" answer to each write. */
private class FakeTransport : ByteTransport {
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
    fun drop(cause: IOException) { inbound.close(cause) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BmapConnectionTest {

    @Before
    fun silenceLog() {
        BtLog.sink = { _, _, _ -> }
    }

    private val battery = FunctionAddress(2, 2)
    private val currentMode = FunctionAddress(31, 3)

    @Test
    fun `SINGLE returns the matching frame and routes the rest to unsolicited`() = runTest {
        val t = FakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        val pushes = ArrayList<BmapPacket>()
        backgroundScope.launch { c.unsolicited.collect { pushes += it } }
        runCurrent() // subscribe before any frame arrives

        // Reply to the battery GET preceded by an unrelated mode push, in one read.
        t.onWrite = { t.deliver("1f030301 02  02020304 50ffff00") }
        val reply = c.request(BmapPacket.get(battery))

        assertEquals(1, reply.size)
        assertEquals(battery, reply[0].address)
        assertEquals(BmapOperator.STATUS, reply[0].operator)
        assertEquals(0x50, reply[0].payload[0].toInt() and 0xFF)
        assertEquals("02020100", t.written.single().joinToString("") { "%02x".format(it) })
        // runCurrent, not advanceUntilIdle: the latter stops once only backgroundScope work is
        // left, and the collector is exactly that.
        runCurrent()
        assertEquals(listOf(currentMode), pushes.map { it.address })
    }

    @Test
    fun `SINGLE skips PROCESSING and returns the frame that follows`() = runTest {
        val t = FakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        t.onWrite = { t.deliver("1f010700") }
        backgroundScope.launch { delay(50); t.deliver("1f010601 00") }

        val reply = c.request(BmapPacket.start(FunctionAddress(31, 1)))
        assertEquals(BmapOperator.RESULT, reply.single().operator)
    }

    @Test
    fun `SINGLE returns an ERROR frame instead of waiting`() = runTest {
        val t = FakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        t.onWrite = { t.deliver("01050401 05") }
        val reply = c.request(BmapPacket.setGet(FunctionAddress(1, 5), byteArrayOf(0, 1)))
        assertTrue(reply.single().isError)
        assertEquals(5, reply.single().errorCode)
    }

    @Test
    fun `MULTI collects the whole block until a quiet gap`() = runTest {
        val t = FakeTransport()
        val c = BmapConnection(t, backgroundScope, quietGapMs = 400, minWriteGapMs = 0)
        val pushes = ArrayList<BmapPacket>()
        backgroundScope.launch { c.unsolicited.collect { pushes += it } }
        runCurrent() // subscribe before any frame arrives

        // [31.6] dump: three STATUS frames for block 31 spread over time, one foreign frame.
        t.onWrite = { t.deliver("1f060301 00") }
        backgroundScope.launch {
            delay(100); t.deliver("1f060301 01")
            delay(100); t.deliver("02020301 50") // battery push: different block, must not extend the gap
            delay(250); t.deliver("1f060301 02") // 350 ms after the last accepted frame: inside the gap
        }
        val reply = c.request(BmapPacket.start(FunctionAddress(31, 6)), ReplyMode.MULTI)

        assertEquals(listOf(0, 1, 2), reply.map { it.payload[0].toInt() })
        advanceUntilIdle()
        assertEquals(listOf(battery), pushes.map { it.address })
    }

    @Test
    fun `MULTI does not arm the quiet gap on the PROCESSING ack`() = runTest {
        val t = FakeTransport()
        val c = BmapConnection(t, backgroundScope, quietGapMs = 400, minWriteGapMs = 0)
        // [31.6] answers PROCESSING at once, then the dump itself only starts well after a quiet gap.
        t.onWrite = { t.deliver("1f060700") }
        backgroundScope.launch {
            delay(900)
            t.deliver("1f060301 00")
            delay(100); t.deliver("1f060301 01")
            delay(100); t.deliver("1f060301 02")
        }
        val reply = c.request(BmapPacket.start(FunctionAddress(31, 6)), ReplyMode.MULTI)

        assertEquals(listOf(0, 1, 2), reply.map { it.payload[0].toInt() })
        assertTrue(reply.none { it.operator == BmapOperator.PROCESSING })
    }

    @Test
    fun `a write that never completes closes the connection instead of pinning the mutex`() = runTest {
        val t = FakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        t.stallWrites = true

        val e = assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(battery), timeoutMs = 1_000) }
        assertTrue("lost" in e.message.orEmpty(), e.message)
        assertFalse(c.isOpen)
        assertFalse(t.isConnected)
        assertEquals("write stalled", (c.closeCause as IOException).message)
        assertTrue(c.awaitClosed() is IOException) // the manager sees a dropped link and reconnects
        // The next caller is not stuck behind the stalled one.
        assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(currentMode), timeoutMs = 1_000) }
        assertTrue(t.written.isEmpty())
    }

    @Test
    fun `timeout throws a no-reply command exception and clears partial frames`() = runTest {
        val t = FakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        t.onWrite = { t.deliver("0202") } // half a header, then silence

        val e = assertFailsWith<HeadphoneCommandException> {
            c.request(BmapPacket.get(battery), timeoutMs = 1_000)
        }
        assertTrue("no reply" in e.message.orEmpty(), e.message)
        assertTrue(c.isOpen)

        // The next request must not be poisoned by the leftover two bytes (which would otherwise
        // combine with the new header into a bogus SET_GET frame at the same address).
        t.onWrite = { t.deliver("02020304 50ffff00") }
        val reply = c.request(BmapPacket.get(battery)).single()
        assertEquals(BmapOperator.STATUS, reply.operator)
        assertEquals(0x50, reply.payload[0].toInt() and 0xFF)
    }

    @Test
    fun `link drop fails the request and closes the connection`() = runTest {
        val t = FakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        t.onWrite = { t.drop(IOException("read failed, socket might closed")) }

        val e = assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(battery)) }
        assertTrue("lost" in e.message.orEmpty(), e.message)
        advanceUntilIdle()
        assertFalse(c.isOpen)
        assertFalse(t.isConnected)
        assertTrue(c.closeCause is IOException)
        assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(battery)) }
    }

    @Test
    fun `requests are serialised and writes are spaced by the minimum gap`() = runTest {
        val t = FakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 80, clock = { testScheduler.currentTime })
        val writeTimes = ArrayList<Long>()
        t.onWrite = { bytes ->
            writeTimes += testScheduler.currentTime
            t.deliver("%02x%02x0301 00".format(bytes[0], bytes[1]))
        }

        val first = launch { c.request(BmapPacket.get(battery)) }
        val second = launch { c.request(BmapPacket.get(currentMode)) }
        advanceUntilIdle()

        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals(listOf(2, 31), t.written.map { it[0].toInt() })
        assertEquals(0L, writeTimes[0])
        assertTrue(writeTimes[1] - writeTimes[0] >= 80, "writes ${writeTimes}ms apart")
    }

    @Test
    fun `local close completes awaitClosed with null`() = runTest {
        val t = FakeTransport()
        val c = BmapConnection(t, backgroundScope)
        c.close()
        assertEquals(null, c.awaitClosed())
        assertFalse(c.isOpen)
    }
}

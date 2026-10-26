package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.jbl.JblBattery
import dev.earshot.protocol.jbl.JblCommands as C
import dev.earshot.protocol.jbl.JblFrame
import dev.earshot.protocol.jbl.JblStatus
import dev.earshot.virtual.VirtualJbl
import dev.earshot.virtual.VirtualJblTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The H-AA link rules in [JblConnection]: the unsolicited device info, frame replay on [attach],
 * request / reply matching without sequence numbers, the dead-link rule (three requests with no
 * frame back), the 450 ms write pace, the write guard, the heartbeat echo and the close paths.
 * Most tests run against [VirtualJbl] (a Wave Buds 2 as jbl-control captured it); the ones that
 * need exact control of what arrives and when use a hand-fed transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JblConnectionTest {

    @Before
    fun quiet() {
        JblLog.sink = { _, _, _ -> }
        BtLog.sink = { _, _, _ -> }
    }

    private fun hex(s: String): ByteArray = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

    // The captured frames of jbl-control's Wave Buds 2 (also in JblWireTest and VirtualJblTest).
    private val rxDeviceInfo = "aa 12 25 4a 42 4c 20 57 61 76 65 20 42 75 64 73 20 32 00 00 21 00 64 ff 2d 2f 6a 1b 50 02 07 00 64 f2 96 10 ff ff 02 00"
    private val rxModes = "aa 91 07 12 01 00 02 01 03 00"
    private val rxVoicePrompts = "aa 93 02 05 01"
    private val rxCallPush = "aa 06 05 00 02 00 1f 01 00"
    private val rxInfo = "aa 06 41 00 02 00 01 10 4a 42 4c 20 57 61 76 65 20 42 75 64 73 20 32 00 00 05 03 02 07 00 00 06 01 06 00 0b 03 00 00 00 00 0c 02 00 00 00 0d 02 00 02 00 1a 02 00 64 00 1b 02 00 ff 00 1c 02 00 64 00 20 01 00"
    private val heartbeat = "aa b1 04 01 00 01 00"

    /** A hand-fed link: the test decides what arrives and when; every write is kept, with the virtual time of it. */
    private class ManualTransport(private val now: () -> Long = { 0L }) : ByteTransport {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val written = mutableListOf<ByteArray>()
        val writeTimes = mutableListOf<Long>()

        /** Called after each write with the number of writes so far (1 = the first). */
        var onWrite: (Int) -> Unit = {}
        var writeError: IOException? = null

        override var isConnected: Boolean = true
        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

        override suspend fun write(bytes: ByteArray) {
            writeError?.let { throw it }
            written += bytes
            writeTimes += now()
            onWrite(written.size)
        }

        fun deliver(vararg chunks: ByteArray) = chunks.forEach { inbound.trySend(it) }

        /** The link drops underneath the connection (an error, not a close). */
        fun drop(cause: Throwable) = inbound.close(cause)

        override fun close() {
            isConnected = false
            inbound.close()
        }
    }

    /** Wraps a link and notes the virtual time of each write. */
    private class TimedTransport(private val inner: ByteTransport, private val now: () -> Long) : ByteTransport by inner {
        val writes = mutableListOf<Long>()

        override suspend fun write(bytes: ByteArray) {
            writes += now()
            inner.write(bytes)
        }
    }

    private class Rig(val unit: VirtualJbl, val transport: VirtualJblTransport, val conn: JblConnection)

    private fun TestScope.connect(transport: ByteTransport) = JblConnection(transport, backgroundScope, clock = { testScheduler.currentTime })

    private fun TestScope.manual() = ManualTransport { testScheduler.currentTime }

    private fun TestScope.rig(unit: VirtualJbl = VirtualJbl()): Rig {
        val transport = VirtualJblTransport(unit)
        return Rig(unit, transport, connect(transport))
    }

    private fun TestScope.timedRig(unit: VirtualJbl = VirtualJbl()): Pair<TimedTransport, JblConnection> {
        val timed = TimedTransport(VirtualJblTransport(unit)) { testScheduler.currentTime }
        return timed to connect(timed)
    }

    private fun ManualTransport.deliverHex(s: String) = deliver(hex(s))

    private val modeStatus: (JblFrame) -> Boolean = { it.command == C.LISTENING && it.sub == C.MODE_STATUS }
    private val levelStatus: (JblFrame) -> Boolean = { it.command == C.LISTENING && it.sub == C.LEVEL_STATUS }

    // ---- Device info and attach ----------------------------------------------------------------

    @Test fun `the device info the headset sends by itself is parsed`() = runTest {
        val r = rig()
        assertNull(r.conn.deviceInfo, "nothing has been collected yet")
        val info = assertNotNull(r.conn.awaitDeviceInfo(1_500))
        assertEquals("JBL Wave Buds 2", info.name)
        assertEquals(0x2100, info.productId)
        assertEquals("2.7.0", info.firmware)
        assertEquals(JblBattery(100, false), info.battery)
        assertEquals(info, r.conn.deviceInfo)
        assertEquals(1L, r.conn.framesReceived)
        assertEquals(0L, testScheduler.currentTime, "it was there at once, nothing waited")
        assertEquals(info, r.conn.awaitDeviceInfo(1_500), "known info is returned without waiting")
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(r.unit.received.isEmpty(), "the headset volunteered it: nothing was asked")
    }

    @Test fun `awaitDeviceInfo gives up after its timeout and wakes up for a late frame`() = runTest {
        val t = manual()
        val conn = connect(t)
        assertNull(conn.awaitDeviceInfo(1_500))
        assertEquals(1_500L, testScheduler.currentTime)
        assertTrue(conn.isOpen, "a missing device info is not a dead link")

        val late = async { conn.awaitDeviceInfo(5_000) }
        testScheduler.runCurrent()
        delay(1_000)
        t.deliverHex(rxDeviceInfo)
        assertEquals("JBL Wave Buds 2", assertNotNull(late.await()).name)
        assertEquals(2_500L, testScheduler.currentTime)
    }

    @Test fun `attach replays the frames that came before it, once, then follows the link`() = runTest {
        val r = rig()
        r.unit.pushMode(C.WIRE_ANC)
        r.unit.pushBattery(80, 70, 60)
        testScheduler.runCurrent()

        val seen = mutableListOf<JblFrame>()
        r.conn.attach { seen += it }
        assertEquals(listOf(C.DEVICE_INFO, C.LISTENING, C.INFO), seen.map { it.command }, "in arrival order")
        assertEquals(C.WIRE_ANC, JblStatus().fold(seen[1]).activeMode)

        r.unit.pushMode(C.WIRE_TALKTHRU)
        testScheduler.runCurrent()
        assertEquals(listOf(C.DEVICE_INFO, C.LISTENING, C.INFO, C.LISTENING), seen.map { it.command })
        assertEquals(C.WIRE_TALKTHRU, JblStatus().fold(seen.last()).activeMode)

        val again = mutableListOf<JblFrame>()
        r.conn.attach { again += it }
        assertTrue(again.isEmpty(), "replayed frames are handed over only once")
        r.unit.pushMode(null)
        testScheduler.runCurrent()
        assertEquals(1, again.size)
        assertEquals(4, seen.size, "the first callback was replaced")
    }

    // ---- Requests ------------------------------------------------------------------------------

    @Test fun `a request returns the matching reply, and the callback sees it too`() = runTest {
        val r = rig(VirtualJbl(address = hex("ff 2d 2f 6a 1b 50")))
        val seen = mutableListOf<JblFrame>()
        r.conn.attach { seen += it }

        val modes = assertNotNull(r.conn.request(C.modeGet(), answers = modeStatus))
        assertEquals(rxModes, modes.encode().hex())
        assertEquals(C.WIRE_AMBIENT, JblStatus().fold(modes).activeMode)

        val info = assertNotNull(r.conn.request(C.infoGet(C.INFO_READ_FIDS - C.FID_BATTERY_HEADBAND)) { it.command == C.INFO && it.sub == 0x02 })
        assertEquals(rxInfo, info.encode().hex())

        // The session folds replies and pushes the same way, so both reach the callback in order.
        assertEquals(listOf(C.DEVICE_INFO, C.LISTENING, C.INFO), seen.map { it.command })
        assertEquals(listOf("aa 91 01 11", "aa 06 15 00 01 00 01 00 05 00 06 00 0b 00 0c 00 0d 00 1a 00 1b 00 1c 00 20"), r.unit.received.map { it.encode().hex() })
    }

    @Test fun `a frame of the wrong kind is no match, and still proves the link is alive`() = runTest {
        val r = rig()
        r.conn.awaitDeviceInfo(1_000)
        // The buds answer a mode read with a mode status, which this predicate refuses: five times, far
        // past the three silent requests that close a link.
        repeat(5) {
            assertNull(r.conn.request(C.modeGet(), timeoutMs = 700) { it.command == C.SERIAL })
        }
        assertTrue(r.conn.isOpen)
        assertEquals(6L, r.conn.framesReceived)
    }

    @Test fun `a silent read returns null after the timeout and does not close the link`() = runTest {
        val r = rig()
        r.conn.awaitDeviceInfo(1_000)
        val start = testScheduler.currentTime
        // A Wave Buds 2 never answers the level read.
        assertNull(r.conn.request(C.levelGet(), answers = levelStatus))
        assertEquals(JblConnection.REPLY_TIMEOUT_MS, testScheduler.currentTime - start)
        assertTrue(r.conn.isOpen)
        assertEquals(listOf(C.LISTENING), r.unit.received.map { it.command })
        // The link works afterwards.
        assertNotNull(r.conn.request(C.modeGet(), answers = modeStatus))
    }

    @Test fun `three requests in a row with no frame at all close the link as unresponsive`() = runTest {
        val t = manual()
        val conn = connect(t)
        repeat(2) { assertNull(conn.request(C.modeGet(), answers = modeStatus)) }
        assertTrue(conn.isOpen)

        val error = assertFailsWith<HeadphoneCommandException> { conn.request(C.modeGet(), answers = modeStatus) }
        assertEquals("The headphones didn't answer (Listening mode)", error.message)
        assertFalse(conn.isOpen)
        assertFalse(t.isConnected, "the transport is closed with the link")
        assertIs<UnresponsiveLinkException>(conn.awaitClosed())
        assertEquals(3, t.written.size)
        // Closed: no further request is even written.
        val later = assertFailsWith<HeadphoneCommandException> { conn.request(C.modeGet(), answers = modeStatus) }
        assertEquals(JblConnection.NOT_CONNECTED, later.message)
        assertEquals(3, t.written.size)
    }

    @Test fun `every unanswered attempt of one request counts, as the device-info probe relies on`() = runTest {
        val t = manual()
        val conn = connect(t)
        // Two attempts: two silent sends, the link survives.
        assertNull(conn.request(C.deviceInfoGet(), timeoutMs = 2_000, attempts = 2) { it.command == C.DEVICE_INFO })
        assertTrue(conn.isOpen)
        // The next silent send is the third in a row: the link closes inside the request and the
        // two attempts it had left are not sent.
        assertFailsWith<HeadphoneCommandException> {
            conn.request(C.deviceInfoGet(), timeoutMs = 2_000, attempts = 3) { it.command == C.DEVICE_INFO }
        }
        assertFalse(conn.isOpen)
        assertEquals(3, t.written.size, "the dead link stops the retries")
        assertIs<UnresponsiveLinkException>(conn.awaitClosed())
    }

    @Test fun `any frame from the headset resets the count of silent requests`() = runTest {
        val r = rig()
        r.conn.awaitDeviceInfo(1_000)
        repeat(2) { assertNull(r.conn.request(C.levelGet(), answers = levelStatus)) }
        // A reply (any frame) shows the link is alive.
        assertNotNull(r.conn.request(C.modeGet(), answers = modeStatus))
        repeat(2) { assertNull(r.conn.request(C.levelGet(), answers = levelStatus)) }
        assertTrue(r.conn.isOpen, "four silent requests, but never three in a row")
        // A push between two silent requests counts as well.
        r.unit.pushMode(C.WIRE_ANC)
        testScheduler.runCurrent()
        assertNull(r.conn.request(C.levelGet(), answers = levelStatus))
        assertTrue(r.conn.isOpen)
        // Three in a row now close it.
        assertNull(r.conn.request(C.levelGet(), answers = levelStatus))
        assertFailsWith<HeadphoneCommandException> { r.conn.request(C.levelGet(), answers = levelStatus) }
        assertFalse(r.conn.isOpen)
    }

    @Test fun `a retry that is answered returns the reply`() = runTest {
        val t = manual()
        val conn = connect(t)
        // Only the second send gets an answer.
        t.onWrite = { n -> if (n == 2) t.deliverHex(rxModes) }
        val reply = assertNotNull(conn.request(C.modeGet(), attempts = 2, answers = modeStatus))
        assertEquals(rxModes, reply.encode().hex())
        assertEquals(2, t.written.size)
        assertEquals(listOf("aa 91 01 11", "aa 91 01 11"), t.written.map { it.hex() })
        assertEquals(JblConnection.REPLY_TIMEOUT_MS, testScheduler.currentTime, "one timeout, then the second send")
        assertTrue(conn.isOpen)
    }

    @Test fun `requests run one at a time`() = runTest {
        val t = manual()
        val conn = connect(t)
        val modes = async { conn.request(C.modeGet(), answers = modeStatus) }
        val prompts = async { conn.request(C.voicePromptsGet()) { it.command == C.VOICE_PROMPTS } }
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(1, t.written.size, "the second request waits for the first one's reply")

        t.deliverHex(rxModes)
        assertEquals(rxModes, assertNotNull(modes.await()).encode().hex())
        testScheduler.runCurrent()
        assertEquals(listOf("aa 91 01 11", "aa 93 01 04"), t.written.map { it.hex() })
        t.deliverHex(rxVoicePrompts)
        assertEquals(rxVoicePrompts, assertNotNull(prompts.await()).encode().hex())
    }

    // ---- Pacing --------------------------------------------------------------------------------

    @Test fun `writes are paced at least 450 ms apart in virtual time`() = runTest {
        val (timed, conn) = timedRig()
        conn.send(C.modeGet())
        conn.send(C.voicePromptsGet())
        conn.send(C.eqGet())
        assertEquals(listOf(0L, 450L, 900L), timed.writes, "the first write does not wait")
    }

    @Test fun `concurrent senders are paced one after the other`() = runTest {
        val (timed, conn) = timedRig()
        val jobs = List(3) { launch { conn.send(C.modeGet()) } }
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertTrue(jobs.all { it.isCompleted })
        assertEquals(listOf(0L, 450L, 900L), timed.writes)
    }

    @Test fun `a write only waits for what is left of the gap`() = runTest {
        val (timed, conn) = timedRig()
        conn.send(C.modeGet())
        delay(300)
        conn.send(C.modeGet())
        delay(1_000)
        conn.send(C.modeGet())
        assertEquals(listOf(0L, 450L, 1_450L), timed.writes, "150 ms of waiting, then none")
    }

    // ---- The write guard -----------------------------------------------------------------------

    @Test fun `a frame the guard refuses throws and never reaches the transport`() = runTest {
        val r = rig()
        r.conn.awaitDeviceInfo(1_000)
        val refused = listOf(
            JblFrame(0x95), // factory reset
            JblFrame(0x97), // power off
            JblFrame(0x95, byteArrayOf(1)),
            JblFrame(0xF0, byteArrayOf(1, 2, 3)), // firmware transfer
            JblFrame(0xA4), JblFrame(0xA3), JblFrame(0x15), JblFrame(0x74), JblFrame(0x61), JblFrame(0xB0),
            JblFrame(0x42, byteArrayOf(1)), // an unknown command
            JblFrame(C.LISTENING, byteArrayOf(0x10, 1, 1, 2, 1)), // two modes on at once
            JblFrame(C.LISTENING, byteArrayOf(0x20, 0x04, 9)), // a level outside 1..7
            JblFrame(C.VOICE_PROMPTS, byteArrayOf(0x03, 2)),
            JblFrame(C.INFO, byteArrayOf(0x00, 0x00, 0x01, 0x01, 0x00), longLength = true), // a TLV set
        )
        for (f in refused) {
            val e = assertFailsWith<HeadphoneCommandException>("$f") { r.conn.send(f) }
            assertTrue("doesn't send that command to JBL headphones" in e.message.orEmpty(), e.message)
            assertFailsWith<HeadphoneCommandException>("$f as a request") { r.conn.request(f) { true } }
        }
        assertEquals("Earshot doesn't send that command to JBL headphones (AA 95)", assertFailsWith<HeadphoneCommandException> { r.conn.send(JblFrame(0x95)) }.message)
        assertTrue(r.unit.received.isEmpty(), "nothing was written: ${r.unit.received}")
        assertTrue(r.conn.isOpen, "a refusal is not a dead link, even past three of them")

        // An allowed frame still goes out.
        r.conn.send(C.modeGet())
        assertEquals(listOf(C.LISTENING), r.unit.received.map { it.command })
    }

    @Test fun `a refused frame costs no pacing and no write time`() = runTest {
        val (timed, conn) = timedRig()
        conn.send(C.modeGet())
        assertFailsWith<HeadphoneCommandException> { conn.send(JblFrame(0x97)) }
        assertEquals(listOf(0L), timed.writes)
        assertEquals(0L, testScheduler.currentTime, "the refusal came before the pace delay")
    }

    // ---- Heartbeat -----------------------------------------------------------------------------

    @Test fun `the heartbeat is echoed once the device info is known`() = runTest {
        val r = rig()
        testScheduler.runCurrent() // the device info arrives
        r.unit.pushHeartbeat()
        testScheduler.runCurrent()
        assertEquals(listOf(C.heartbeatEcho()), r.unit.received)
        assertEquals(1, r.unit.heartbeatEchoes)
        assertEquals(heartbeat, r.unit.received.single().encode().hex())
    }

    @Test fun `a heartbeat before the device info is not echoed`() = runTest {
        val t = manual()
        val conn = connect(t)
        t.deliverHex(heartbeat)
        testScheduler.runCurrent()
        assertTrue(t.written.isEmpty(), "JBL's app answers only after the device info")
        assertNull(conn.deviceInfo)

        t.deliverHex(rxDeviceInfo)
        testScheduler.runCurrent()
        assertTrue(t.written.isEmpty(), "the device info alone triggers nothing")
        t.deliverHex(heartbeat)
        testScheduler.runCurrent()
        assertEquals(listOf(heartbeat), t.written.map { it.hex() })
    }

    @Test fun `heartbeat echoes follow the write pace`() = runTest {
        val unit = VirtualJbl()
        val timed = TimedTransport(VirtualJblTransport(unit)) { testScheduler.currentTime }
        val conn = connect(timed)
        testScheduler.runCurrent()
        unit.pushHeartbeat()
        unit.pushHeartbeat()
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(2, unit.heartbeatEchoes)
        assertEquals(listOf(0L, 450L), timed.writes)
        assertTrue(conn.isOpen)
    }

    // ---- Closing -------------------------------------------------------------------------------

    @Test fun `closing the transport completes awaitClosed, and later sends throw`() = runTest {
        val r = rig()
        testScheduler.runCurrent()
        assertTrue(r.conn.isOpen)
        r.transport.close()
        assertNull(r.conn.awaitClosed(), "a clean end of the stream is no error")
        assertFalse(r.conn.isOpen)

        val sent = assertFailsWith<HeadphoneCommandException> { r.conn.send(C.modeGet()) }
        assertEquals("Headphones not connected", sent.message)
        val asked = assertFailsWith<HeadphoneCommandException> { r.conn.request(C.modeGet()) { true } }
        assertEquals("Headphones not connected", asked.message)
        assertTrue(r.unit.received.isEmpty())
        // A refused frame on a closed link still reports the closed link first.
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { r.conn.send(JblFrame(0x95)) }.message)
    }

    @Test fun `closing the connection closes the transport and is safe twice`() = runTest {
        val r = rig()
        r.conn.close()
        assertFalse(r.transport.isConnected)
        assertNull(r.conn.awaitClosed())
        r.conn.close()
        assertFalse(r.conn.isOpen)
    }

    @Test fun `a link that drops with an error completes awaitClosed with that error`() = runTest {
        val t = manual()
        val conn = connect(t)
        t.drop(IOException("gatt disconnected"))
        // By type and message: the coroutines library may hand the collector a stack-recovered copy.
        val cause = conn.awaitClosed()
        assertIs<IOException>(cause)
        assertEquals("gatt disconnected", cause.message)
        assertFalse(conn.isOpen)
        assertFalse(t.isConnected)
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { conn.send(C.modeGet()) }.message)
    }

    @Test fun `a failed write closes the link and says it was lost`() = runTest {
        val t = manual()
        val conn = connect(t)
        t.writeError = IOException("gatt write failed")
        val e = assertFailsWith<HeadphoneCommandException> { conn.send(C.modeGet()) }
        assertEquals("Connection to the headphones was lost", e.message)
        assertIs<IOException>(e.cause)
        assertFalse(conn.isOpen)
        assertEquals("gatt write failed", conn.awaitClosed()?.message)
        assertFalse(t.isConnected)
    }

    // ---- Frames split across chunks ------------------------------------------------------------

    @Test fun `frames that arrive a byte at a time are put together`() = runTest {
        val t = manual()
        val conn = connect(t)
        val seen = mutableListOf<JblFrame>()
        conn.attach { seen += it }
        for (frame in listOf(rxDeviceInfo, rxInfo, rxModes)) for (b in hex(frame)) t.deliver(byteArrayOf(b))
        testScheduler.runCurrent()
        assertEquals(listOf(C.DEVICE_INFO, C.INFO, C.LISTENING), seen.map { it.command })
        assertTrue(seen.none { it.truncated })
        assertEquals(rxInfo, seen[1].encode().hex(), "the long length form survived the split")
        assertEquals("JBL Wave Buds 2", conn.deviceInfo?.name)
        assertEquals(3L, conn.framesReceived)
    }

    @Test fun `a two-byte length header cut in the middle is waited out`() = runTest {
        val t = manual()
        val conn = connect(t)
        val seen = mutableListOf<JblFrame>()
        conn.attach { seen += it }
        val bytes = hex(rxInfo)
        t.deliver(bytes.copyOfRange(0, 3)) // aa 06 41: the high length byte is still missing
        testScheduler.runCurrent()
        assertTrue(seen.isEmpty())
        t.deliver(bytes.copyOfRange(3, 30))
        testScheduler.runCurrent()
        assertTrue(seen.isEmpty())
        t.deliver(bytes.copyOfRange(30, bytes.size))
        testScheduler.runCurrent()
        assertEquals(listOf(rxInfo), seen.map { it.encode().hex() })
    }

    @Test fun `frames joined in one notification are all delivered in order`() = runTest {
        val t = manual()
        val conn = connect(t)
        val seen = mutableListOf<JblFrame>()
        conn.attach { seen += it }
        t.deliver(hex("$rxModes $rxVoicePrompts $rxCallPush"))
        testScheduler.runCurrent()
        assertEquals(listOf(C.LISTENING, C.VOICE_PROMPTS, C.INFO), seen.map { it.command })
        assertEquals(3L, conn.framesReceived)
    }

    @Test fun `bytes outside a frame are dropped`() = runTest {
        val t = manual()
        val conn = connect(t)
        val seen = mutableListOf<JblFrame>()
        conn.attach { seen += it }
        t.deliver(hex("01 02 $rxVoicePrompts"))
        testScheduler.runCurrent()
        assertEquals(listOf(rxVoicePrompts), seen.map { it.encode().hex() })
    }

    @Test fun `a frame cut by a small MTU comes out truncated and the next one is intact`() = runTest {
        val t = manual()
        val conn = connect(t)
        val seen = mutableListOf<JblFrame>()
        conn.attach { seen += it }
        val info = hex(rxDeviceInfo)
        t.deliver(info.copyOfRange(0, 20)) // the 20 bytes a default MTU carries
        t.deliver(hex(rxModes))
        testScheduler.runCurrent()
        assertEquals(listOf(true, false), seen.map { it.truncated })
        assertEquals(listOf(C.DEVICE_INFO, C.LISTENING), seen.map { it.command })
        assertEquals(C.WIRE_AMBIENT, JblStatus().fold(seen[1]).activeMode)
        // The fields before the cut are real: the name came, the product id did not.
        assertEquals("JBL Wave Buds 2", conn.deviceInfo?.name)
        assertNull(conn.deviceInfo?.productId)
    }

    @Test fun `a frame left incomplete when the link ends is delivered as truncated`() = runTest {
        val t = manual()
        val conn = connect(t)
        val seen = mutableListOf<JblFrame>()
        conn.attach { seen += it }
        t.deliver(hex(rxModes).copyOfRange(0, 6))
        testScheduler.runCurrent()
        assertTrue(seen.isEmpty())
        t.close()
        assertNull(conn.awaitClosed())
        assertEquals(listOf(true), seen.map { it.truncated })
        assertEquals(C.LISTENING, seen.single().command)
    }

    // ---- The device-info probe the link factory runs --------------------------------------------

    @Test fun `the probe takes the unsolicited device info without sending anything`() = runTest {
        val r = rig()
        val info = assertNotNull(JblLinkFactory.probe(r.conn))
        assertEquals(0x2100, info.productId)
        assertTrue(r.unit.received.isEmpty())
        JblLinkFactory.checkProduct(info) // a Wave Buds 2 passes
    }

    @Test fun `the probe asks for the device info when the headset stays quiet`() = runTest {
        val t = manual()
        t.onWrite = { t.deliverHex(rxDeviceInfo) }
        val conn = connect(t)
        val info = assertNotNull(JblLinkFactory.probe(conn))
        assertEquals("JBL Wave Buds 2", info.name)
        assertEquals(listOf("aa 11 00"), t.written.map { it.hex() })
        assertEquals(JblLinkFactory.UNSOLICITED_INFO_MS, testScheduler.currentTime)
    }

    @Test fun `the probe finds nothing on a silent link and leaves it open for the factory to close`() = runTest {
        val t = manual()
        val conn = connect(t)
        assertNull(JblLinkFactory.probe(conn))
        assertEquals(2, t.written.size, "two attempts")
        assertTrue(conn.isOpen, "two silent sends are not yet a dead link")
    }

    @Test fun `a headset of another JBL stack is refused after the probe`() = runTest {
        val r = rig(VirtualJbl(productId = 0x2101)) // a Protocol 4 model
        val info = assertNotNull(JblLinkFactory.probe(r.conn))
        val e = assertFailsWith<RfcommConnectException> { JblLinkFactory.checkProduct(info) }
        assertFalse(e.looksBusy)
    }

    // ---- Link loss while a call is waiting -------------------------------------------------------
    //
    // BUG (JblConnection, not fixed here, see the report): when the link ends while a request or an
    // awaitDeviceInfo is waiting, finish() cancels the waiter and the caller gets a bare
    // CancellationException instead of a HeadphoneCommandException. ConnectionManager rethrows a
    // CancellationException from factory.open() as its own cancellation, so a link that drops during
    // the probe would end the connect loop without a state change or a retry. The fix is to catch
    // the waiter's cancellation when `!isOpen && currentCoroutineContext().isActive` and throw
    // HeadphoneCommandException(NOT_CONNECTED), as BmapConnection.writeSpaced does. These two tests
    // pin today's behaviour; when the fix lands they should expect HeadphoneCommandException.

    @Test fun `BUG - a request waiting when the link ends fails with a bare CancellationException`() = runTest {
        val t = manual()
        val conn = connect(t)
        val pending = async { runCatching { conn.request(C.modeGet(), answers = modeStatus) } }
        testScheduler.runCurrent()
        assertEquals(1, t.written.size, "the request is out and waiting")
        t.close()
        assertIs<CancellationException>(pending.await().exceptionOrNull())
        assertFalse(conn.isOpen)
    }

    @Test fun `BUG - awaitDeviceInfo waiting when the link ends fails with a bare CancellationException`() = runTest {
        val t = manual()
        val conn = connect(t)
        val pending = async { runCatching { conn.awaitDeviceInfo(1_500) } }
        testScheduler.runCurrent()
        t.close()
        assertIs<CancellationException>(pending.await().exceptionOrNull())
    }
}

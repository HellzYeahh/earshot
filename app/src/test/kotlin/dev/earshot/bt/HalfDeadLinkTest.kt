package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.hexToBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A channel-2 socket that connects but never answers: what the Galaxy (Android 17, firmware
 * 10.12) gave Earshot while the Bose app ran in the background. [answer] scripts replies per write.
 */
private class SilentFakeTransport : ByteTransport {
    var answer: (ByteArray) -> String? = { null }
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)

    override var isConnected: Boolean = true
    override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        answer(bytes)?.let { deliver(it) }
    }

    override fun close() {
        isConnected = false
        inbound.close()
    }

    fun deliver(hex: String) { inbound.trySend(hex.replace(" ", "").hexToBytes()) }
    fun drop(cause: IOException) { inbound.close(cause) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HalfDeadLinkTest {

    @Before
    fun silenceLog() {
        BtLog.sink = { _, _, _ -> }
    }

    private val battery = FunctionAddress(2, 2)
    private val charging = FunctionAddress(2, 3)
    private val productId = FunctionAddress(0, 3)

    // ---- connect-time probe -------------------------------------------------------------

    @Test
    fun `a silent probe closes the socket and reports the busy channel`() = runTest {
        val t = SilentFakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)

        val e = assertFailsWith<RfcommConnectException> { LivenessProbe.check(c) }
        assertTrue(e.looksBusy)
        assertFalse(c.isOpen)
        assertFalse(t.isConnected)
        assertEquals(LivenessProbe.TIMEOUT_MS, testScheduler.currentTime) // one probe, one timeout
        // What the manager shows while the phone still has the headphones linked for audio...
        assertEquals(ConnectionManager.MESSAGE_CHANNEL_BUSY, SessionEnd.connectFailureMessage(e, linked = true))
        assertEquals(ConnectionManager.MESSAGE_CHANNEL_BUSY, SessionEnd.connectFailureMessage(e, linked = null))
        // ...and not when the headphones are simply gone.
        assertEquals(SessionEnd.MESSAGE_NOT_LINKED, SessionEnd.connectFailureMessage(e, linked = false))
    }

    @Test
    fun `a probe that gets any reply, even an ERROR, passes`() = runTest {
        val t = SilentFakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        t.answer = { "02020401 04" } // [2.2] ERROR FuncNotSupp: still somebody listening

        LivenessProbe.check(c)
        assertTrue(c.isOpen)
        assertTrue(c.hasAnswered)
    }

    @Test
    fun `a probe that sees other traffic but no battery reply passes`() = runTest {
        val t = SilentFakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        t.answer = { "1f030301 02" } // a mode push instead of the battery STATUS

        LivenessProbe.check(c)
        assertTrue(c.isOpen)
    }

    @Test
    fun `a link that drops during the probe is not mistaken for a busy channel`() = runTest {
        val t = SilentFakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        t.answer = { t.drop(IOException("read failed, socket might closed")); null }

        val e = assertFailsWith<IOException> { LivenessProbe.check(c) }
        assertFalse(e is RfcommConnectException && e.looksBusy, e.toString())
        assertFalse(c.isOpen)
    }

    // ---- mid-session watchdog -----------------------------------------------------------

    @Test
    fun `three silent replies mid-session close the connection as unresponsive`() = runTest {
        val t = SilentFakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        // A healthy start: the probe and one command are answered.
        t.answer = { bytes -> "%02x%02x0301 50".format(bytes[0], bytes[1]) }
        LivenessProbe.check(c)
        c.request(BmapPacket.get(battery))

        // Then the far end goes deaf (the Bose app grabbed the channel).
        t.answer = { null }
        val first = assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(battery), timeoutMs = 2_000) }
        assertTrue("no reply" in first.message.orEmpty(), first.message)
        assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(charging), timeoutMs = 2_000) }
        assertTrue(c.isOpen, "two timeouts are tolerated")
        val third = assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(productId), timeoutMs = 2_000) }

        assertEquals(BmapConnection.MESSAGE_STOPPED_ANSWERING, third.message)
        assertFalse(c.isOpen)
        assertFalse(t.isConnected)
        val cause = c.awaitClosed()
        assertIs<UnresponsiveLinkException>(cause)
        // Later commands fail at once instead of each burning another timeout.
        val before = testScheduler.currentTime
        assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(battery)) }
        assertEquals(before, testScheduler.currentTime)

        // The manager turns that end into DISCONNECTED + the busy message, as a failed attempt.
        val end = SessionEnd.classify(cause, userStopped = false, linked = true)
        assertEquals(ConnectionManager.MESSAGE_CHANNEL_BUSY, end.message)
        assertTrue(end.countsAsFailedAttempt)
    }

    @Test
    fun `three silent unknown functions while 0_1 still answers keep the link and mark them absent`() = runTest {
        val t = SilentFakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        // Older firmware: no ERROR for functions it lacks, but the known-good [0.1] answers.
        t.answer = { bytes -> if (bytes[0] == 0.toByte() && bytes[1] == 1.toByte()) "00010305 312e322e30" else null }
        val absent = listOf(FunctionAddress(1, 24), FunctionAddress(1, 27), FunctionAddress(1, 3))
        for (a in absent) {
            val e = assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(a), timeoutMs = 2_000) }
            assertTrue("no reply" in e.message.orEmpty(), e.message)
        }
        assertTrue(c.isOpen, "a healthy link must not be closed for silent functions")
        assertEquals(absent.toSet(), c.silentFunctions)
        // The count started over: three more silent requests are tolerated the same way.
        repeat(2) { assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(FunctionAddress(5, 20 + it)), timeoutMs = 2_000) } }
        assertTrue(c.isOpen)
        // Commands that work keep working.
        t.answer = { "02020301 50" }
        c.request(BmapPacket.get(battery))
    }

    @Test
    fun `any incoming byte resets the silent count`() = runTest {
        val t = SilentFakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        repeat(4) {
            t.answer = { null }
            assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(battery), timeoutMs = 1_000) }
            assertFailsWith<HeadphoneCommandException> { c.request(BmapPacket.get(charging), timeoutMs = 1_000) }
            t.answer = { "02020301 50" }
            c.request(BmapPacket.get(battery))
        }
        assertTrue(c.isOpen)
    }

    @Test
    fun `unwatched requests never trip the watchdog`() = runTest {
        val t = SilentFakeTransport()
        val c = BmapConnection(t, backgroundScope, minWriteGapMs = 0)
        repeat(5) {
            assertFailsWith<HeadphoneCommandException> { c.requestUnwatched(BmapPacket.get(FunctionAddress(5, it)), timeoutMs = 500) }
        }
        assertTrue(c.isOpen, "a Lab scan over silent functions must keep the link")
    }

    @Test
    fun `ordinary session ends keep their old messages`() {
        assertEquals(SessionEnd.MESSAGE_CONNECTION_LOST, SessionEnd.classify(IOException("read failed"), userStopped = false, linked = true).message)
        assertFalse(SessionEnd.classify(IOException("read failed"), userStopped = false, linked = true).countsAsFailedAttempt)
        assertNull(SessionEnd.classify(null, userStopped = true, linked = null).message)
        assertEquals(SessionEnd.MESSAGE_NOT_LINKED, SessionEnd.classify(UnresponsiveLinkException("x"), userStopped = false, linked = false).message)
        assertEquals(SessionEnd.MESSAGE_CONNECT_FAILED, SessionEnd.connectFailureMessage(RfcommConnectException("refused", looksBusy = false), linked = true))
    }
}

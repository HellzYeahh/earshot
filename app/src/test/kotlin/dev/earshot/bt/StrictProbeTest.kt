package dev.earshot.bt

import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.hexToBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A socket on some RFCOMM channel: [answer] scripts what comes back per write, [chatter] what it says unprompted. */
internal class ScriptedTransport(
    private val answer: (ByteArray) -> String? = { null },
    chatter: String? = null,
) : ByteTransport {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    override var isConnected: Boolean = true
    override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

    init {
        chatter?.let(::deliver)
    }

    override suspend fun write(bytes: ByteArray) {
        answer(bytes)?.let(::deliver)
    }

    override fun close() {
        isConnected = false
        inbound.close()
    }

    fun deliver(hex: String) {
        inbound.trySend(hex.replace(" ", "").hexToBytes())
    }

    companion object {
        /** `[0.1]` STATUS "1.2.0": what a BMAP channel says to the probe. */
        const val BMAP_VERSION_REPLY = "00010305 312e322e30"
        /** wolverine channel 14 (bosectl #22): decodes as a SET_GET on block 0xFF. */
        const val BEACON = "ff550203 010203 ff550203 010203"
        /** channel 22: a log stream that "dumps data regardless of input". */
        const val LOG = "5b3132333435365d206274206c696e6b207570 0a 5b3132333435375d2061636c20636f6e6e6563746564 0a"

        fun bmap() = ScriptedTransport(answer = { bytes -> if (bytes[0] == 0.toByte() && bytes[1] == 1.toByte()) BMAP_VERSION_REPLY else null })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StrictProbeTest {

    @Before
    fun silenceLog() {
        BtLog.sink = { _, _, _ -> }
    }

    private fun TestScope.connection(t: ByteTransport) = BmapConnection(t, backgroundScope, minWriteGapMs = 0)

    // ── the pure decision ────────────────────────────────────────────────────────────────

    private fun frame(hex: String) = BmapPacket.decodeAll(hex.replace(" ", "").hexToBytes()).single()

    @Test
    fun `an answer to the probe itself is alive, STATUS or ERROR`() {
        val none = BmapConnection.Traffic(bytes = 9, deviceFrames = 1, oddFrames = 0, junkBytes = 0)
        assertSame(LivenessProbe.Verdict.Alive, LivenessProbe.judge(frame(ScriptedTransport.BMAP_VERSION_REPLY), none))
        assertSame(LivenessProbe.Verdict.Alive, LivenessProbe.judge(frame("00010401 04"), none))
    }

    @Test
    fun `an echo of the request is not an answer`() {
        val echo = frame("00010100")
        assertEquals(BmapOperator.GET, echo.operator)
        val traffic = BmapConnection.Traffic(bytes = 4, deviceFrames = 0, oddFrames = 1, junkBytes = 0)
        assertIs<LivenessProbe.Verdict.NotBmap>(LivenessProbe.judge(echo, traffic))
    }

    @Test
    fun `no reply and no bytes is silent, bytes that are not BMAP are not alive`() {
        assertSame(LivenessProbe.Verdict.Silent, LivenessProbe.judge(null, BmapConnection.Traffic(0, 0, 0, 0)))
        assertIs<LivenessProbe.Verdict.NotBmap>(LivenessProbe.judge(null, BmapConnection.Traffic(14, 2, 1, 0)))
        assertIs<LivenessProbe.Verdict.NotBmap>(LivenessProbe.judge(null, BmapConnection.Traffic(40, 0, 0, 40)))
        // A push from a known block that crossed the probe still proves BMAP.
        assertSame(LivenessProbe.Verdict.Alive, LivenessProbe.judge(null, BmapConnection.Traffic(5, 1, 0, 0)))
    }

    // ── over a connection ────────────────────────────────────────────────────────────────

    @Test
    fun `the channel 14 beacon is rejected although bytes keep coming`() = runTest {
        val t = ScriptedTransport(answer = { ScriptedTransport.BEACON }, chatter = ScriptedTransport.BEACON)
        val c = connection(t)
        val v = LivenessProbe.probe(c)
        assertIs<LivenessProbe.Verdict.NotBmap>(v, v.toString())
        assertTrue(c.hasAnswered, "the old probe would have called this alive")

        val e = assertFailsWith<RfcommConnectException> { LivenessProbe.check(connection(ScriptedTransport(chatter = ScriptedTransport.BEACON))) }
        assertTrue(e.looksBusy)
    }

    @Test
    fun `a log stream is rejected`() = runTest {
        val t = ScriptedTransport(answer = { ScriptedTransport.LOG }, chatter = ScriptedTransport.LOG)
        assertIs<LivenessProbe.Verdict.NotBmap>(LivenessProbe.probe(connection(t)))
    }

    @Test
    fun `a channel that echoes our bytes is rejected`() = runTest {
        val echo = ScriptedTransport(answer = { bytes -> bytes.joinToString("") { "%02x".format(it) } })
        assertIs<LivenessProbe.Verdict.NotBmap>(LivenessProbe.probe(connection(echo)))
    }

    @Test
    fun `a valid STATUS or ERROR to 0_1 passes, and the probe sends 0_1`() = runTest {
        val written = ArrayList<String>()
        val t = ScriptedTransport(answer = { bytes -> written += bytes.joinToString("") { "%02x".format(it) }; ScriptedTransport.BMAP_VERSION_REPLY })
        LivenessProbe.check(connection(t))
        assertEquals(listOf("00010100"), written) // GET [0.1]: the QC45 init packet

        LivenessProbe.check(connection(ScriptedTransport(answer = { "00010401 04" })))
    }

    @Test
    fun `a silent channel is silent, not alive`() = runTest {
        val c = connection(ScriptedTransport())
        assertSame(LivenessProbe.Verdict.Silent, LivenessProbe.probe(c))
        assertTrue(c.isOpen, "probe() leaves closing to the caller")
        assertEquals(LivenessProbe.TIMEOUT_MS, testScheduler.currentTime)
        assertFalse(c.hasAnswered)
    }
}

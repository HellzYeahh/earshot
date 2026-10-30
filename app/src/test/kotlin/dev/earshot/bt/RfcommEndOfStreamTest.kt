package dev.earshot.bt

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Android 17 (target 37): an RFCOMM socket's read() returns -1 when the link drops, where older
 * targets threw an IOException. Both must close `incoming` with a cause, so a drop never looks
 * like a local close (research A2, docs/research/play-readiness.md).
 */
class RfcommEndOfStreamTest {

    private var savedSink: ((Int, String, Throwable?) -> Unit)? = null

    @Before
    fun quietLog() {
        savedSink = BtLog.sink
        BtLog.sink = { _, _, _ -> }
    }

    @After
    fun restoreLog() {
        savedSink?.let { BtLog.sink = it }
    }

    /** Hands out [chunks] one per read(), then -1 (or [failWith]) for ever. Counts the reads. */
    private class ScriptedStream(
        private val chunks: List<ByteArray>,
        private val failWith: IOException? = null,
        private val availableAtEnd: Int = 0,
        /** Runs just before the first -1: our own close() unblocking the read from another thread. */
        private val onEnd: () -> Unit = {},
    ) : InputStream() {
        var reads = 0
            private set
        private var next = 0

        override fun available(): Int = if (next < chunks.size) chunks[next].size else availableAtEnd

        override fun read(): Int = throw UnsupportedOperationException("the transport reads into a buffer")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads++
            if (next >= chunks.size) {
                failWith?.let { throw it }
                onEnd()
                return -1
            }
            val chunk = chunks[next++]
            chunk.copyInto(b, off)
            return chunk.size
        }
    }

    @Test
    fun `end of stream on a link we did not close is a dropped link`() {
        val stream = ScriptedStream(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4)))
        val seen = ArrayList<ByteArray>()

        val cause = RfcommTransport.readUntilEnd(stream, { seen += it }, isClosed = { false })

        assertIs<IOException>(cause)
        assertEquals(RfcommTransport.END_OF_STREAM, cause.message)
        assertEquals(2, seen.size)
        assertContentEquals(byteArrayOf(1, 2, 3), seen[0])
        assertContentEquals(byteArrayOf(4), seen[1])
        assertEquals(3, stream.reads, "stops at the first -1 instead of reading again")
    }

    @Test
    fun `end of stream after our own close is a clean close`() {
        var closed = false
        val stream = ScriptedStream(listOf(byteArrayOf(9)), onEnd = { closed = true })

        val cause = RfcommTransport.readUntilEnd(stream, {}, isClosed = { closed })

        assertNull(cause)
        assertEquals(2, stream.reads)
    }

    @Test
    fun `an IOException before target 37 is still the drop cause`() {
        val drop = IOException("bt socket closed, read return: -1")
        val stream = ScriptedStream(emptyList(), failWith = drop)

        val cause = RfcommTransport.readUntilEnd(stream, {}, isClosed = { false })

        assertSame(drop, cause)
    }

    @Test
    fun `the greeting drain stops on end of stream and keeps what it read`() = runBlocking {
        // available() keeps claiming bytes after the end, as a dead socket may: without the -1
        // check the drain would read -1 over and over until its 1.5 s limit.
        val stream = ScriptedStream(listOf(byteArrayOf(0x02, 0x02, 0x03, 0x04)), availableAtEnd = 4)

        val greeting = RfcommTransport.collectGreeting(stream)

        assertTrue(greeting.endOfStream)
        assertContentEquals(byteArrayOf(0x02, 0x02, 0x03, 0x04), greeting.bytes)
        assertEquals(2, stream.reads)
    }

    @Test
    fun `a silent channel is not end of stream`() = runBlocking {
        val stream = ScriptedStream(emptyList())

        val greeting = RfcommTransport.collectGreeting(stream)

        assertFalse(greeting.endOfStream)
        assertEquals(0, greeting.bytes.size)
        assertEquals(0, stream.reads, "a silent channel is only polled with available()")
    }
}

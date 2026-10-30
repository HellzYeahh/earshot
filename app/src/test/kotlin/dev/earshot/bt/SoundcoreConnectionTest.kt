package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.soundcore.SoundcoreCommands
import dev.earshot.protocol.soundcore.SoundcorePacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * [SoundcoreConnection] when the link drops under a request: the caller must hear "not connected"
 * (a [HeadphoneCommandException]), never a bare cancellation, so the link factory can report the
 * busy service and the connect loop backs off and retries instead of ending in CONNECTING.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundcoreConnectionTest {

    @Before
    fun quiet() {
        BtLog.sink = { _, _, _ -> }
    }

    /** A transport the test drives: [onWrite] runs for every frame Earshot sends. */
    private class ScriptedTransport : ByteTransport {
        val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val written = mutableListOf<ByteArray>()
        var onWrite: (ByteArray) -> Unit = {}
        override var isConnected = true
        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()
        override suspend fun write(bytes: ByteArray) {
            written += bytes
            onWrite(bytes)
        }
        override fun close() {
            isConnected = false
            inbound.close()
        }
    }

    @Test fun `a socket that closes while the answer is awaited fails with not connected`() = runTest {
        val t = ScriptedTransport()
        // The soundcore app holds the vendor service: the earbuds accept the socket, then close it.
        t.onWrite = { t.inbound.close() }
        val conn = SoundcoreConnection(t, backgroundScope, minWriteGapMs = 0)
        val e = assertFailsWith<HeadphoneCommandException> { conn.request(SoundcoreCommands.REQUEST_STATE) }
        assertEquals(SoundcoreConnection.NOT_CONNECTED, e.message)
        assertFalse(conn.isOpen)
        assertEquals(1, t.written.size, "no retry is written on a closed link")
        assertNull(conn.awaitClosed(), "a remote EOF is not an error")
    }

    @Test fun `an answer still arrives normally`() = runTest {
        val t = ScriptedTransport()
        t.onWrite = { t.inbound.trySend(SoundcorePacket(SoundcoreCommands.REQUEST_STATE, byteArrayOf(1, 2), inbound = true).encode()) }
        val conn = SoundcoreConnection(t, backgroundScope, minWriteGapMs = 0)
        val reply = conn.request(SoundcoreCommands.REQUEST_STATE)
        assertEquals(SoundcoreCommands.REQUEST_STATE, reply.command)
        assertEquals(listOf<Byte>(1, 2), reply.body.toList())
        conn.close()
        val after = assertFailsWith<HeadphoneCommandException> { conn.request(SoundcoreCommands.REQUEST_STATE) }
        assertEquals(SoundcoreConnection.NOT_CONNECTED, after.message)
    }
}

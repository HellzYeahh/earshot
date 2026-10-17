package dev.earshot.virtual

import dev.earshot.protocol.BmapFrameBuffer
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.qcultra2.ModeConfigs
import dev.earshot.protocol.qcultra2.QcUltra2
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VirtualTransportTest {

    private fun transport() = VirtualTransport(
        replyDelayMs = 40L..40L,
        chunkGapMs = 50L,
        tickIntervalMs = 10_000L,
        random = Random(1),
    )

    @Test
    fun `greeting on collect, delayed reply, dump split mid-frame across two chunks`() = runTest {
        val t = transport()
        val chunks = ArrayList<ByteArray>()
        val reader = launch { t.incoming.collect { chunks += it } }
        runCurrent()
        assertEquals(1, chunks.size, "greeting")
        assertEquals(2, BmapPacket.decodeAll(chunks[0]).size)

        t.write(BmapPacket.get(QcUltra2.BATTERY).encode())
        advanceTimeBy(39); runCurrent()
        assertEquals(1, chunks.size, "reply must not be instant")
        advanceTimeBy(2); runCurrent()
        assertEquals(2, chunks.size)
        assertEquals("48ffff00", BmapPacket.decodeAll(chunks[1]).single().payload.joinToString("") { "%02x".format(it) })

        t.write(BmapPacket.start(QcUltra2.MODE_CONFIG).encode())
        advanceTimeBy(41); runCurrent()
        assertEquals(3, chunks.size, "first half of the dump")
        // The first chunk ends mid-frame: on its own it does not decode to all twelve frames.
        assertTrue(BmapPacket.decodeStream(chunks[2]).consumed < chunks[2].size)
        advanceTimeBy(51); runCurrent()
        assertEquals(4, chunks.size, "second half after the gap")

        val buffer = BmapFrameBuffer()
        val frames = chunks.drop(2).flatMap { buffer.feed(it) }
        assertEquals(0, buffer.pendingSize)
        assertEquals(12, frames.size)
        assertEquals(BmapOperator.PROCESSING, frames[0].operator)
        assertTrue(frames.drop(1).all { it.payload.size == ModeConfigs.STATUS_SIZE })

        t.close()
        reader.join()
        assertFalse(t.isConnected)
    }

    @Test
    fun `power off answers RESULT then drops the link like a socket reset`() = runTest {
        val t = transport()
        val chunks = ArrayList<ByteArray>()
        var failure: Throwable? = null
        val reader = launch {
            try {
                t.incoming.collect { chunks += it }
            } catch (e: IOException) {
                failure = e
            }
        }
        runCurrent()
        assertTrue(t.isConnected)
        t.write(BmapPacket.start(QcUltra2.POWER, byteArrayOf(0)).encode())
        advanceTimeBy(200); runCurrent()
        reader.join()
        assertTrue(failure is IOException, "incoming should fail with an IOException, got $failure")
        val last = BmapPacket.decodeAll(chunks.last()).single()
        assertEquals(BmapOperator.RESULT, last.operator)
        assertEquals(QcUltra2.POWER, last.address)
        assertFalse(t.isConnected)
        assertFailsWith<IOException> { t.write(BmapPacket.get(QcUltra2.BATTERY).encode()) }
    }

    @Test
    fun `factory shares the device and powers it back on`() {
        val device = VirtualQcUltra2()
        device.handle(BmapPacket.start(QcUltra2.POWER, byteArrayOf(0)))
        assertFalse(device.isPoweredOn)
        val factory = VirtualTransport.factory(device)
        val t = factory() as VirtualTransport
        assertTrue(device.isPoweredOn)
        assertTrue(t.device === device)
    }
}

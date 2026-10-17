package dev.earshot.protocol

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BmapFrameBufferTest {
    private val battery = BmapPacket(2, 2, BmapOperator.STATUS, "50ffff00".hexToBytes())
    private val mode = BmapPacket(31, 3, BmapOperator.RESULT, byteArrayOf(2))
    private val config = BmapPacket(31, 6, BmapOperator.STATUS, ByteArray(48) { it.toByte() })

    @Test
    fun `one complete frame per read`() {
        val buf = BmapFrameBuffer()
        assertEquals(listOf(battery), buf.feed(battery.encode()))
        assertEquals(listOf(mode), buf.feed(mode.encode()))
        assertEquals(0, buf.pendingSize)
    }

    @Test
    fun `several frames in one read come out in order`() {
        val buf = BmapFrameBuffer()
        val frames = buf.feed(battery.encode() + mode.encode() + config.encode())
        assertEquals(listOf(battery, mode, config), frames)
        assertEquals(0, buf.pendingSize)
    }

    @Test
    fun `a frame split across reads is reassembled`() {
        val buf = BmapFrameBuffer()
        val bytes = config.encode()
        assertTrue(buf.feed(bytes.copyOfRange(0, 2)).isEmpty())      // header cut in half
        assertEquals(2, buf.pendingSize)
        assertTrue(buf.feed(bytes.copyOfRange(2, 30)).isEmpty())     // payload cut in half
        assertEquals(listOf(config), buf.feed(bytes.copyOfRange(30, bytes.size)))
        assertEquals(0, buf.pendingSize)
    }

    @Test
    fun `a read ending mid-frame yields the complete ones and carries the rest`() {
        val buf = BmapFrameBuffer()
        val all = battery.encode() + mode.encode() + config.encode()
        val cut = battery.size() + mode.size() + 10
        assertEquals(listOf(battery, mode), buf.feed(all.copyOfRange(0, cut)))
        assertEquals(10, buf.pendingSize)
        assertEquals(listOf(config), buf.feed(all.copyOfRange(cut, all.size)))
    }

    @Test
    fun `byte at a time still works`() {
        val buf = BmapFrameBuffer()
        val all = battery.encode() + config.encode()
        val out = ArrayList<BmapPacket>()
        for (b in all) out += buf.feed(byteArrayOf(b))
        assertEquals(listOf(battery, config), out)
    }

    @Test
    fun `feed honours offset and length`() {
        val buf = BmapFrameBuffer()
        val chunk = ByteArray(4) { 0x7F } + mode.encode() + ByteArray(3) { 0x7F }
        assertEquals(listOf(mode), buf.feed(chunk, 4, mode.size()))
        assertEquals(0, buf.pendingSize)
    }

    @Test
    fun `a bogus header is dropped and reported`() {
        var dropped: ByteArray? = null
        val buf = BmapFrameBuffer(onDesync = { dropped = it })
        val junk = "aa bb cc dd ee".hexToBytes()                      // operator nibble 0xC: not BMAP
        assertTrue(buf.feed(junk).isEmpty())
        assertEquals(1, buf.desyncCount)
        assertEquals(5L, buf.droppedBytes)
        assertContentEquals(junk, dropped)
        assertEquals(0, buf.pendingSize)
        // and the buffer is usable again afterwards
        assertEquals(listOf(battery), buf.feed(battery.encode()))
    }

    @Test
    fun `pending bytes that outgrow the limit are dropped`() {
        val buf = BmapFrameBuffer(maxPending = 64)
        // valid-looking header claiming 255 payload bytes, then feed garbage that never completes it
        assertTrue(buf.feed(byteArrayOf(31, 6, 3, 0xFF.toByte())).isEmpty())
        assertEquals(0, buf.desyncCount)
        assertTrue(buf.feed(ByteArray(61)).isEmpty())                 // 65 pending > 64
        assertEquals(1, buf.desyncCount)
        assertEquals(65L, buf.droppedBytes)
        assertEquals(0, buf.pendingSize)
    }

    @Test
    fun `reset forgets a partial frame without counting a desync`() {
        val buf = BmapFrameBuffer()
        buf.feed(config.encode().copyOfRange(0, 10))
        assertEquals(10, buf.pendingSize)
        buf.reset()
        assertEquals(0, buf.pendingSize)
        assertEquals(0, buf.desyncCount)
        assertEquals(listOf(mode), buf.feed(mode.encode()))
    }

    private fun BmapPacket.size() = BmapPacket.HEADER_SIZE + payload.size
}

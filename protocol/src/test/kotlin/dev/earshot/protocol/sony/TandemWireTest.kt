package dev.earshot.protocol.sony

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden Tandem frames. The received ones are byte-for-byte from mos9527/SonyHeadphonesClient's
 * replay captures (MIT; `tests/WF-1000XM5-6.1.0`, `tests/WF-LC900-2.0.3-equalizer`), so encoding
 * our own parse of them must give the same bytes back, escaping and checksum included.
 */
class TandemWireTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun decodeOne(frame: ByteArray): TandemFrame = TandemFrameBuffer().feed(frame).single()

    @Test fun `acks are the fixed nine-byte frames`() {
        assertContentEquals(hex("3e 01 00 00 00 00 00 01 3c"), TandemFrame.ack(0).encode())
        assertContentEquals(hex("3e 01 01 00 00 00 00 02 3c"), TandemFrame.ack(1).encode())
        // The headset's ACK to mos9527's first frame (seq 0) carries seq 1.
        val ack = decodeOne(hex("3e 01 01 00 00 00 00 02 3c"))
        assertTrue(ack.isAck)
        assertEquals(1, ack.seq)
    }

    @Test fun `the protocol-info request matches what every client sends first`() {
        assertContentEquals(hex("3e 0c 00 00 00 00 02 00 00 0e 3c"), TandemFrame(TandemDataType.DATA_MDR, 0, SonyProtocolInfo.GET).encode())
    }

    @Test fun `captured frames decode and re-encode to the same bytes`() {
        val captured = listOf(
            "3e 0c 01 00 00 00 08 01 00 03 00 30 18 00 00 61 3c", // protocol info, WF-1000XM5
            "3e 0c 00 00 00 00 0d 05 01 0a 57 46 2d 31 30 30 30 58 4d 35 8e 3c", // model name
            "3e 0c 00 00 00 00 08 23 09 00 00 64 00 64 64 6c 3c", // L/R battery
            "3e 0c 01 00 00 00 07 67 17 01 00 01 00 14 a8 3c", // NCASM 0x17
            "3e 0c 01 00 00 00 0a 57 00 a0 06 14 0b 0a 0d 09 0c 5f 3c", // EQ
            "3e 0c 00 00 00 00 15 51 00 0a 0d 08 00 00 30 00 31 00 32 00 33 00 a0 00 a1 00 a2 00 3a 3c", // EQ capability, WF-LC900
        )
        for (h in captured) {
            val bytes = hex(h)
            val f = decodeOne(bytes)
            assertContentEquals(bytes, TandemFrame(f.dataType, f.seq, f.payload).encode(), h)
        }
    }

    @Test fun `a 0x3D inside the payload is escaped as 3D 2D (table-2 support list)`() {
        // mos9527's WF-1000XM5 capture: the table-2 support list lists function 0x42 with priority 0x3D.
        val wire = hex("3e 0e 01 00 00 00 19 07 00 0b 42 3d 2d 53 ff 54 ff f1 ff 60 ff 61 ff f2 ff 63 ff f8 30 32 04 31 ff ee 3c")
        val f = decodeOne(wire)
        assertEquals(TandemDataType.DATA_MDR_NO2, f.dataType)
        assertEquals(25, f.payload.size)
        assertEquals(0x3D, f.payload[4].toInt() and 0xFF)
        assertContentEquals(wire, f.encode())
    }

    @Test fun `escaping covers the length field and the checksum too`() {
        // A one-byte payload 0x30 sums to 0x3D: the checksum itself is escaped.
        assertContentEquals(hex("3e 0c 00 00 00 00 01 30 3d 2d 3c"), TandemFrame(0x0C, 0, byteArrayOf(0x30)).encode())
        // A 62-byte payload: the length's low byte is 0x3E.
        val long = TandemFrame(0x0C, 1, ByteArray(62) { 0x11 })
        val enc = long.encode()
        assertContentEquals(hex("3e 0c 01 00 00 00 3d 2e"), enc.copyOfRange(0, 8))
        assertEquals(62, decodeOne(enc).payload.size)
        // Every special byte inside a payload.
        val nasty = byteArrayOf(0x3C, 0x3D, 0x3E, 0x2C, 0x2D, 0x2E)
        val round = decodeOne(TandemFrame(0x0E, 0, nasty).encode())
        assertContentEquals(nasty, round.payload)
        assertNull(TandemFrame.unescape(hex("3d")))
        assertNull(TandemFrame.unescape(hex("3d 01")))
    }

    @Test fun `the stream decoder survives chunking, junk, a bad checksum and a cut-off frame`() {
        val good1 = hex("3e 0c 01 00 00 00 07 67 17 01 00 01 00 14 a8 3c")
        val bad = hex("3e 0c 01 00 00 00 07 67 17 01 00 01 00 14 a9 3c") // checksum off by one
        val cut = hex("3e 0c 00 00 00") // never ends: the next 3E starts over
        val good2 = hex("3e 01 00 00 00 00 00 01 3c")
        val stream = hex("ff 00 11") + good1 + bad + cut + good2
        var junk = 0
        val buffer = TandemFrameBuffer(onJunk = { junk += it })
        val frames = stream.toList().chunked(3).flatMap { buffer.feed(it.toByteArray()) }
        assertEquals(listOf(TandemDataType.DATA_MDR, TandemDataType.ACK), frames.map { it.dataType })
        assertTrue(junk > 0)
    }

    @Test fun `a length field that disagrees is tolerated and reported`() {
        var mismatch: Pair<Int, Int>? = null
        val body = hex("0c 00 00 00 00 05 12 02") // claims 5, carries 2
        val frame = byteArrayOf(0x3E) + body + TandemFrame.checksum(body, body.size).toByte() + byteArrayOf(0x3C)
        val f = TandemFrameBuffer(onLengthMismatch = { d, a -> mismatch = d to a }).feed(frame).single()
        assertEquals(2, f.payload.size)
        assertEquals(5 to 2, mismatch)
    }

    @Test fun `which data types are acknowledged`() {
        assertTrue(TandemDataType.needsAck(TandemDataType.DATA_MDR))
        assertTrue(TandemDataType.needsAck(TandemDataType.DATA_MDR_NO2))
        assertTrue(TandemDataType.needsAck(TandemDataType.LARGE_DATA_MDR))
        assertTrue(TandemDataType.needsAck(TandemDataType.DATA))
        assertFalse(TandemDataType.needsAck(TandemDataType.ACK))
        assertFalse(TandemDataType.needsAck(TandemDataType.SHOT_MDR))
        assertFalse(TandemDataType.needsAck(TandemDataType.SHOT_MDR_NO2))
        assertEquals(SonyTable.T1, SonyTable.forDataType(0x0C))
        assertEquals(SonyTable.T2, SonyTable.forDataType(0x0E))
        assertNull(SonyTable.forDataType(0x0D))
    }
}

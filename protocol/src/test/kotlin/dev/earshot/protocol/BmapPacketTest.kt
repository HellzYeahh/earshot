package dev.earshot.protocol

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BmapPacketTest {

    @Test
    fun `GET encodes as the 4-byte header from the notes`() {
        // NOTES.md quick reference: read battery = [2, 2, 0x01, 0x00]
        assertEquals("02020100", BmapPacket.get(FunctionAddress(2, 2)).encode().toHex())
        // switch to Aware: [31, 3, 0x05, 2, 1, 0]
        assertEquals("1f0305020100", BmapPacket.start(FunctionAddress(31, 3), byteArrayOf(1, 0)).encode().toHex())
        // SET_GET [31.10] [5,0,0,0,1]
        assertEquals("1f0a02050500000001", BmapPacket.setGet(FunctionAddress(31, 10), byteArrayOf(5, 0, 0, 0, 1)).encode().toHex())
    }

    @Test
    fun `encode then decode round trips every operator and the payload`() {
        for (op in BmapOperator.entries) {
            val payload = ByteArray(37) { (it * 7).toByte() }
            val original = BmapPacket(31, 6, op, payload)
            val decoded = BmapPacket.decodeOne(original.encode())
            assertNotNull(decoded)
            assertEquals(original, decoded)
            assertContentEquals(payload, decoded.payload)
        }
    }

    @Test
    fun `flags nibble carries deviceId and port around the operator`() {
        val packet = BmapPacket(1, 7, BmapOperator.SET_GET, byteArrayOf(0x05, 0x00), deviceId = 2, port = 1)
        val bytes = packet.encode()
        // (2 shl 6) or (1 shl 4) or 2 = 0x80 | 0x10 | 0x02
        assertEquals(0x92, bytes[2].toInt() and 0xFF)
        val decoded = BmapPacket.decodeOne(bytes)!!
        assertEquals(2, decoded.deviceId)
        assertEquals(1, decoded.port)
        assertEquals(BmapOperator.SET_GET, decoded.operator)
        // decoding only looks at the low nibble for the operator
        assertEquals(BmapOperator.STATUS, BmapOperator.fromCode(0xF3))
        assertNull(BmapOperator.fromCode(0x08))
    }

    @Test
    fun `decodeOne returns null on a short read or a bogus operator`() {
        assertNull(BmapPacket.decodeOne("020201".hexToBytes()))               // header incomplete
        assertNull(BmapPacket.decodeOne("1f0a0305050000".hexToBytes()))       // says 5 bytes, has 3
        assertNull(BmapPacket.decodeOne("02020900".hexToBytes()))             // operator 9 does not exist
        assertNotNull(BmapPacket.decodeOne("1f0a03050500000001".hexToBytes()))
    }

    @Test
    fun `decodeStream splits several frames glued in one read and keeps the tail`() {
        val a = BmapPacket(2, 2, BmapOperator.STATUS, "50ffff00".hexToBytes()).encode()
        val b = BmapPacket(31, 3, BmapOperator.RESULT, byteArrayOf(1)).encode()
        val tail = byteArrayOf(31, 6, 3)
        val result = BmapPacket.decodeStream(a + b + tail)
        assertEquals(2, result.packets.size)
        assertEquals(FunctionAddress(2, 2), result.packets[0].address)
        assertEquals(FunctionAddress(31, 3), result.packets[1].address)
        assertEquals(a.size + b.size, result.consumed)
    }

    @Test
    fun `error packets expose the code and the typed error`() {
        val packet = BmapPacket.decodeOne("01050401 05".hexToBytes())!!
        assertTrue(packet.isError)
        assertEquals(5, packet.errorCode)
        assertEquals(BmapError.OP_NOT_SUPPORTED, packet.error)
        assertTrue(BmapException.from(packet).operatorNotSupported)
        @Suppress("DEPRECATION")
        assertTrue(BmapException.from(packet).needsAuthentication) // deprecated alias still answers
        assertEquals("The headphones' firmware doesn't support changing this", BmapError.OP_NOT_SUPPORTED.description)
        assertTrue("[1.5]" in BmapException.from(packet).message.orEmpty())
        assertEquals("[1.5]", packet.address.toString())
    }

    @Test
    fun `hex helpers ignore whitespace and round trip`() {
        val bytes = "f6 0a 00 00 f60afe01".hexToBytes()
        assertEquals("f60a0000f60afe01", bytes.toHex())
    }
}

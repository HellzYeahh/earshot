package dev.earshot.protocol.soundcore

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden frames: the request-state example and two frames captured from the official app on a
 * Sleep A30 (quoted in OpenSCQ30's issues), an ack, and the ANC frame computed from the layout.
 */
class SoundcoreWireTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `request state encodes to the documented frame`() {
        assertContentEquals(hex("08 EE 00 00 00 01 01 0A 00 02"), SoundcorePacket(SoundcoreCommands.REQUEST_STATE).encode())
    }

    @Test fun `frames captured from the official app round-trip`() {
        // Delete alarm 1, and set an alarm: checksums and lengths must come out the same.
        assertContentEquals(hex("08 EE 00 00 00 14 82 0B 00 01 98"), SoundcorePacket(0x1482, byteArrayOf(1)).encode())
        val setAlarm = hex("08 EE 00 00 00 14 81 12 00 01 01 30 02 82 00 32 0A 8F")
        assertContentEquals(setAlarm, SoundcorePacket(0x1481, setAlarm.copyOfRange(9, setAlarm.size - 1)).encode())
    }

    @Test fun `Sleep A30 noise cancelling on`() {
        assertContentEquals(hex("08 EE 00 00 00 06 87 0B 00 01 8F"), SoundcorePacket(SoundcoreCommands.SET_ANC_D1301, byteArrayOf(1)).encode())
    }

    @Test fun `inbound frames decode across arbitrary chunking, junk and a bad checksum`() {
        val ack = hex("09 FF 00 00 01 06 81 0A 00 9A")
        val state = SoundcorePacket(SoundcoreCommands.REQUEST_STATE, ByteArray(150), inbound = true).encode()
        val corrupt = ack.copyOf().also { it[it.size - 1] = 0x00 }
        val stream = byteArrayOf(0x33, 0x44) + ack + corrupt + state
        val buffer = SoundcoreFrameBuffer()
        val packets = stream.toList().chunked(7).flatMap { buffer.feed(it.toByteArray()) }
        assertEquals(listOf(0x0681, 0x0101), packets.map { it.command })
        assertTrue(packets.all { it.inbound })
        assertEquals(0, packets[0].body.size)
        assertEquals(150, packets[1].body.size)
    }

    @Test fun `the allow-list refuses unpair, pairing, power off, find and unknown writes`() {
        assertTrue(SoundcoreWriteGuard.isAllowed(SoundcorePacket(SoundcoreCommands.REQUEST_STATE)))
        assertTrue(SoundcoreWriteGuard.isAllowed(SoundcorePacket(SoundcoreCommands.SET_ANC_D1301, byteArrayOf(1))))
        for (refused in listOf(0x0B83, 0x0B85, 0x0B81, 0x0B84, 0x0185, 0x0189, 0x1089, 0x0482, 0x1482, 0x0681, 0x01A6)) {
            assertFalse(SoundcoreWriteGuard.isAllowed(SoundcorePacket(refused)), SoundcorePacket.commandName(refused))
        }
    }

    @Test fun `Sleep A30 state from its 150-byte body`() {
        val body = ByteArray(150)
        body[0] = 1; body[1] = 1
        body[2] = 7; body[3] = 8 // 80 %, 90 %
        "01.91".toByteArray().copyInto(body, 4)
        "01.91".toByteArray().copyInto(body, 9)
        "1301A2B3C4D5E6F7".toByteArray().copyInto(body, 14)
        "01.68".toByteArray().copyInto(body, 36)
        body[65] = 0
        body[140] = 1
        body[146] = 0
        val s = assertNotNull(D1301State.parse(body))
        assertEquals(80, s.leftPercent)
        assertEquals(90, s.rightPercent)
        assertEquals("01.91", s.firmware)
        assertEquals("1301A2B3C4D5E6F7", s.serial)
        assertEquals("01.68", s.caseFirmware)
        assertTrue(s.ancOn)
        assertFalse(s.tapControlsDisabled)
        assertFalse(s.listeningLocal)
    }

    @Test fun `an absent bud and absent firmware read as null`() {
        val body = ByteArray(150)
        body[2] = 255.toByte(); body[3] = 0
        for (i in 4..8) body[i] = 0xFF.toByte()
        val s = assertNotNull(D1301State.parse(body))
        assertNull(s.leftPercent)
        assertEquals(10, s.rightPercent)
        assertNull(s.firmwareLeft)
        assertNull(D1301State.parse(ByteArray(40)))
    }

    @Test fun `models by name and by vendor service UUID`() {
        assertEquals(SoundcoreModel.SLEEP_A30, SoundcoreModel.fromName("soundcore Sleep A30"))
        assertNull(SoundcoreModel.fromName("soundcore Boom 2"))
        assertEquals(SoundcoreModel.SLEEP_A30, SoundcoreModel.fromServiceUuid("0cf12d31-fac3-4553-bd80-d6832e7d1301"))
        assertNull(SoundcoreModel.fromServiceUuid("0cf12d31-fac3-4553-bd80-d6832e7b3138")) // Boom 2: not driven yet
        assertNull(SoundcoreModel.fromServiceUuid("00001101-0000-1000-8000-00805f9b34fb"))
    }

    /**
     * The state the user's Sleep A30 sent on 2026-10-21 (firmware 01.91), serial and bytes 30..35
     * masked. Noise cancelling on, both buds full, tap controls on: what the earbuds were doing.
     */
    @Test fun `a real Sleep A30 state reads as on, full battery, firmware 01_91`() {
        val body = hex(
            "0101090930312e393130312e39313133303158585858585858585858585800000000000030312e36380000000006dd88" +
            "ddddff0001d4027c0064001e0001ffffff00000100003232000006001e43410000000000020100000000000000000002" +
            "018000000000000004030280000000000000040403800000000000000405048000000000000004010000010701010101" +
            "010000010100",
        )
        assertEquals(150, body.size)
        val s = assertNotNull(D1301State.parse(body))
        assertTrue(s.ancOn)
        assertEquals(100, s.leftPercent)
        assertEquals(100, s.rightPercent)
        assertEquals("01.91", s.firmware)
        assertEquals("01.68", s.caseFirmware)
        assertFalse(s.tapControlsDisabled)
        assertFalse(s.listeningLocal)
        assertTrue(s.budsLinked)
    }
}

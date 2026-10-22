package dev.earshot.protocol.gaia

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden frames from momentum4-control's Momentum 4 captures (firmware 3.37.3; MIT) and the
 * sennheiser-desktop-client battery example quoted in the Sennheiser notes (facts only).
 */
class GaiaWireTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun decodeOne(s: String): GaiaFrame = GaiaFrameBuffer().feed(hex(s)).single()

    @Test fun `requests encode to the captured frames`() {
        assertContentEquals(hex("FF 03 00 00 04 95 06 03"), GaiaFrame(0x0495, 0x0603).encode())
        assertContentEquals(hex("FF 03 00 01 04 95 1A 04 01"), GaiaFrame(0x0495, 0x1A04, byteArrayOf(1)).encode())
        assertContentEquals(hex("FF 03 00 00 00 1D 00 00"), GaiaFrame(GaiaVendor.QUALCOMM, 0x0000).encode())
        assertContentEquals(hex("FF 03 00 03 04 95 06 00 00 07 08"), GaiaFrame(0x0495, 0x0600, hex("00 07 08")).encode())
        assertContentEquals(hex("FF 03 00 02 04 95 10 01 04 0A"), GaiaFrame(0x0495, 0x1001, hex("04 0A")).encode())
    }

    @Test fun `a response decodes to the request it answers`() {
        val battery = decodeOne("FF 03 00 01 04 95 07 03 2A")
        assertEquals(0x0495, battery.vendor)
        assertEquals(0x0703, battery.command)
        assertEquals(GaiaCommand.TYPE_RESPONSE, battery.type)
        assertEquals(0x0603, GaiaCommand.request(battery.command))
        assertContentEquals(byteArrayOf(42), battery.payload)

        val ack = decodeOne("FF 03 00 00 04 95 1B 04")
        assertTrue(GaiaCommand.isResponse(ack.command))
        assertEquals(0x1A04, GaiaCommand.request(ack.command))
        assertEquals(0, ack.payload.size)
    }

    @Test fun `command word arithmetic`() {
        assertEquals(13, GaiaCommand.feature(0x1A04))
        assertEquals(4, GaiaCommand.pdu(0x1A04))
        assertEquals(0x1A04, GaiaCommand.of(feature = 13, pdu = 4))
        assertEquals(0x1B04, GaiaCommand.response(0x1A04))
        assertEquals(0x1A85, GaiaCommand.notification(0x1A05))
        assertEquals(0x1B85, GaiaCommand.error(0x1A05))
        assertTrue(GaiaCommand.isNotification(0x1A83))
        assertTrue(GaiaCommand.isError(0x1781)) // 0x1601 refused with reason 5 on a Momentum 4
        assertEquals(0x1601, GaiaCommand.request(0x1781))
        assertEquals(1, GaiaCommand.feature(0x0200)) // firmware update
        assertEquals(6, GaiaCommand.feature(0x0C00)) // Qualcomm upgrade
    }

    @Test fun `API version and both feature lists from a Momentum 4`() {
        val api = decodeOne("FF 03 00 02 00 1D 01 00 03 01")
        assertEquals(GaiaApiVersion(3, 1), GaiaApiVersion.parse(api.payload))

        val qualcomm = assertNotNull(GaiaFeaturePage.parse(decodeOne("FF 03 00 0D 00 1D 01 01 00 0D 01 0C 01 06 02 04 02 07 01 00 04").payload))
        assertFalse(qualcomm.moreData)
        assertEquals(mapOf(13 to 1, 12 to 1, 6 to 2, 4 to 2, 7 to 1, 0 to 4), qualcomm.features)

        val sennheiser = assertNotNull(
            GaiaFeaturePage.parse(decodeOne("FF 03 00 19 04 95 01 01 00 09 01 08 06 0C 01 03 02 10 02 04 02 02 01 0A 02 14 01 0B 01 0D 01 00 04").payload),
        )
        assertEquals(listOf(9, 8, 12, 3, 16, 4, 2, 10, 20, 11, 13, 0), sennheiser.features.keys.toList())
        assertEquals(6, sennheiser.features[8]) // generation-2 EQ
        assertNull(GaiaFeaturePage.parse(ByteArray(0)))
    }

    @Test fun `frames split, merged and surrounded by junk still decode`() {
        val stream = hex("00 13 37") +
            hex("FF 03 00 01 04 95 07 03 2A") +
            hex("FF 03 00 06 04 95 1A 81 01 01 02 00 03 00") +
            hex("FF 03 00 0D 04 95 13 06 4D 34 41 45 42 54 20 42 6C 61 63 6B 00")
        val buffer = GaiaFrameBuffer()
        val frames = stream.toList().chunked(3).flatMap { buffer.feed(it.toByteArray()) }
        assertEquals(listOf(0x0703, 0x1A81, 0x1306), frames.map { it.command })
        assertEquals(13, frames[2].payload.size)
    }

    @Test fun `a 0xFF inside a payload does not split the frame`() {
        // Battery of a bud in its case reads 0xFF.
        val frames = GaiaFrameBuffer().feed(hex("FF 03 00 03 04 95 07 03 FF 50 FF FF 03 00 00 04 95 1B 04"))
        assertEquals(listOf(0x0703, 0x1B04), frames.map { it.command })
        assertContentEquals(hex("FF 50 FF"), frames[0].payload)
    }

    @Test fun `a long payload switches to version 4 with a 16-bit length, both ways`() {
        val payload = ByteArray(300) { it.toByte() }
        val bytes = GaiaFrame(0x0495, 0x1206, payload).encode()
        assertContentEquals(hex("FF 04 02 01 2C 04 95 12 06"), bytes.copyOfRange(0, 9))
        assertEquals(9 + 300, bytes.size)
        val back = GaiaFrameBuffer().feed(bytes).single()
        assertEquals(4, back.version)
        assertContentEquals(payload, back.payload)
        // The 254-byte boundary stays short.
        assertEquals(3, GaiaFrame(0x0495, 0x1206, ByteArray(254)).encode()[1].toInt())
    }

    @Test fun `the optional checksum is written, checked and a corrupt frame dropped`() {
        val withSum = GaiaFrame(0x0495, 0x0603, checksum = true).encode()
        assertContentEquals(hex("FF 03 01 00 04 95 06 03"), withSum.copyOfRange(0, 8))
        assertEquals(GaiaFrame.xor(withSum, withSum.size - 1), withSum.last().toInt() and 0xFF)
        assertEquals(0x0603, GaiaFrameBuffer().feed(withSum).single().command)

        val corrupt = withSum.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        var junk = 0
        val good = hex("FF 03 00 00 04 95 1B 04")
        assertEquals(listOf(0x1B04), GaiaFrameBuffer(onJunk = { junk += it }).feed(corrupt + good).map { it.command })
        assertTrue(junk > 0)
    }

    @Test fun `version 1 frames decode, unknown versions and flags are junk`() {
        assertEquals(0x8182, GaiaFrameBuffer().feed(hex("FF 01 00 00 04 94 81 82")).single().command)
        assertTrue(GaiaFrameBuffer().feed(hex("FF 02 00 00 04 95 06 03")).isEmpty())
        assertTrue(GaiaFrameBuffer().feed(hex("FF 03 80 00 04 95 06 03")).isEmpty())
    }

    @Test fun `toString never shows the payload`() {
        val name = GaiaFrame(0x0495, 0x1306, "M4AEBT Black".toByteArray()).toString()
        assertEquals("0495/1306 (12 bytes)", name)
    }

    @Test fun `error reasons`() {
        assertEquals("invalid parameter", GaiaError.name(5))
        assertTrue(GaiaError.meansUnsupported(GaiaError.COMMAND_NOT_SUPPORTED))
        assertFalse(GaiaError.meansUnsupported(GaiaError.INCORRECT_STATE))
    }
}

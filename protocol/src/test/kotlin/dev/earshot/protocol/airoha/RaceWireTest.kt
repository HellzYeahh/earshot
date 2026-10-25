package dev.earshot.protocol.airoha

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RACE framing (`05 type len16 id16 payload`, little-endian, the length counting id + payload),
 * the never-send ranges and the per-vendor allow-lists, and the one-command-in-flight queue.
 * Frames are built from the layout in the JBL notes §2–3; no captures exist yet.
 */
class RaceWireTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ---- framing ---------------------------------------------------------------------------

    @Test fun `commands encode little-endian with the length over id and payload`() {
        // ANC on, filter 1 (JBL: ANC).
        assertContentEquals(hex("05 5A 05 00 06 0E 00 0A 01"), RacePacket.command(RaceIds.ANC_PASSTHROUGH, hex("00 0A 01")).encode())
        // MMI get enum, module 0 (EQ preset group): the A-RACE start read.
        assertContentEquals(hex("05 5A 04 00 01 09 00 00"), RacePacket.command(RaceIds.MMI_GET_ENUM, hex("00 00")).encode())
        // A command without payload still carries its id.
        assertContentEquals(hex("05 5A 02 00 D4 0C"), RacePacket.command(RaceIds.ROLE_QUERY_B).encode())
    }

    @Test fun `replies decode across chunk boundaries`() {
        val buffer = RaceFrameBuffer()
        // TWS battery indication: status 0, partner, 80 %.
        val frame = hex("05 5D 05 00 D6 0C 00 01 50")
        assertTrue(buffer.feed(frame.copyOfRange(0, 3)).isEmpty())
        val packet = buffer.feed(frame.copyOfRange(3, frame.size)).single()
        assertTrue(packet.isIndication)
        assertEquals(RaceIds.TWS_BATTERY, packet.id)
        assertContentEquals(hex("00 01 50"), packet.payload)
    }

    @Test fun `junk before a frame and implausible headers are skipped`() {
        var junk = 0
        val buffer = RaceFrameBuffer(onJunk = { junk += it })
        // Stray bytes, a 05 with an unknown type, then a real response.
        val packets = buffer.feed(hex("FF 00 05 77 05 5B 03 00 01 09 00"))
        assertEquals(1, packets.size)
        assertTrue(packets[0].isResponse)
        assertEquals(RaceIds.MMI_GET_ENUM, packets[0].id)
        assertEquals(4, junk)
    }

    @Test fun `two frames in one chunk both come out`() {
        val packets = RaceFrameBuffer().feed(hex("05 5B 03 00 06 0E 00 05 5B 03 00 01 09 00"))
        assertEquals(listOf(RaceIds.ANC_PASSTHROUGH, RaceIds.MMI_GET_ENUM), packets.map { it.id })
    }

    @Test fun `a length below the id is not a frame`() {
        assertTrue(RaceFrameBuffer().feed(hex("05 5B 01 00 06")).isEmpty())
    }

    // ---- guard -----------------------------------------------------------------------------

    private val jbl = RaceWriteGuard(RaceVendor.JBL)
    private val skullcandy = RaceWriteGuard(RaceVendor.SKULLCANDY)

    @Test fun `firmware, flash, NV and debug ids are refused for every vendor`() {
        val never = listOf(0x1C00, 0x1C05, 0x1C1B, 0x0400, 0x04F0, 0x0702, 0x070F, 0x0A01, 0x0A09, 0x0A0D, 0x1E07, 0x020D, 0x0E01, 0x0E0B, 0x0CD7, 0x2C86, 0x3101, 0x1101, 0x0D01, 0x0093)
        for (id in never) for (guard in listOf(jbl, skullcandy)) {
            assertNotNull(guard.refusal(RacePacket.command(id)), "0x%04X must be refused".format(id))
            assertTrue(RaceWriteGuard.isNever(id))
        }
        assertTrue(RaceWriteGuard.allowedIds(RaceVendor.JBL).none(RaceWriteGuard::isNever), "no vendor list overlaps the never list")
    }

    @Test fun `JBL's destructive Harman ids are refused`() {
        for (id in listOf(0x0057, 0x0067, 0x0007, 0x0011, 0x0083, 0x0089, 0x0094, 0x0030)) {
            assertNotNull(jbl.refusal(RacePacket.command(id)), "0x%04X".format(id))
        }
    }

    @Test fun `only commands are sent`() {
        assertNotNull(jbl.refusal(RacePacket(RacePacket.TYPE_RESPONSE, RaceIds.ROLE_QUERY_B)))
        assertNotNull(jbl.refusal(RacePacket(RacePacket.TYPE_INDICATION, RaceIds.ROLE_QUERY_B)))
        assertNull(jbl.refusal(RacePacket.command(RaceIds.ROLE_QUERY_B)))
    }

    @Test fun `unknown ids are refused`() {
        assertNotNull(jbl.refusal(RacePacket.command(0x0A00)), "NV-key reads are not needed and not listed")
        assertNotNull(jbl.refusal(RacePacket.command(0x1234)))
    }

    @Test fun `ANC sub-commands and filters are checked`() {
        assertNull(jbl.refusal(RacePacket.command(RaceIds.ANC_PASSTHROUGH, hex("00 0A 01"))))
        assertNull(jbl.refusal(RacePacket.command(RaceIds.ANC_PASSTHROUGH, hex("00 0A 05 00"))))
        assertNull(jbl.refusal(RacePacket.command(RaceIds.ANC_PASSTHROUGH, hex("00 0B"))))
        assertNull(jbl.refusal(RacePacket.command(RaceIds.ANC_PASSTHROUGH, hex("00 1E 03 00"))))
        assertNotNull(jbl.refusal(RacePacket.command(RaceIds.ANC_PASSTHROUGH, hex("00 0A 08"))), "no filter 8")
        assertNotNull(jbl.refusal(RacePacket.command(RaceIds.ANC_PASSTHROUGH, hex("00 0B 00"))), "off takes no argument")
        assertNotNull(jbl.refusal(RacePacket.command(RaceIds.ANC_PASSTHROUGH, hex("00 0E"))), "the dump sub-commands are not listed")
        assertNotNull(jbl.refusal(RacePacket.command(RaceIds.ANC_PASSTHROUGH)))
    }

    @Test fun `MMI enums allow only the documented modules`() {
        for (module in listOf(0, 1, 5, 6, 7)) assertNull(jbl.refusal(RacePacket.command(RaceIds.MMI_GET_ENUM, byteArrayOf(module.toByte(), 0))))
        assertNotNull(jbl.refusal(RacePacket.command(RaceIds.MMI_GET_ENUM, hex("02 00"))))
        assertNull(jbl.refusal(RacePacket.command(RaceIds.MMI_SET_ENUM, hex("00 00 03"))), "EQ preset 3")
        assertNull(jbl.refusal(RacePacket.command(RaceIds.MMI_SET_ENUM, hex("01 00 01"))), "voice prompts on")
        assertNotNull(jbl.refusal(RacePacket.command(RaceIds.MMI_SET_ENUM, hex("05 00 01"))), "the ANC status module is read-only")
        assertNotNull(jbl.refusal(RacePacket.command(RaceIds.MMI_SET_ENUM, hex("00 00"))), "a write needs its value")
    }

    @Test fun `battery asks agent or partner only`() {
        assertNull(jbl.refusal(RacePacket.command(RaceIds.TWS_BATTERY, hex("00"))))
        assertNull(jbl.refusal(RacePacket.command(RaceIds.TWS_BATTERY, hex("01"))))
        assertNotNull(jbl.refusal(RacePacket.command(RaceIds.TWS_BATTERY, hex("02"))))
    }

    @Test fun `the find-me tone needs the user's own tap`() {
        val ring = RacePacket.command(RaceIds.FIND_ME, hex("01 01 00"))
        assertNotNull(jbl.refusal(ring))
        assertNull(jbl.refusal(ring, userInitiated = true))
        assertNull(jbl.refusal(RacePacket.command(RaceIds.FIND_ME_QUERY)))
    }

    @Test fun `Skullcandy gets the Airoha standard ids but not JBL's`() {
        assertNull(skullcandy.refusal(RacePacket.command(RaceIds.ANC_PASSTHROUGH, hex("00 0B"))))
        assertNotNull(skullcandy.refusal(RacePacket.command(0x0014)), "SafeSound is a Harman id")
        assertNull(jbl.refusal(RacePacket.command(0x0014, hex("01"))))
        assertNotNull(jbl.refusal(RacePacket.command(0x0014, ByteArray(17))), "Harman payloads are capped")
    }

    // ---- queue -----------------------------------------------------------------------------

    @Test fun `a command waits for the reply with its id`() = runTest {
        val sent = mutableListOf<ByteArray>()
        val queue = RaceRequestQueue(send = { sent += it }, guard = jbl)
        val call = async { queue.request(RacePacket.command(RaceIds.TWS_BATTERY, hex("00")), timeoutMs = 1_000) }
        testScheduler.runCurrent()
        assertEquals(1, sent.size)
        assertFalse(queue.offer(RacePacket(RacePacket.TYPE_RESPONSE, RaceIds.MMI_GET_ENUM, hex("00"))), "another id is not the answer")
        assertTrue(queue.offer(RacePacket(RacePacket.TYPE_INDICATION, RaceIds.TWS_BATTERY, hex("00 00 5A"))))
        val reply = call.await()
        assertTrue(reply.isSuccess)
        assertContentEquals(hex("00 5A"), reply.body)
    }

    @Test fun `a refused command never reaches the link`() = runTest {
        val sent = mutableListOf<ByteArray>()
        val queue = RaceRequestQueue(send = { sent += it }, guard = jbl)
        assertFailsWith<RaceRefusedException> { queue.request(RacePacket.command(0x1C00), timeoutMs = 1_000) }
        assertTrue(sent.isEmpty())
    }

    @Test fun `silence times out and a closed queue refuses`() = runTest {
        val queue = RaceRequestQueue(send = {}, guard = jbl)
        assertFailsWith<RaceTimeoutException> { queue.request(RacePacket.command(RaceIds.ROLE_QUERY_B), timeoutMs = 500) }
        queue.close()
        assertFailsWith<RaceLinkClosedException> { queue.request(RacePacket.command(RaceIds.ROLE_QUERY_B), timeoutMs = 500) }
    }
}

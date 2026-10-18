package dev.earshot.bt

import dev.earshot.protocol.bragi.BosRpcPacket
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.bragi.PppFraming
import dev.earshot.protocol.toHex
import dev.earshot.service.DeviceFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BragiLinkTest {

    private fun frame(tag: Int) = PppFraming.frame(BosRpcPacket.request(BragiServices.DEV_INFO, tag).encode())

    // ── Frame reader ─────────────────────────────────────────────────────────────────────

    @Test fun `reader returns frames split across chunks`() {
        val f = frame(1)
        val r = BragiFrameReader()
        assertTrue(r.feed(f.copyOfRange(0, 5)).isEmpty())
        assertTrue(r.feed(f.copyOfRange(5, 9)).isEmpty())
        val out = r.feed(f.copyOfRange(9, f.size))
        assertEquals(1, out.size)
        assertEquals(PppFraming.PROTOCOL_CLEARTEXT, out.single().protocolId)
        assertEquals(0, r.pendingSize)
    }

    @Test fun `reader returns two frames glued together`() {
        val out = BragiFrameReader().feed(frame(1) + frame(2))
        assertEquals(2, out.size)
    }

    @Test fun `reader skips a vendor header and noise in front of and between frames`() {
        val vendor = byteArrayOf(0x05, 0x5D, 0x0D, 0x00, 0x01, 0x0D)
        val skipped = ArrayList<Int>()
        val out = BragiFrameReader(onSkipped = { skipped += it }).feed(vendor + frame(1) + byteArrayOf(0x7E, 0x00, 0x01) + vendor + frame(2))
        assertEquals(2, out.size)
        assertTrue(skipped.sum() > 0)
    }

    @Test fun `reader resyncs after a corrupt frame`() {
        val bad = frame(1).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val out = BragiFrameReader().feed(bad + frame(2))
        assertEquals(1, out.size)
        assertEquals(0x10, BosRpcPacket.decode(out.single().body).let { it.flags })
        assertEquals(2, BosRpcPacket.decode(out.single().body).tag)
    }

    @Test fun `reader never keeps more than its buffer limit`() {
        val r = BragiFrameReader(maxBuffer = 64)
        // Garbage that is not a frame is skipped, so the buffer cannot grow without bound.
        val hdr = byteArrayOf(0x7E, 0xFF.toByte(), 0x80.toByte(), 0x94.toByte(), 0xEA.toByte(), 0x60)
        r.feed(hdr)
        r.feed(ByteArray(200))
        assertTrue(r.pendingSize <= 64)
    }

    // ── Transmit wrappers ────────────────────────────────────────────────────────────────

    @Test fun `raw wrapper changes nothing`() {
        val f = frame(0)
        assertEquals(f.toHex(), RawPppWrapper.wrap(f).toHex())
    }


    // ── Link profile order ───────────────────────────────────────────────────────────────

    @Test fun `profiles are tried in order and the remembered one goes first`() {
        val f = BragiLinkFactory()
        assertEquals(BragiLinkProfile.DEFAULTS.map { it.id }, f.orderFor(null).map { it.id })
        val learned = BragiLinkProfile.DEFAULTS[1].id
        val order = f.orderFor(learned).map { it.id }
        assertEquals(learned, order.first())
        assertEquals(BragiLinkProfile.DEFAULTS.size, order.size)
        assertEquals(order.size, order.toSet().size)
        // An id from an older version is ignored.
        assertEquals(BragiLinkProfile.DEFAULTS.map { it.id }, f.orderFor("no-such-profile").map { it.id })
    }

    @Test fun `BOS-RPC goes raw on the data service first and nothing is ever RACE-wrapped`() {
        assertEquals(BragiLinkProfile.SERVICE_DATA, BragiLinkProfile.DEFAULTS.first().uuid)
        assertTrue(BragiLinkProfile.DEFAULTS.all { it.wrapper === RawPppWrapper })
    }

    // ── Which stack drives a device ──────────────────────────────────────────────────────

    @Test fun `family follows the bluetooth name`() {
        assertEquals(DeviceFamily.BRAGI, DeviceFamily.forName("Skullcandy CRUSHER 1080 ANC"))
        assertEquals(DeviceFamily.BRAGI, DeviceFamily.forName("Crusher 1080 ANC"))
        assertEquals(DeviceFamily.BOSE, DeviceFamily.forName("Bose QC Ultra Headphones"))
        assertEquals(DeviceFamily.BOSE, DeviceFamily.forName("Fargo")) // unknown name: Bose, as before
        assertEquals(DeviceFamily.BOSE, DeviceFamily.forName(null))
        assertEquals(DeviceFamily.BRAGI, DeviceFamily.fromKey("bragi"))
        assertEquals(DeviceFamily.BOSE, DeviceFamily.fromKey("BOSE"))
        assertEquals(null, DeviceFamily.fromKey("nonsense"))
    }

    @Test fun `each link factory handles only its own family`() {
        assertTrue(BoseLinkFactory().handles("Bose QC Ultra Headphones"))
        assertTrue(!BoseLinkFactory().handles("Skullcandy CRUSHER 1080 ANC"))
        assertTrue(BragiLinkFactory().handles("Skullcandy CRUSHER 1080 ANC"))
        assertTrue(!BragiLinkFactory().handles("Bose QC45"))
    }
}

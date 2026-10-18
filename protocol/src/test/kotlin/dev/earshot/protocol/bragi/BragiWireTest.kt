package dev.earshot.protocol.bragi

import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Locks the Crusher 1080 BOS-RPC wire format to the golden frames recovered from the Skullcandy
 * app 5.0.6 (the CRCs were reproduced independently from the app's captured frames). If any of
 * these change, the on-wire bytes have changed.
 */
class BragiWireTest {

    private fun hex(s: String) = s.hexToBytes()
    private fun ByteArray.h() = toHex()

    // ── CRC known-answer (from the verified golden headers/frames) ───────────────────────

    @Test fun `crc8 rohc matches the golden headers`() {
        assertEquals(0xCE, Crc.crc8Rohc(hex("7E FF 80 94 00 04")))
        assertEquals(0xC7, Crc.crc8Rohc(hex("7E FF 80 94 00 08")))
    }

    @Test fun `crc16 mcrf4xx matches the golden frames`() {
        // FCS covers bytes 1..(6+len): dev_info body 10 02 01 00 -> AB00
        assertEquals(0xAB00, Crc.crc16Mcrf4xx(hex("FF 80 94 00 04 CE 10 02 01 00")))
        // mode_configure {ANC, activate} tag 0 -> 4D3B, tag 1 -> 467F
        assertEquals(0x4D3B, Crc.crc16Mcrf4xx(hex("FF 80 94 00 08 C7 10 04 02 00 08 02 10 01")))
        assertEquals(0x467F, Crc.crc16Mcrf4xx(hex("FF 80 94 00 08 C7 10 04 02 01 08 02 10 01")))
    }

    // ── Layer A: BOS-RPC packet (header + protobuf body) ─────────────────────────────────

    @Test fun `dev_info request packet carries the explicit unspecified gender`() {
        val p = BosRpcPacket.request(BragiServices.DEV_INFO, tag = 0, payload = DeviceInfo.devInfo())
        assertEquals("0800", DeviceInfo.devInfo().h())
        assertEquals("100201000800", p.encode().h())
        // With no body at all (a call that takes Empty) the packet is just the header.
        assertEquals("10020100", BosRpcPacket.request(BragiServices.DEV_INFO, tag = 0).encode().h())
    }

    @Test fun `mode_configure ANC activate packet`() {
        val body = Hearing.modeConfigure(HearingMode.ANC, activate = true)
        assertEquals("08021001", body.h()) // field1=ANC(2), field2=true
        val p0 = BosRpcPacket.request(BragiServices.MODE_CONFIGURE, tag = 0, payload = body)
        assertEquals("1004020008021001", p0.encode().h())
        val p1 = BosRpcPacket.request(BragiServices.MODE_CONFIGURE, tag = 1, payload = body)
        assertEquals("1004020108021001", p1.encode().h())
    }

    @Test fun `crusher state_get request and abort`() {
        val get = BosRpcPacket.request(BragiServices.CRUSHER_STATE_GET, tag = 0, payload = Crusher.stateGet(subscribe = false))
        assertEquals("10170200", get.encode().h())
        val abort = BosRpcPacket.abort(BragiServices.CRUSHER_STATE_GET, tag = 5)
        assertEquals("1c170205", abort.encode().h())
    }

    // ── Layer B: full bos_ppp_framing SDU ────────────────────────────────────────────────

    @Test fun `dev_info frames to the bytes the Skullcandy app sends`() {
        // Body 08 00: tag 0 and tag 1 as recovered from the app's builder.
        val f0 = PppFraming.frame(BosRpcPacket.request(BragiServices.DEV_INFO, 0, DeviceInfo.devInfo()).encode())
        assertEquals("7eff8094000 62d 100201000800 5480".replace(" ", ""), f0.h())
        val f1 = PppFraming.frame(BosRpcPacket.request(BragiServices.DEV_INFO, 1, DeviceInfo.devInfo()).encode())
        assertEquals("7eff8094000 62d 100201010800 0e5c".replace(" ", ""), f1.h())
    }

    @Test fun `a bodiless call frames to the empty-body golden bytes`() {
        val frame = PppFraming.frame(BosRpcPacket.request(BragiServices.DEV_INFO, 0).encode())
        assertEquals("7eff8094000 4ce 10020100 ab00".replace(" ", ""), frame.h())
    }

    @Test fun `mode_configure frames to the golden bytes for tag 0 and 1`() {
        val body = Hearing.modeConfigure(HearingMode.ANC, activate = true)
        val f0 = PppFraming.frame(BosRpcPacket.request(BragiServices.MODE_CONFIGURE, 0, body).encode())
        assertEquals("7eff80940008c710040200080210014d3b", f0.h())
        val f1 = PppFraming.frame(BosRpcPacket.request(BragiServices.MODE_CONFIGURE, 1, body).encode())
        assertEquals("7eff8094000 8c7 1004020108021001 467f".replace(" ", ""), f1.h())
    }

    // ── Round-trip: frame -> parse -> decode ─────────────────────────────────────────────

    @Test fun `parse round-trips a framed packet`() {
        val body = Hearing.modeConfigure(HearingMode.AT, activate = true, gain = 3)
        val packet = BosRpcPacket.request(BragiServices.MODE_CONFIGURE, tag = 7, payload = body)
        val frame = PppFraming.frame(packet.encode())
        val result = PppFraming.parse(frame)
        assertTrue(result is PppFraming.ParseResult.Frame)
        val decoded = (result as PppFraming.ParseResult.Frame).decoded
        assertEquals(PppFraming.PROTOCOL_CLEARTEXT, decoded.protocolId)
        assertEquals(frame.size, decoded.consumed)
        val back = BosRpcPacket.decode(decoded.body)
        assertEquals(packet, back)
        assertEquals(BragiServices.MODE_CONFIGURE, back.address)
    }

    @Test fun `parse reports incomplete then invalid`() {
        val frame = PppFraming.frame(BosRpcPacket.request(BragiServices.DEV_INFO, 0).encode())
        assertEquals(PppFraming.ParseResult.Incomplete, PppFraming.parse(frame.copyOfRange(0, 5)))
        val corrupt = frame.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(PppFraming.parse(corrupt) is PppFraming.ParseResult.Invalid)
        assertTrue(PppFraming.parse(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)) is PppFraming.ParseResult.Invalid)
    }

    // ── Response/config decoders ─────────────────────────────────────────────────────────

    @Test fun `crusher config and state decode`() {
        // max_intensity=10, intensity_read_only=false, mode_count=3
        val cfg = Crusher.parseConfig(ProtoWriter.build { uint32(1, 10, true); bool(2, false); uint32(3, 3, true) })
        assertEquals(Crusher.Config(10, false, 3), cfg)
        // is_active=true, intensity=5, mode=1
        val st = Crusher.parseState(ProtoWriter.build { bool(1, true); uint32(2, 5, true); uint32(3, 1, true) })
        assertEquals(Crusher.State(true, 5, 1), st)
    }

    @Test fun `dev_info decodes model and the auth gate`() {
        val body = ProtoWriter.build {
            string(4, "CHP300"); string(5, "1.2.3"); string(11, "S6CNW"); uint32(12, 1, true)
        }
        val info = DeviceInfo.parseDevInfo(body)
        assertEquals("CHP300", info.model)
        assertEquals("S6CNW", info.modelNumber)
        assertTrue(info.authenticatedAccess)
        // Default proto3 omits field 12 -> gate reads false.
        assertEquals(false, DeviceInfo.parseDevInfo(ProtoWriter.build { string(4, "x") }).authenticatedAccess)
    }

    @Test fun `battery decodes repeated entries`() {
        val one = ProtoWriter.build { uint32(2, 80, true); uint32(3, 2, true) }
        val body = ProtoWriter.build { message(1, one) }
        val b = DeviceInfo.parseBattery(body)
        assertEquals(1, b.size)
        assertEquals(80, b[0].level)
        assertEquals(DeviceInfo.BatteryStatus.CHARGING, b[0].status)
    }

    @Test fun `peq band encodes frequency gain and q`() {
        val band = Hearing.PeqBand(frequencyHz = 100, gainDb = -3.0f, q = 1.41f)
        val round = Hearing.PeqBand.parse(band.encode())
        assertEquals(band, round)
    }

    @Test fun `spatial mode set is a single enum field`() {
        assertEquals("0802", SpatialAudio.setMode(SpatialMode.STILL).h())
        assertEquals("0803", SpatialAudio.setMode(SpatialMode.MOTION).h())
        assertEquals("0801", SpatialAudio.setMode(SpatialMode.OFF).h())
    }

    @Test fun `services allow-list rejects everything outside the feature set`() {
        assertTrue(BragiServices.isAllowed(BragiServices.CRUSHER_STATE_SET))
        assertTrue(BragiServices.isAllowed(BragiServices.AUTH_HELLO))
        assertFalse(BragiServices.isAllowed(BragiServices.Address(service = 99, method = 1)))
    }

    @Test fun `frame rejects an oversize body`() {
        assertFailsWith<IllegalArgumentException> { PppFraming.frame(ByteArray(PppFraming.MAX_BODY + 1)) }
    }

    private fun assertFalse(b: Boolean) = assertTrue(!b)
}

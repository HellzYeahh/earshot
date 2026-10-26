package dev.earshot.protocol.jbl

import dev.earshot.protocol.jbl.JblCommands as C
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden frames: jbl-control's capture of a Wave Buds 2, firmware 2.7.0, MTU 517 (its
 * tests/fixtures/wave-buds-2.txt, MIT). "tx" is what Earshot must send byte for byte; "rx" is what
 * the buds sent back, which must fold into the right status.
 */
class JblWireTest {

    private fun hex(s: String): ByteArray = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

    private val rxDeviceInfo = "aa 12 25 4a 42 4c 20 57 61 76 65 20 42 75 64 73 20 32 00 00 21 00 64 ff 2d 2f 6a 1b 50 02 07 00 64 f2 96 10 ff ff 02 00"
    private val rxCallPush = "aa 06 05 00 02 00 1f 01 00"
    private val rxModes = "aa 91 07 12 01 00 02 01 03 00"
    private val rxInfo = "aa 06 41 00 02 00 01 10 4a 42 4c 20 57 61 76 65 20 42 75 64 73 20 32 00 00 05 03 02 07 00 00 06 01 06 00 0b 03 00 00 00 00 0c 02 00 00 00 0d 02 00 02 00 1a 02 00 64 00 1b 02 00 ff 00 1c 02 00 64 00 20 01 00"
    private val rxEq = "aa a2 74 00 04" + " 00".repeat(115)
    private val rxVoicePrompts = "aa 93 02 05 01"

    @Test fun `the frames Earshot sends are the captured ones`() {
        assertEquals("aa 9b 02 01 01", C.notifyOn().encode().hex())
        assertEquals("aa 91 01 11", C.modeGet().encode().hex())
        assertEquals("aa 91 01 21", C.levelGet().encode().hex())
        assertEquals(
            "aa 06 15 00 01 00 01 00 05 00 06 00 0b 00 0c 00 0d 00 1a 00 1b 00 1c 00 20",
            C.infoGet().encode().hex(),
        )
        assertEquals("aa a2 02 01 ff", C.eqGet().encode().hex())
        assertEquals("aa 93 01 04", C.voicePromptsGet().encode().hex())
        assertEquals("aa 98 01 01", C.voiceAwareGet().encode().hex())
        assertEquals("aa 23 01 01", C.findGet().encode().hex())
        assertEquals("aa 94 01 01", C.serialGet().encode().hex())
        assertEquals("aa 80 01 01", C.savGet().encode().hex())
        assertEquals("aa 11 00", C.deviceInfoGet().encode().hex())
    }

    @Test fun `mode, level and prompt writes`() {
        val all = listOf(1, 2, 3)
        // jbl-control's mode_set on the same buds.
        assertEquals("aa 91 07 10 01 00 02 01 03 00", C.modeSet(all, C.WIRE_AMBIENT).encode().hex())
        assertEquals("aa 91 07 10 01 01 02 00 03 00", C.modeSet(all, C.WIRE_ANC).encode().hex())
        assertEquals("aa 91 07 10 01 00 02 00 03 00", C.modeSet(all, null).encode().hex())
        // A headset that listed only ANC and ambient gets only those pairs.
        assertEquals("aa 91 05 10 01 01 02 00", C.modeSet(listOf(2, 1), C.WIRE_ANC).encode().hex())
        assertEquals("aa 91 03 20 04 05", C.ancLevelSet(5).encode().hex())
        assertEquals("aa 93 02 03 00", C.voicePromptsSet(false).encode().hex())
        assertEquals("aa b1 04 01 00 01 00", C.heartbeatEcho().encode().hex())
    }

    @Test fun `the EQ write is jbl-control's layout`() {
        val bass = C.eqPreset(3)!!
        val bytes = C.eqSet(bass.category, bass.gainsDb).encode()
        assertEquals("aa a2 74 00 03 03 00 00 80 bf 30 00 00 00 00 00 00 00 00 0a", bytes.copyOfRange(0, 20).hex())
        // First band: peak, +4 dB, 32 Hz, Q 1.0.
        assertEquals("01 00 00 80 40 00 00 00 42 0a", bytes.copyOfRange(20, 30).hex())
        assertEquals(4 + C.EQ_SET_SIZE, bytes.size)
        // Gains past ±6 dB are clamped.
        val loud = JblFrame.decodeWhole(C.eqSet(1, List(10) { 9f }).encode())!!
        assertEquals(List(10) { 6f }, JblStatus.eqGains(JblFrame(C.EQ, loud.payload.also { it[0] = 0x04 }, longLength = true)))
    }

    @Test fun `the captured replies fold into the status`() {
        val buffer = JblFrameBuffer()
        val frames = listOf(rxDeviceInfo, rxCallPush, rxModes, rxInfo, rxEq, rxVoicePrompts).flatMap { buffer.feed(hex(it)) }
        assertEquals(6, frames.size)
        assertTrue(frames.none { it.truncated })
        val s = frames.fold(JblStatus()) { acc, f -> acc.fold(f) }

        assertEquals(JblDeviceInfo("JBL Wave Buds 2", 0x2100, JblBattery(100, false), "2.7.0", "50:1B:6A:2F:2D:FF"), s.info)
        assertTrue(s.info!!.addressIs("FF:2D:2F:6A:1B:50"), "either byte order")
        assertFalse(s.info!!.addressIs("50:1B:6A:2F:2D:00"))
        assertEquals(JblModel.WAVE_BUDS_2, JblModel.fromPid(s.info?.productId))
        assertEquals(listOf(1, 2, 3), s.modes)
        assertEquals(C.WIRE_AMBIENT, s.activeMode)
        assertEquals("2.7.0", s.firmware)
        // Left in the ear at 100 %, right in the case (unknown), case at 100 %.
        assertEquals(JblBattery(100, false), s.left)
        assertEquals(JblBattery(null, false), s.right)
        assertEquals(JblBattery(100, false), s.case)
        assertTrue(s.reportsBuds)
        assertEquals(C.EQ_OFF, s.eqCategory)
        assertEquals(true, s.voicePrompts)
        assertNull(s.ancLevel, "the Wave Buds 2 never answered the level read")
    }

    @Test fun `frames split, joined and cut`() {
        val buffer = JblFrameBuffer()
        val bytes = hex(rxModes)
        assertTrue(buffer.feed(bytes.copyOfRange(0, 4)).isEmpty())
        val joined = buffer.feed(bytes.copyOfRange(4, bytes.size) + hex(rxVoicePrompts))
        assertEquals(listOf(C.LISTENING, C.VOICE_PROMPTS), joined.map { it.command })
        // A small MTU cut the device info; the next notification starts a new frame.
        val cut = JblFrameBuffer()
        assertTrue(cut.feed(hex(rxDeviceInfo).copyOfRange(0, 20)).isEmpty())
        val out = cut.feed(hex(rxModes))
        assertEquals(listOf(true, false), out.map { it.truncated })
        // Junk before a header is dropped.
        var junk = 0
        assertEquals(1, JblFrameBuffer { junk += it }.feed(hex("01 02 " + rxVoicePrompts)).size)
        assertEquals(2, junk)
    }

    @Test fun `a cut device info never replaces a whole one`() {
        val whole = JblFrameBuffer().feed(hex(rxDeviceInfo)).single()
        val cut = JblFrameBuffer().flushAfter(hex(rxDeviceInfo).copyOfRange(0, 20))
        assertTrue(cut.truncated)
        assertTrue(JblDeviceInfo.parse(cut)!!.isPartial, "17 payload bytes: the name, no product id")
        val s = JblStatus().fold(whole).fold(cut)
        assertEquals(0x2100, s.info?.productId)
        // On its own, a cut one is still better than nothing.
        assertEquals("JBL Wave Buds 2", JblStatus().fold(cut).info?.name)
    }

    private fun JblFrameBuffer.flushAfter(bytes: ByteArray): JblFrame {
        assertTrue(feed(bytes).isEmpty())
        return flush().single()
    }

    @Test fun `a cut or odd frame never throws`() {
        var s = JblStatus()
        for (raw in listOf("aa 91 02 12 01", "aa 06 03 00 02 00 1a", "aa a2 02 00 00", "aa 25 02 01 00", "aa 12 03 4a 42 4c")) {
            s = JblFrameBuffer().feed(hex(raw)).fold(s) { acc, f -> acc.fold(f) }
        }
        assertNull(s.modes)
        assertNull(s.eqCategory)
    }

    @Test fun `older earbuds report their batteries without left and right`() {
        val f = JblFrameBuffer().feed(hex("aa 25 07 01 00 01 50 5a 00 40")).single()
        val s = JblStatus().fold(f)
        assertEquals(JblBattery(80, false) to JblBattery(90, true), s.budPair)
        assertEquals(JblBattery(64, false), s.case)
        assertNull(s.left)
    }

    @Test fun `whole phone frames decode in both length forms`() {
        assertEquals(C.eqGet(), JblFrame.decodeWhole(C.eqGet().encode()))
        assertEquals(C.infoGet(), JblFrame.decodeWhole(C.infoGet().encode()))
        val eq = C.eqSet(2, C.eqPreset(2)!!.gainsDb)
        assertEquals(eq, JblFrame.decodeWhole(eq.encode()))
        assertNull(JblFrame.decodeWhole(hex("aa 91 05 10")))
        assertFalse(JblFrame.decodeWhole(C.modeGet().encode())!!.longLength)
    }
}

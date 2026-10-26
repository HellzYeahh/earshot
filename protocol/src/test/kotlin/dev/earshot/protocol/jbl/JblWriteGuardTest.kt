package dev.earshot.protocol.jbl

import dev.earshot.protocol.jbl.JblCommands as C
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JblWriteGuardTest {

    private fun frame(command: Int, vararg bytes: Int, long: Boolean = false) =
        JblFrame(command, ByteArray(bytes.size) { bytes[it].toByte() }, longLength = long)

    @Test fun `everything Earshot builds passes`() {
        val built = listOf(
            C.notifyOn(), C.deviceInfoGet(), C.modeGet(), C.levelGet(), C.infoGet(), C.infoGet(C.BATTERY_FIDS), C.eqGet(),
            C.voicePromptsGet(), C.batteryGet(), C.heartbeatEcho(), C.serialGet(), C.voiceAwareGet(), C.findGet(), C.savGet(),
            C.modeSet(listOf(1, 2, 3), C.WIRE_TALKTHRU), C.modeSet(listOf(1, 2), null), C.ancLevelSet(1), C.ancLevelSet(7),
            C.voicePromptsSet(true), C.voicePromptsSet(false),
        ) + C.EQ_PRESETS.map { C.eqSet(it.category, it.gainsDb) }
        built.forEach { assertTrue(JblWriteGuard.isAllowed(it), "$it") }
        JblReads.ALL.forEach { assertTrue(JblWriteGuard.isRead(it.frame), it.name) }
    }

    @Test fun `the never list wins, whatever the payload`() {
        for (command in JblWriteGuard.NEVER) {
            for (payload in listOf(intArrayOf(), intArrayOf(0x01), intArrayOf(0x01, 0x01), intArrayOf(0x00))) {
                assertFalse(JblWriteGuard.isAllowed(frame(command, *payload)), "%02x".format(command))
            }
        }
        // Factory reset and power off by name.
        assertTrue(0x95 in JblWriteGuard.NEVER && 0x97 in JblWriteGuard.NEVER && 0xF0 in JblWriteGuard.NEVER)
    }

    @Test fun `known commands with other payloads are refused`() {
        val refused = listOf(
            frame(C.NOTIFY_ON, 0x01, 0x00),
            frame(C.LISTENING, 0x13), // "all off" in the notes: not Earshot's way
            frame(C.LISTENING, 0x02, 0x00, 0x01), // Tour One sub-modes
            frame(C.LISTENING, 0x10, 0x01, 0x01, 0x02, 0x01), // two modes on
            frame(C.LISTENING, 0x10, 0x04, 0x01), // unknown mode
            frame(C.LISTENING, 0x10, 0x01, 0x02), // flag not 0/1
            frame(C.LISTENING, 0x20, 0x04, 0x08), // level past 7
            frame(C.LISTENING, 0x20, 0x01, 0x01), // adaptive ANC: not driven
            frame(C.LISTENING, 0x20, 0xA1, 0x03), // ambient level: not driven
            frame(C.INFO, 0x00, 0x00, 0x0B, 0x03, 0x01, 0x1E, 0x00, long = true), // a TLV set (auto-off)
            frame(C.INFO, 0x01, 0x00, 0x25, long = true), // a TLV id outside the read list (manual power-off)
            frame(C.INFO, 0x01, 0x00, 0x01, 0x00, 0x01, long = true), // the same id twice
            frame(C.EQ, 0x01, 0x00), // a category read: only "current"
            frame(C.VOICE_PROMPTS, 0x03, 0x02),
            frame(C.VOICE_PROMPTS, 0x01), // language list
            frame(C.FIND, 0x00, 0x01, 0x01), // ringing: Earshot's own Find feature does that
            frame(C.HEARTBEAT, 0x01, 0x00, 0x01, 0x01),
            frame(0x34, 0x01), // voice assistant
            frame(0x33, 0x01, 0x85), // auto-off (old)
            frame(0x36, 0x10), // old find
            frame(0x77, 0x00, 0x03, 0x08), // gestures
            frame(0x81, 0x00), // codec blobs
        )
        refused.forEach { assertFalse(JblWriteGuard.isAllowed(it), "$it ${it.payload.joinToString(" ") { b -> "%02x".format(b) }}") }
    }

    @Test fun `an EQ write must be exactly the app's layout`() {
        val good = C.eqSet(1, C.eqPreset(1)!!.gainsDb)
        assertTrue(JblWriteGuard.isWrite(good))
        fun tweak(at: Int, value: Int): JblFrame = JblFrame(C.EQ, good.payload.also { it[at] = value.toByte() }, longLength = true)
        assertFalse(JblWriteGuard.isAllowed(tweak(1, C.EQ_CUSTOM)), "custom curves are not written")
        assertFalse(JblWriteGuard.isAllowed(tweak(1, 4)), "unknown category")
        assertFalse(JblWriteGuard.isAllowed(tweak(6, 44)), "sample rate")
        assertFalse(JblWriteGuard.isAllowed(tweak(15, 9)), "band count")
        assertFalse(JblWriteGuard.isAllowed(tweak(C.EQ_HEADER_SIZE, 2)), "band type")
        assertFalse(JblWriteGuard.isAllowed(tweak(C.EQ_HEADER_SIZE + 9, 20)), "Q")
        // +12 dB in the first band.
        assertFalse(JblWriteGuard.isAllowed(tweak(C.EQ_HEADER_SIZE + 4, 0x41)), "gain past 6 dB")
        assertFalse(JblWriteGuard.isAllowed(JblFrame(C.EQ, good.payload, longLength = false)), "the short form is the read")
        assertFalse(JblWriteGuard.isAllowed(JblFrame(C.EQ, good.payload.copyOf(C.EQ_SET_SIZE - 1), longLength = true)))
    }

    @Test fun `model by name and product id`() {
        assertEquals(JblModel.WAVE_BUDS_2, JblModel.fromName("JBL Wave Buds 2"))
        assertEquals(JblModel.WAVE_BUDS_2, JblModel.fromName("JBL Wave Buds 2-LE"))
        assertEquals(JblModel.TOUR_ONE_M2, JblModel.fromName("LE-JBL Tour One M2"))
        assertEquals(JblModel.LIVE_660NC, JblModel.fromName("JBL LIVE660NC"))
        assertEquals(JblModel.TUNE_230NC_TWS, JblModel.fromName("JBL TUNE230NC TWS"))
        assertEquals(JblModel.TUNE_230NC_TWS, JblModel.fromName("JBL Tune 230NC"))
        assertEquals(JblModel.LIVE_770NC, JblModel.fromName("JBL Live 770NC"))
        // Same digits, other stack (Airoha): never claimed.
        assertNull(JblModel.fromName("JBL Tune 770NC"))
        // Protocol 4 and not JBL at all.
        assertNull(JblModel.fromName("JBL Tour One M3"))
        assertNull(JblModel.fromName("Wave Buds 2"))
        assertNull(JblModel.fromName("JBL Wave Buds 2 Pro"))
        assertNull(JblModel.fromName(null))

        assertEquals(JblModel.TOUR_PRO_2, JblModel.fromPid(0x20E8))
        assertNull(JblModel.fromPid(0x2101))
        assertTrue(JblModel.refusal(0x2101)!!.contains("newer protocol"))
        assertTrue(JblModel.refusal(0x20B7)!!.contains("Airoha"))
        assertNull(JblModel.refusal(0x2100))
        assertNull(JblModel.refusal(null))
    }

    @Test fun `no product id is in two tables`() {
        val haa = JblModel.entries.flatMap { it.pids }
        assertEquals(haa.size, haa.toSet().size)
        assertTrue(haa.none { it in JblModel.NEWER_PROTOCOL_PIDS || it in JblModel.AIROHA_PIDS })
        assertTrue(JblModel.NEWER_PROTOCOL_PIDS.none { it in JblModel.AIROHA_PIDS })
    }
}

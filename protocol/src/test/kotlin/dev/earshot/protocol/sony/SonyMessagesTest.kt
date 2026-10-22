package dev.earshot.protocol.sony

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The message layouts against real replies: every `cap(…)` frame below is a received frame from
 * mos9527/SonyHeadphonesClient's captures (MIT): WF-1000XM5 firmware 6.1.0 unless noted, and the
 * LinkBuds Clip (WF-LC900) 2.0.3 for the 10-band EQ.
 */
class SonyMessagesTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The payload of a captured frame. */
    private fun cap(frame: String): ByteArray = TandemFrameBuffer().feed(hex(frame)).single().payload

    @Test fun `protocol info tells v2 from v1 and reads both table flags`() {
        val xm5 = assertNotNull(SonyProtocolInfo.parse(cap("3e 0c 01 00 00 00 08 01 00 03 00 30 18 00 00 61 3c")))
        assertTrue(xm5.isV2)
        assertEquals(0x03003018, xm5.version)
        assertTrue(xm5.table1 && xm5.table2)
        assertTrue(xm5.isKnownVersion)
        val clip = assertNotNull(SonyProtocolInfo.parse(cap("3e 0c 00 00 00 00 08 01 00 03 00 30 15 00 00 5d 3c")))
        assertEquals(0x03003015, clip.version)
        // A v1 headset (WH-1000XM3/XM4) answers with four bytes.
        val v1 = assertNotNull(SonyProtocolInfo.parse(hex("01 00 01 10")))
        assertFalse(v1.isV2)
        assertFalse(v1.isKnownVersion)
        // Table 2 off: EnableDisable 1 = disabled.
        assertFalse(assertNotNull(SonyProtocolInfo.parse(hex("01 00 03 00 30 18 00 01"))).table2)
        assertFalse(SonyProtocolVersion.isKnown(0x04000001))
        assertFalse(SonyProtocolVersion.isKnown(0x03999999))
    }

    @Test fun `device info - model name, firmware, series and colour`() {
        assertEquals("WF-1000XM5", SonyDeviceInfo.parseString(cap("3e 0c 00 00 00 00 0d 05 01 0a 57 46 2d 31 30 30 30 58 4d 35 8e 3c"), SonyDeviceInfo.MODEL_NAME))
        assertEquals("6.1.0", SonyDeviceInfo.parseString(cap("3e 0c 01 00 00 00 08 05 02 05 36 2e 31 2e 30 14 3c"), SonyDeviceInfo.FW_VERSION))
        assertEquals(0x30 to 0x03, SonyDeviceInfo.parseSeries(cap("3e 0c 01 00 00 00 04 05 03 30 03 4c 3c")))
        assertEquals("1000X", SonyDeviceInfo.seriesName(0x30))
        assertNull(SonyDeviceInfo.parseString(cap("3e 0c 01 00 00 00 08 05 02 05 36 2e 31 2e 30 14 3c"), SonyDeviceInfo.MODEL_NAME))
    }

    @Test fun `support functions on both tables select the NCASM, EQ, battery and auto-off types`() {
        val t1 = assertNotNull(SonySupportFunctions.parse(cap(
            "3e 0c 00 00 00 00 5f 07 00 2e 10 ff 12 ff 13 ff 14 ff 15 ff 16 ff 17 ff 23 ff 25 36 11 ff 27 ff 29 ff 2a ff 92 ff " +
                "50 11 6b 08 70 01 90 ff a1 12 c1 ff c2 ff e2 1b f1 38 fc 0d 93 ff f6 33 f9 41 d1 3b 94 ff 40 2e 42 3a 44 ff ff 2d " +
                "19 ff f3 26 e7 1a d2 20 fd 2b b6 1d b0 42 b2 43 b8 1f b7 15 34 3f f4 23 f5 24 29 3c",
        )))
        assertEquals(46, t1.size)
        assertEquals(NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS, SonyNcAsm.typeFor(t1))
        assertEquals(EqType.PRESET_EQ, SonyEq.typeFor(t1))
        assertEquals(listOf(PowerType.LR_BATTERY_WITH_THRESHOLD, PowerType.CRADLE_BATTERY_WITH_THRESHOLD), SonyBattery.typesFor(t1))
        assertEquals(PowerType.AUTO_POWER_OFF_WEARING_DETECTION, SonyAutoPowerOff.typeFor(t1))
        assertTrue(SonyFunction.SMART_TALKING_MODE_TYPE2 in t1)
        assertTrue(SonyFunction.UPSCALING_AUTO_OFF in t1)
        assertNull(SonyAudio.connectionModeTypeFor(t1)) // the WF-1000XM5 has the Classic / LE Audio switch instead
        val t2 = assertNotNull(SonySupportFunctions.parse(cap(
            "3e 0e 01 00 00 00 19 07 00 0b 42 3d 2d 53 ff 54 ff f1 ff 60 ff 61 ff f2 ff 63 ff f8 30 32 04 31 ff ee 3c",
        )))
        assertEquals(setOf(0x42, 0x53, 0x54, 0xF1, 0x60, 0x61, 0xF2, 0x63, 0xF8, 0x32, 0x31), t2)
        assertEquals(0x01, SonyVoiceGuidance.typeFor(t2))
    }

    @Test fun `battery - left and right, case, and a single headband battery`() {
        val lr = assertIs<SonyBatteryReading.LeftRight>(SonyBattery.parse(cap("3e 0c 00 00 00 00 08 23 09 00 00 64 00 64 64 6c 3c")))
        assertEquals(0, lr.left.percent)
        assertEquals(100, lr.right.percent)
        assertFalse(lr.right.charging)
        val case = assertIs<SonyBatteryReading.Case>(SonyBattery.parse(cap("3e 0c 01 00 00 00 05 23 0a 35 00 1e 92 3c")))
        assertEquals(53, case.level.percent)
        val clip = assertIs<SonyBatteryReading.LeftRight>(SonyBattery.parse(cap("3e 0c 01 00 00 00 08 23 09 4e 00 00 00 64 64 57 3c")))
        assertEquals(78, clip.left.percent)
        // A headband model's push: 70 %, charging.
        val single = assertIs<SonyBatteryReading.Single>(SonyBattery.parse(hex("25 00 46 01")))
        assertEquals(SonyBatteryLevel(70, charging = true), single.level)
        assertNull(SonyBattery.parse(hex("23 00 c8 00"))) // 200 % is not a level
    }

    @Test fun `noise - the 0x17 reply, mode switching and the SET it builds`() {
        val nc = assertNotNull(SonyNcAsm.parse(cap("3e 0c 01 00 00 00 07 67 17 01 00 01 00 14 a8 3c")))
        assertEquals(SonyNoiseMode.OFF, nc.mode)
        assertEquals(20, nc.ambientLevel)
        assertFalse(nc.focusOnVoice)
        assertContentEquals(hex("68 17 01 01 00 00 14"), nc.withMode(SonyNoiseMode.NOISE_CANCELLING).encodeSet())
        assertContentEquals(hex("68 17 01 01 01 00 14"), nc.withMode(SonyNoiseMode.AMBIENT).encodeSet())
        assertContentEquals(hex("68 17 01 01 01 01 0a"), nc.withMode(SonyNoiseMode.AMBIENT).copy(focusOnVoice = true, ambientLevel = 10).encodeSet())
        assertEquals(SonyNoiseMode.AMBIENT, SonyNcAsm.parse(hex("69 17 01 01 01 00 05"))?.mode)
        assertEquals(SonyNoiseMode.NOISE_CANCELLING, SonyNcAsm.parse(hex("69 17 01 01 00 00 05"))?.mode)
    }

    @Test fun `noise - every driven type round-trips and maps to the right modes`() {
        val samples = mapOf(
            "67 01 01 01 01" to SonyNoiseMode.NOISE_CANCELLING,
            "67 11 01 01 00 00 01" to SonyNoiseMode.AMBIENT,
            "67 12 01 01 02 00 00" to SonyNoiseMode.NOISE_CANCELLING,
            "67 13 01 01 00 01 0f" to SonyNoiseMode.AMBIENT,
            "67 14 01 01 01 00 0f" to SonyNoiseMode.NOISE_CANCELLING,
            "67 15 01 01 01 03 00 0f" to SonyNoiseMode.AMBIENT,
            "67 16 01 00 00 02 00 0f" to SonyNoiseMode.OFF,
            "67 18 01 01 00 00 0f" to SonyNoiseMode.NOISE_CANCELLING,
            "67 19 01 01 01 01 0f 01 02" to SonyNoiseMode.AMBIENT,
            "67 21 01 01 00 01" to SonyNoiseMode.AMBIENT,
            "67 22 01 00 00 0f" to SonyNoiseMode.OFF,
        )
        for ((h, mode) in samples) {
            val s = assertNotNull(SonyNcAsm.parse(hex(h)), h)
            assertEquals(mode, s.mode, h)
            // Re-encoding as a SET gives the same bytes after the command byte.
            assertContentEquals(hex(h).copyOfRange(1, hex(h).size), s.encodeSet().copyOfRange(1, s.encodeSet().size), h)
            for (target in SonyNoiseMode.entries) {
                if (target == SonyNoiseMode.NOISE_CANCELLING && !s.hasNoiseCancelling) continue
                if (target == SonyNoiseMode.AMBIENT && !s.hasAmbient) continue
                val switched = s.withMode(target)
                assertEquals(target, switched.mode, "$h → $target")
                assertEquals(target, SonyNcAsm.parse(byteArrayOf(0x69) + switched.encodeSet().copyOfRange(1, switched.encodeSet().size))?.mode, "$h → $target on the wire")
            }
        }
        val na = assertNotNull(SonyNcAsm.parse(hex("67 19 01 01 01 01 0f 01 02")))
        assertEquals(true, na.autoAmbient)
        assertEquals(2, na.autoAmbientSensitivity)
        assertTrue(na.focusOnVoice)
        assertNull(SonyNcAsm.parse(hex("67 40 00 01")), "the test mode is never parsed as a setting")
        assertNull(SonyNcAsm.parse(hex("67 17 01 00")), "short frame")
    }

    @Test fun `noise - the capability gives the ambient level range`() {
        assertEquals(1..20, SonyNcAsm.parseAmbientRange(hex("61 17 02 00 01 14 01 01 01 14 01")))
        assertEquals(0..19, SonyNcAsm.parseAmbientRange(hex("61 22 01 00 00 13 01")))
        assertNull(SonyNcAsm.parseAmbientRange(hex("61 17 00")))
    }

    @Test fun `EQ - the 6-value layout (Clear Bass first) and the 10-band LinkBuds layout`() {
        val xm5 = assertNotNull(SonyEqSetting.parse(cap("3e 0c 01 00 00 00 0a 57 00 a0 06 14 0b 0a 0d 09 0c 5f 3c")))
        assertEquals(SonyEqPresets.CUSTOM, xm5.presetId)
        assertEquals(listOf(10, 1, 0, 3, -1, 2), xm5.bandsDb(21))
        val capClip = assertNotNull(SonyEqCapability.parse(cap("3e 0c 00 00 00 00 15 51 00 0a 0d 08 00 00 30 00 31 00 32 00 33 00 a0 00 a1 00 a2 00 3a 3c")))
        assertEquals(10, capClip.bandCount)
        assertEquals(13, capClip.steps)
        assertEquals(-6..6, capClip.range)
        assertEquals(listOf(0x00, 0x30, 0x31, 0x32, 0x33, 0xA0, 0xA1, 0xA2), capClip.presets)
        assertEquals(listOf("Off", "Heavy", "Clear", "Hard", "Soft", "Manual", "Custom 1", "Custom 2"), capClip.presets.map(SonyEqPresets::label))
        val heavy = assertNotNull(SonyEqSetting.parse(cap("3e 0c 00 00 00 00 0e 59 00 30 0a 0a 0a 05 05 06 06 06 06 06 06 ef 3c")))
        assertEquals(0x30, heavy.presetId)
        assertEquals(listOf(4, 4, -1, -1, 0, 0, 0, 0, 0, 0), heavy.bandsDb(13))
        // Writes: a preset alone, and bands under the Manual slot.
        assertContentEquals(hex("58 00 30 00"), SonyEq.setPreset(EqType.PRESET_EQ, 0x30))
        assertContentEquals(hex("58 00 a0 06 14 0b 0a 0d 09 0c"), SonyEq.setBands(EqType.PRESET_EQ, SonyEqPresets.CUSTOM, listOf(10, 1, 0, 3, -1, 2), 21))
        assertContentEquals(hex("58 31 03 00 06 0c"), SonyEq.setBands(EqType.CUSTOM_EQ, null, listOf(-6, 0, 9), 13)) // clamped to ±6
        assertEquals(listOf("Clear Bass", "400 Hz", "1 kHz", "2.5 kHz", "6.3 kHz", "16 kHz"), SonyEq.bandNames(6))
        assertEquals(21, SonyEq.defaultSteps(6))
        assertEquals(13, SonyEq.defaultSteps(10))
    }

    @Test fun `auto power off - the wearing-detection reply and the SET`() {
        val (current, last) = assertNotNull(SonyAutoPowerOff.parseParam(cap("3e 0c 00 00 00 00 04 27 05 10 00 4c 3c")))
        assertEquals(SonyAutoOff.WHEN_REMOVED, current)
        assertEquals(SonyAutoOff.MIN_5, last)
        assertEquals(listOf(SonyAutoOff.MIN_5, SonyAutoOff.MIN_30, SonyAutoOff.OFF), SonyAutoPowerOff.parseCapability(hex("21 04 03 00 01 11")))
        assertContentEquals(hex("28 04 01 01"), SonyAutoPowerOff.set(PowerType.AUTO_POWER_OFF, SonyAutoOff.MIN_30, SonyAutoOff.MIN_5))
        // Switching off remembers the last real timer for when it's switched back on.
        assertContentEquals(hex("28 05 11 03"), SonyAutoPowerOff.set(PowerType.AUTO_POWER_OFF_WEARING_DETECTION, SonyAutoOff.OFF, SonyAutoOff.MIN_180))
        assertContentEquals(hex("28 05 10 00"), SonyAutoPowerOff.set(PowerType.AUTO_POWER_OFF_WEARING_DETECTION, SonyAutoOff.WHEN_REMOVED, null))
    }

    @Test fun `general settings - named by the headset, ON is 0`() {
        val side = assertNotNull(SonyGeneralSetting.parseCapability(cap(
            "3e 0c 00 00 00 00 2e d1 d1 00 01 10 53 49 44 45 54 4f 4e 45 5f 53 45 54 54 49 4e 47 18 53 49 44 45 54 4f 4e 45 5f 53 45 54 54 49 4e 47 5f 53 55 4d 4d 41 52 59 42 3c",
        )))
        assertEquals(0xD1, side.type)
        assertTrue(side.isBoolean)
        assertEquals("SIDETONE_SETTING", side.name)
        assertEquals("Hear your voice on calls", side.title)
        assertEquals(0xD1 to false, SonyGeneralSetting.parseBoolean(cap("3e 0c 01 00 00 00 04 d7 d1 00 01 ba 3c")))
        assertEquals(0xD2 to true, SonyGeneralSetting.parseBoolean(cap("3e 0c 01 00 00 00 04 d7 d2 00 00 ba 3c")))
        assertContentEquals(hex("d8 d2 00 01"), SonyGeneralSetting.setBoolean(0xD2, on = false))
        assertContentEquals(hex("d0 d1 01"), SonyGeneralSetting.getCapability(0xD1))
        assertEquals("Ambient sound button", SonyGeneralSetting(0xD3, true, "AMBIENT_SOUND_BUTTON_SETTING", enumName = true).title)
    }

    @Test fun `audio, system and voice guidance - DSEE, speak-to-chat, pause when taken off`() {
        assertEquals(0x02, SonyAudio.parseUpscalingType(cap("3e 0c 00 00 00 00 03 e1 01 02 f3 3c")))
        assertEquals("DSEE Extreme", SonyAudio.upscalingName(0x02))
        assertEquals(AudioType.UPSCALING to 1, SonyAudio.parseParam(cap("3e 0c 01 00 00 00 03 e7 01 01 f9 3c")))
        assertContentEquals(hex("e8 01 00"), SonyAudio.setUpscaling(false))
        assertContentEquals(hex("e8 00 01"), SonyAudio.setConnectionMode(AudioType.CONNECTION_MODE, 1))
        assertEquals(SystemType.SMART_TALKING_MODE_TYPE2 to false, SonySystem.parseBoolean(cap("3e 0c 00 00 00 00 04 f7 0c 01 01 15 3c")))
        assertEquals(SystemType.PLAYBACK_CONTROL_BY_WEARING to true, SonySystem.parseBoolean(cap("3e 0c 01 00 00 00 03 f7 01 00 08 3c")))
        assertContentEquals(hex("f8 0c 00 01"), SonySystem.setSpeakToChat(true))
        assertContentEquals(hex("f8 01 01"), SonySystem.setPlaybackByWearing(false))
        assertEquals(true, SonyVoiceGuidance.parse(cap("3e 0e 00 00 00 00 04 47 01 00 01 5b 3c")))
        assertContentEquals(hex("48 01 01"), SonyVoiceGuidance.set(0x01, on = false))
    }

    @Test fun `alerts - a positive-negative prompt is answered, a notice is not`() {
        val a = assertNotNull(SonyAlert.parse(hex("99 00 07 01")))
        assertTrue(a.needsAnswer)
        assertContentEquals(hex("98 00 07 01"), a.answer(positive = true))
        assertContentEquals(hex("98 00 07 00"), a.answer(positive = false))
        assertFalse(assertNotNull(SonyAlert.parse(hex("99 00 1c 00"))).needsAnswer)
        assertContentEquals(hex("94 00 00"), SonyAlert.ENABLE)
    }

    @Test fun `notify commands are recognised by their low nibble`() {
        for (c in listOf(0x25, 0x29, 0x55, 0x59, 0x65, 0x69, 0x99, 0xC9, 0xD9, 0xE9, 0xF9, 0xFD)) assertTrue(SonyMessages.isNotify(c), "%02x".format(c))
        for (c in listOf(0x23, 0x27, 0x57, 0x67, 0xD7, 0xE7, 0xF7, 0x01, 0x07)) assertFalse(SonyMessages.isNotify(c), "%02x".format(c))
    }
}

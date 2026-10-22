package dev.earshot.protocol.sony

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Sony allow-list: what the driver builds passes, the dangerous ids never do (sony.md §4). */
class SonyWriteGuardTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun t1(s: String) = SonyWriteGuard.isAllowed(SonyTable.T1, hex(s))
    private fun t2(s: String) = SonyWriteGuard.isAllowed(SonyTable.T2, hex(s))

    @Test fun `everything the driver sends is allowed`() {
        val allowed = listOf(
            SonyProtocolInfo.GET, SonyCapabilityInfo.GET, SonyDeviceInfo.get(1), SonyDeviceInfo.get(2), SonyDeviceInfo.get(3), SonySupportFunctions.GET,
            SonyBattery.get(PowerType.BATTERY), SonyBattery.get(PowerType.LR_BATTERY_WITH_THRESHOLD), SonyBattery.get(PowerType.CRADLE_BATTERY),
            SonyAutoPowerOff.getCapability(PowerType.AUTO_POWER_OFF), SonyAutoPowerOff.getParam(PowerType.AUTO_POWER_OFF_WEARING_DETECTION),
            SonyAutoPowerOff.set(PowerType.AUTO_POWER_OFF, SonyAutoOff.MIN_30, null),
            SonyNcAsm.getCapability(NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS), SonyNcAsm.getParam(NcAsmType.ASM_SEAMLESS),
            SonyNcAsm(NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS, true, ncAsmMode = 0, ambientLevel = 20).encodeSet(),
            SonyNcAsm(NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA, true, ncAsmMode = 1, ambientLevel = 5, autoAmbient = true, autoAmbientSensitivity = 1).encodeSet(),
            SonyNcAsm(NcAsmType.NC_ON_OFF, true, ncOn = true).encodeSet(),
            SonyEqCapability.get(EqType.PRESET_EQ), SonyEqSetting.get(EqType.PRESET_EQ),
            SonyEq.setPreset(EqType.PRESET_EQ, 0x10), SonyEq.setBands(EqType.PRESET_EQ, 0xA0, listOf(0, 0, 0, 0, 0, 0), 21),
            SonyEq.setBands(EqType.CUSTOM_EQ, null, List(10) { 0 }, 13),
            SonyGeneralSetting.getCapability(0xD1), SonyGeneralSetting.getParam(0xD2), SonyGeneralSetting.setBoolean(0xD2, true),
            SonyAudio.getCapability(AudioType.UPSCALING), SonyAudio.getParam(AudioType.UPSCALING), SonyAudio.setUpscaling(true),
            SonyAudio.getParam(AudioType.CONNECTION_MODE), SonyAudio.setConnectionMode(AudioType.CONNECTION_MODE, 1),
            SonySystem.getParam(SystemType.SMART_TALKING_MODE_TYPE2), SonySystem.setSpeakToChat(false),
            SonySystem.getParam(SystemType.PLAYBACK_CONTROL_BY_WEARING), SonySystem.setPlaybackByWearing(true),
            SonyBattery.USER_POWER_OFF, SonyAlert.ENABLE, SonyAlert(0x07, 1).answer(true),
        )
        for (p in allowed) assertTrue(SonyWriteGuard.isAllowed(SonyTable.T1, p), p.joinToString(" ") { "%02x".format(it) })
        assertTrue(SonyWriteGuard.isAllowed(SonyTable.T2, SonySupportFunctions.GET))
        assertTrue(SonyWriteGuard.isAllowed(SonyTable.T2, SonyVoiceGuidance.getParam(0x01)))
        assertTrue(SonyWriteGuard.isAllowed(SonyTable.T2, SonyVoiceGuidance.set(0x01, false)))
    }

    @Test fun `firmware update is refused, every UPDT command and the transfer ones`() {
        for (cmd in (0x30..0x39) + 0x3E + 0x3F) {
            for (type in listOf(0x00, 0x01, 0x03, 0x06)) {
                assertFalse(t1("%02x %02x".format(cmd, type)), "UPDT %02x %02x".format(cmd, type))
                assertFalse(t1("%02x %02x 01 00 00".format(cmd, type)))
            }
        }
    }

    @Test fun `test commands, NC test mode, training and logging are refused`() {
        assertFalse(t1("0f 00"))
        assertFalse(t1("0f 01 00"))
        assertFalse(t1("64 40 00")) // NCASM_SET_STATUS NC test mode start
        assertFalse(t1("68 40 01 01 00 00 00"))
        assertFalse(t1("66 40")) // not even read
        assertFalse(t1("f4 0b 00")) // face-tap test mode
        assertFalse(t1("f8 0b 00"))
        assertFalse(t1("f4 10 00")) // head-gesture training
        assertFalse(t1("f8 10 00"))
        assertFalse(t2("f4 01 00")) // table-2 repeat-tap training
        assertFalse(t2("f8 01 00"))
        for (type in 0..2) {
            assertFalse(t1("c4 %02x 00".format(type)), "LOG_SET_STATUS enable")
            assertFalse(t1("c4 %02x 01".format(type)))
        }
        assertFalse(t1("c9 01 00"))
    }

    @Test fun `reset, factory power off and unpair are refused`() {
        assertFalse(t1("f8 09 00")) // reset settings
        assertFalse(t1("f8 09 01")) // factory reset
        assertFalse(t1("f4 09 01"))
        assertFalse(t1("24 03 ff")) // FACTORY_POWER_OFF
        assertFalse(t1("24 03 00"))
        assertFalse(t1("24 03 01 00")) // the user power-off with a trailing byte
        assertTrue(t1("24 03 01"))
        val address = "41 41 3a 42 42 3a 43 43 3a 44 44 3a 45 45 3a 46 46" // "AA:BB:CC:DD:EE:FF"
        assertFalse(t2("3c 00 02 $address")) // unpair
        assertFalse(t2("3c 02 02 $address"))
        assertFalse(t2("3c 00 01 $address")) // connect a paired source: not in the MVP either
        assertFalse(t2("34 00 01 00")) // pairing mode
        assertFalse(t2("38 01 00")) // source switch
    }

    @Test fun `data uploads, link-restarting switches and unknown writes are refused`() {
        assertFalse(t1("58 36 00 01 02")) // customizable sound-effect external update
        assertFalse(t1("e8 05 01")) // Classic / LE Audio connection mode
        assertFalse(t1("f8 04 31")) // voice assistant
        assertFalse(t1("f8 03 02 35 20")) // button assignment (not driven)
        assertFalse(t1("68 30 02")) // NC/AMB button function
        assertFalse(t2("44 01 00")) // voice-guidance language transfer status
        assertFalse(t1("ff 00"))
        assertFalse(t1("00"))
        assertFalse(t1(""))
    }

    @Test fun `lengths and values are checked, not only the command`() {
        assertFalse(t1("68 17 01 01 00 00")) // one byte short
        assertFalse(t1("68 17 01 01 00 00 14 00")) // one byte long
        assertFalse(t1("68 17 05 01 00 00 14")) // valueChangeStatus 5
        assertFalse(t1("58 00 a0 06 0a 0a")) // says six bands, carries two
        assertFalse(t1("28 04 7f 00")) // not an auto-off element
        assertFalse(t1("d8 d1 01 00")) // a list setting written as boolean
        assertFalse(t1("d8 d5 00 00")) // no GS 5
        assertFalse(t1("e8 01 02"))
        assertFalse(t1("98 00 07 02"))
        assertFalse(t1("00 00 00"))
        // The right table matters: table-2 voice guidance is not a table-1 command.
        assertFalse(t1("48 01 00"))
        assertFalse(t2("68 17 01 01 00 00 14"))
    }

    @Test fun `check throws for a refused message`() {
        assertFailsWith<IllegalArgumentException> { SonyWriteGuard.check(SonyMessage(SonyTable.T1, hex("c4 01 00"))) }
        SonyWriteGuard.check(SonyMessage(SonyTable.T1, SonyProtocolInfo.GET))
    }
}

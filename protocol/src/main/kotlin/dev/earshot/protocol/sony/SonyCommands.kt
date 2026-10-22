package dev.earshot.protocol.sony

/**
 * The two MDR v2 command tables. A message is `[command, inquiredType, …]`; commands come in
 * groups of ten per function block (GET/RET CAPABILITY, GET/RET/SET/NTFY STATUS, GET/RET/SET/NTFY
 * PARAM). Table 1 rides Tandem data type 0x0C, table 2 rides 0x0E. Values from Sound Connect 13.3.0
 * (facts only) and mos9527/SonyHeadphonesClient's generated `ProtocolV2T1/T2` headers (MIT); see
 * docs/research/research_notes/Top brand headset protocols/sony.md §3.
 */
enum class SonyTable(val dataType: Int) {
    T1(TandemDataType.DATA_MDR),
    T2(TandemDataType.DATA_MDR_NO2),
    ;

    companion object {
        /** Which table a received frame's data type belongs to; null for anything else (ignored). */
        fun forDataType(type: Int): SonyTable? = when (type) {
            TandemDataType.DATA_MDR, TandemDataType.SHOT_MDR, TandemDataType.LARGE_DATA_MDR -> T1
            TandemDataType.DATA_MDR_NO2, TandemDataType.SHOT_MDR_NO2 -> T2
            else -> null
        }
    }
}

/** One MDR message (the Tandem payload) and the table it belongs to. */
class SonyMessage(val table: SonyTable, val payload: ByteArray) {
    val command: Int get() = payload.u8(0)
    val type: Int get() = payload.u8(1)

    /** NTFY commands (low nibble 5, 9 or D: `65`, `69`, `25`, `F9`, `FD`…) are pushed by the headset. */
    val isNotify: Boolean get() = SonyMessages.isNotify(command)

    override fun toString(): String = "${table.name} %02x %02x (%d bytes)".format(command, type, payload.size)
}

/** Table 1 command bytes (the first payload byte). */
object T1 {
    const val CONNECT_GET_PROTOCOL_INFO = 0x00
    const val CONNECT_RET_PROTOCOL_INFO = 0x01
    const val CONNECT_GET_CAPABILITY_INFO = 0x02
    const val CONNECT_RET_CAPABILITY_INFO = 0x03
    const val CONNECT_GET_DEVICE_INFO = 0x04
    const val CONNECT_RET_DEVICE_INFO = 0x05
    const val CONNECT_GET_SUPPORT_FUNCTION = 0x06
    const val CONNECT_RET_SUPPORT_FUNCTION = 0x07

    /** Never send. */
    const val GET_TEST = 0x0F

    const val COMMON_GET_CAPABILITY = 0x10
    const val COMMON_GET_STATUS = 0x12
    const val COMMON_RET_STATUS = 0x13
    const val COMMON_NTFY_STATUS = 0x15

    const val POWER_GET_CAPABILITY = 0x20
    const val POWER_RET_CAPABILITY = 0x21
    const val POWER_GET_STATUS = 0x22
    const val POWER_RET_STATUS = 0x23
    const val POWER_SET_STATUS = 0x24
    const val POWER_NTFY_STATUS = 0x25
    const val POWER_GET_PARAM = 0x26
    const val POWER_RET_PARAM = 0x27
    const val POWER_SET_PARAM = 0x28
    const val POWER_NTFY_PARAM = 0x29

    /** Firmware update, 0x30–0x39 plus [UPDT_TRANSFER_DATA] and [UPDT_NTFY_MESSAGE]: never sent. */
    val UPDT = 0x30..0x39
    const val UPDT_TRANSFER_DATA = 0x3E
    const val UPDT_NTFY_MESSAGE = 0x3F

    const val EQEBB_GET_CAPABILITY = 0x50
    const val EQEBB_RET_CAPABILITY = 0x51
    const val EQEBB_GET_STATUS = 0x52
    const val EQEBB_RET_STATUS = 0x53
    const val EQEBB_NTFY_STATUS = 0x55
    const val EQEBB_GET_PARAM = 0x56
    const val EQEBB_RET_PARAM = 0x57
    const val EQEBB_SET_PARAM = 0x58
    const val EQEBB_NTFY_PARAM = 0x59

    const val NCASM_GET_CAPABILITY = 0x60
    const val NCASM_RET_CAPABILITY = 0x61
    const val NCASM_GET_STATUS = 0x62
    const val NCASM_SET_STATUS = 0x64
    const val NCASM_GET_PARAM = 0x66
    const val NCASM_RET_PARAM = 0x67
    const val NCASM_SET_PARAM = 0x68
    const val NCASM_NTFY_PARAM = 0x69

    const val ALERT_SET_STATUS = 0x94
    const val ALERT_SET_PARAM = 0x98
    const val ALERT_NTFY_PARAM = 0x99

    /** Usage logging: never switched on (mos9527's init does; Earshot does not). */
    const val LOG_SET_STATUS = 0xC4
    const val LOG_NTFY_PARAM = 0xC9

    const val GENERAL_SETTING_GET_CAPABILITY = 0xD0
    const val GENERAL_SETTING_RET_CAPABILITY = 0xD1
    const val GENERAL_SETTING_GET_PARAM = 0xD6
    const val GENERAL_SETTING_RET_PARAM = 0xD7
    const val GENERAL_SETTING_SET_PARAM = 0xD8
    const val GENERAL_SETTING_NTFY_PARAM = 0xD9

    const val AUDIO_GET_CAPABILITY = 0xE0
    const val AUDIO_RET_CAPABILITY = 0xE1
    const val AUDIO_GET_STATUS = 0xE2
    const val AUDIO_GET_PARAM = 0xE6
    const val AUDIO_RET_PARAM = 0xE7
    const val AUDIO_SET_PARAM = 0xE8
    const val AUDIO_NTFY_PARAM = 0xE9

    const val SYSTEM_GET_CAPABILITY = 0xF0
    const val SYSTEM_SET_STATUS = 0xF4
    const val SYSTEM_GET_PARAM = 0xF6
    const val SYSTEM_RET_PARAM = 0xF7
    const val SYSTEM_SET_PARAM = 0xF8
    const val SYSTEM_NTFY_PARAM = 0xF9
    const val SYSTEM_GET_EXT_PARAM = 0xFA
    const val SYSTEM_RET_EXT_PARAM = 0xFB
    const val SYSTEM_SET_EXT_PARAM = 0xFC
    const val SYSTEM_NTFY_EXT_PARAM = 0xFD
}

/** Table 2 command bytes. There is no firmware-update block here. */
object T2 {
    const val CONNECT_GET_SUPPORT_FUNCTION = 0x06
    const val CONNECT_RET_SUPPORT_FUNCTION = 0x07
    const val PERI_SET_STATUS = 0x34
    const val PERI_SET_PARAM = 0x38

    /** Connect / disconnect / **unpair** a paired source (`[3C, type, action, address]`): never sent. */
    const val PERI_SET_EXTENDED_PARAM = 0x3C
    const val VOICE_GUIDANCE_GET_PARAM = 0x46
    const val VOICE_GUIDANCE_RET_PARAM = 0x47
    const val VOICE_GUIDANCE_SET_PARAM = 0x48
    const val SYSTEM_SET_STATUS = 0xF4
    const val SYSTEM_SET_PARAM = 0xF8
}

/**
 * Support-function ids (`CONNECT_RET_SUPPORT_FUNCTION`, MDR v2 `FunctionType`), only the ones
 * the driver looks at. The headset announces these; features come from this list, never from
 * the model name.
 */
object SonyFunction {
    // Table 1.
    const val CODEC_INDICATOR = 0x12
    const val BATTERY = 0x20
    const val LEFT_RIGHT_BATTERY = 0x21
    const val CRADLE_BATTERY = 0x22
    const val POWER_OFF = 0x23
    const val AUTO_POWER_OFF = 0x24
    const val AUTO_POWER_OFF_WITH_WEARING_DETECTION = 0x25
    const val BATTERY_WITH_THRESHOLD = 0x28
    const val LR_BATTERY_WITH_THRESHOLD = 0x29
    const val CRADLE_BATTERY_WITH_THRESHOLD = 0x2A
    const val PRESET_EQ = 0x50
    const val PRESET_EQ_NON_CUSTOMIZABLE = 0x52
    const val CUSTOM_EQ = 0x55
    const val PRESET_EQ_AND_ERRORCODE = 0x57
    const val FIXED_MESSAGE = 0x90
    const val GENERAL_SETTING_1 = 0xD1
    const val GENERAL_SETTING_4 = 0xD4
    const val CONNECTION_MODE_QUALITY = 0xE1
    const val UPSCALING_AUTO_OFF = 0xE2
    const val CONNECTION_MODE_QUALITY_WITH_LDAC = 0xE3
    const val PLAYBACK_CONTROL_BY_WEARING = 0xF1
    const val SMART_TALKING_MODE_TYPE2 = 0xFC

    // Table 2.
    const val VG_NO_LANGUAGE_SWITCH = 0x40
    const val VG_LANGUAGE_SWITCH = 0x41
    const val VG_LANGUAGE_SWITCH_AND_VOLUME = 0x42
    const val VG_ONLY_ON_OFF = 0x45

    /**
     * FunctionType → the NCASM inquired type it selects (Sound Connect's init): NC on/off 0x61 → 0x01,
     * 0x62 → 0x11, 0x63 → 0x12, 0x64 → 0x13, 0x65 → 0x14, ambient on/off 0x66 → 0x21, ambient
     * level 0x67 → 0x22, 0x68 → 0x15, 0x6A → 0x16, 0x6B → 0x17, 0x6C → 0x18, 0x6D → 0x19.
     */
    val NCASM_TYPE_FOR_FUNCTION: Map<Int, Int> = mapOf(
        0x61 to NcAsmType.NC_ON_OFF,
        0x62 to NcAsmType.NC_ON_OFF_AND_ASM_ON_OFF,
        0x63 to NcAsmType.NC_MODE_SWITCH_AND_ASM_ON_OFF,
        0x64 to NcAsmType.NC_ON_OFF_AND_ASM_SEAMLESS,
        0x65 to NcAsmType.NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        0x66 to NcAsmType.ASM_ON_OFF,
        0x67 to NcAsmType.ASM_SEAMLESS,
        0x68 to NcAsmType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        0x6A to NcAsmType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        0x6B to NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        0x6C to NcAsmType.MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
        0x6D to NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
    )

    /** FunctionType → EQ inquired type. PRESET_EQ_AND_ULT_MODE (0x53) has another layout and is left out. */
    val EQ_TYPE_FOR_FUNCTION: Map<Int, Int> = mapOf(
        PRESET_EQ to EqType.PRESET_EQ,
        PRESET_EQ_NON_CUSTOMIZABLE to EqType.PRESET_EQ_NONCUSTOMIZABLE,
        PRESET_EQ_AND_ERRORCODE to EqType.PRESET_EQ_AND_ERRORCODE,
        CUSTOM_EQ to EqType.CUSTOM_EQ,
    )

    /** Table-2 FunctionType → voice-guidance inquired type whose on/off is `[48, type, ON 0 | OFF 1]`. */
    val VOICE_GUIDANCE_TYPE_FOR_FUNCTION: Map<Int, Int> = mapOf(
        VG_NO_LANGUAGE_SWITCH to 0x00,
        VG_LANGUAGE_SWITCH to 0x01,
        VG_LANGUAGE_SWITCH_AND_VOLUME to 0x01,
        VG_ONLY_ON_OFF to 0x03,
    )
}

/** NCASM inquired types (the second byte of every `6x` message). */
object NcAsmType {
    const val NC_ON_OFF = 0x01
    const val NC_ON_OFF_AND_ASM_ON_OFF = 0x11
    const val NC_MODE_SWITCH_AND_ASM_ON_OFF = 0x12
    const val NC_ON_OFF_AND_ASM_SEAMLESS = 0x13
    const val NC_MODE_SWITCH_AND_ASM_SEAMLESS = 0x14
    const val MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS = 0x15
    const val MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS = 0x16
    const val MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS = 0x17
    const val MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS = 0x18
    const val MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA = 0x19
    const val ASM_ON_OFF = 0x21
    const val ASM_SEAMLESS = 0x22

    /** Which function the NC/AMB button cycles. Not driven. */
    const val NC_AMB_TOGGLE = 0x30

    /** Factory test mode: never sent. */
    const val NC_TEST_MODE = 0x40

    /** The types Earshot reads and writes, with each one's SET length (command byte included). */
    val SET_LENGTH: Map<Int, Int> = mapOf(
        NC_ON_OFF to 5,
        NC_ON_OFF_AND_ASM_ON_OFF to 7,
        NC_MODE_SWITCH_AND_ASM_ON_OFF to 7,
        NC_ON_OFF_AND_ASM_SEAMLESS to 7,
        NC_MODE_SWITCH_AND_ASM_SEAMLESS to 7,
        MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS to 8,
        MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS to 8,
        MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS to 7,
        MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS to 7,
        MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA to 9,
        ASM_ON_OFF to 6,
        ASM_SEAMLESS to 6,
    )
}

/** EQEBB inquired types. */
object EqType {
    const val PRESET_EQ = 0x00
    const val PRESET_EQ_NONCUSTOMIZABLE = 0x02
    const val PRESET_EQ_AND_ERRORCODE = 0x04
    const val CUSTOM_EQ = 0x31

    /** Downloadable sound-effect packs: a data upload, never sent. */
    const val CUSTOMIZABLE_SOUND_EFFECT_EXTERNAL_UPDATE = 0x36
}

/** POWER inquired types. */
object PowerType {
    const val BATTERY = 0x00
    const val LEFT_RIGHT_BATTERY = 0x01
    const val CRADLE_BATTERY = 0x02
    const val POWER_OFF = 0x03
    const val AUTO_POWER_OFF = 0x04
    const val AUTO_POWER_OFF_WEARING_DETECTION = 0x05
    const val BATTERY_WITH_THRESHOLD = 0x08
    const val LR_BATTERY_WITH_THRESHOLD = 0x09
    const val CRADLE_BATTERY_WITH_THRESHOLD = 0x0A

    /** `POWER_SET_STATUS [24, 03, 01]`: the only power-off Earshot sends. 0xFF would be FACTORY_POWER_OFF. */
    const val USER_POWER_OFF = 0x01
    const val FACTORY_POWER_OFF = 0xFF
}

/** AUDIO inquired types. */
object AudioType {
    const val CONNECTION_MODE = 0x00
    const val UPSCALING = 0x01
    const val CONNECTION_MODE_WITH_LDAC_STATUS = 0x02

    /** Classic / LE Audio switch: restarts the link. Not sent. */
    const val CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO = 0x05
}

/** SYSTEM inquired types (table 1). */
object SystemType {
    const val PLAYBACK_CONTROL_BY_WEARING = 0x01
    const val ASSIGNABLE_SETTINGS = 0x03
    const val VOICE_ASSISTANT_SETTINGS = 0x04
    const val RESET_SETTINGS = 0x09
    const val FACE_TAP_TEST_MODE = 0x0B
    const val SMART_TALKING_MODE_TYPE2 = 0x0C
    const val HEAD_GESTURE_TRAINING = 0x10
}

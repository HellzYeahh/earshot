package dev.earshot.protocol.sony

/**
 * The single write path's allow-list for Sony, like `bt/WriteGuard` for BMAP: refuse by default.
 * A message leaves the phone only when its `[command, inquiredType]` is listed below **and** its
 * length (and, for writes, the value bytes) matches what the driver builds. Everything else is
 * refused before it reaches the socket, in particular (sony.md §4):
 *  - firmware update: table 1 UPDT `30`–`39`, `3E` UPDT_TRANSFER_DATA, `3F` UPDT_NTFY_MESSAGE;
 *  - test: `0F` GET_TEST, NCASM type `40` NC test mode, SYSTEM `0B` face-tap test, `10`
 *    head-gesture training, table-2 SYSTEM `01` repeat-tap training;
 *  - logging: `C4` LOG_SET_STATUS (Earshot never switches usage logs on), `C9`;
 *  - SYSTEM `09` reset settings / factory reset; any power off other than USER `24 03 01`
 *    (`24 03 FF` is FACTORY_POWER_OFF);
 *  - table-2 PERI (pairing mode `34`, source management `38`, connect / disconnect / **unpair** `3C`);
 *  - data uploads (EQEBB `36` sound-effect packs, voice-guidance language transfers), the
 *    Classic / LE Audio switch (AUDIO `05`) and voice-assistant changes (SYSTEM `04`), which
 *    restart the link or the headset.
 */
object SonyWriteGuard {
    private class Rule(val table: SonyTable, val command: Int, val types: Set<Int>, val valid: (ByteArray) -> Boolean)

    private val AUTO_OFF_CODES = SonyAutoOff.entries.map { it.code }.toSet()
    private val NCASM_TYPES = NcAsmType.SET_LENGTH.keys
    private val EQ_TYPES = setOf(EqType.PRESET_EQ, EqType.PRESET_EQ_NONCUSTOMIZABLE, EqType.PRESET_EQ_AND_ERRORCODE, EqType.CUSTOM_EQ)
    private val GS_TYPES = (0xD1..0xD4).toSet()
    private val VG_TYPES = setOf(0x00, 0x01, 0x03)
    private val BIT = 0..1

    private fun len(n: Int): (ByteArray) -> Boolean = { it.size == n }

    private val RULES: List<Rule> = listOf(
        // ---- Reads (table 1).
        Rule(SonyTable.T1, T1.CONNECT_GET_PROTOCOL_INFO, setOf(0x00), len(2)),
        Rule(SonyTable.T1, T1.CONNECT_GET_CAPABILITY_INFO, setOf(0x00), len(2)),
        Rule(SonyTable.T1, T1.CONNECT_GET_DEVICE_INFO, setOf(0x01, 0x02, 0x03, 0x04), len(2)),
        Rule(SonyTable.T1, T1.CONNECT_GET_SUPPORT_FUNCTION, setOf(0x00), len(2)),
        Rule(SonyTable.T1, T1.COMMON_GET_STATUS, setOf(0x02), len(2)),
        Rule(SonyTable.T1, T1.POWER_GET_CAPABILITY, setOf(PowerType.AUTO_POWER_OFF, PowerType.AUTO_POWER_OFF_WEARING_DETECTION), len(2)),
        Rule(
            SonyTable.T1, T1.POWER_GET_STATUS,
            setOf(
                PowerType.BATTERY, PowerType.LEFT_RIGHT_BATTERY, PowerType.CRADLE_BATTERY,
                PowerType.BATTERY_WITH_THRESHOLD, PowerType.LR_BATTERY_WITH_THRESHOLD, PowerType.CRADLE_BATTERY_WITH_THRESHOLD,
            ),
            len(2),
        ),
        Rule(SonyTable.T1, T1.POWER_GET_PARAM, setOf(PowerType.AUTO_POWER_OFF, PowerType.AUTO_POWER_OFF_WEARING_DETECTION), len(2)),
        Rule(SonyTable.T1, T1.EQEBB_GET_CAPABILITY, EQ_TYPES) { it.size == 2 || it.size == 3 },
        Rule(SonyTable.T1, T1.EQEBB_GET_STATUS, setOf(EqType.PRESET_EQ), len(2)),
        Rule(SonyTable.T1, T1.EQEBB_GET_PARAM, EQ_TYPES, len(2)),
        Rule(SonyTable.T1, T1.NCASM_GET_CAPABILITY, NCASM_TYPES, len(2)),
        Rule(SonyTable.T1, T1.NCASM_GET_PARAM, NCASM_TYPES, len(2)),
        Rule(SonyTable.T1, T1.GENERAL_SETTING_GET_CAPABILITY, GS_TYPES, len(3)),
        Rule(SonyTable.T1, T1.GENERAL_SETTING_GET_PARAM, GS_TYPES, len(2)),
        Rule(SonyTable.T1, T1.AUDIO_GET_CAPABILITY, setOf(AudioType.UPSCALING), len(2)),
        Rule(SonyTable.T1, T1.AUDIO_GET_PARAM, setOf(AudioType.CONNECTION_MODE, AudioType.UPSCALING, AudioType.CONNECTION_MODE_WITH_LDAC_STATUS), len(2)),
        Rule(SonyTable.T1, T1.SYSTEM_GET_PARAM, setOf(SystemType.PLAYBACK_CONTROL_BY_WEARING, SystemType.SMART_TALKING_MODE_TYPE2), len(2)),

        // ---- Writes (table 1): one setting each, values range-checked.
        Rule(SonyTable.T1, T1.POWER_SET_STATUS, setOf(PowerType.POWER_OFF)) { it.size == 3 && it.u8(2) == PowerType.USER_POWER_OFF },
        Rule(SonyTable.T1, T1.POWER_SET_PARAM, setOf(PowerType.AUTO_POWER_OFF, PowerType.AUTO_POWER_OFF_WEARING_DETECTION)) {
            it.size == 4 && it.u8(2) in AUTO_OFF_CODES && it.u8(3) in AUTO_OFF_CODES
        },
        Rule(SonyTable.T1, T1.NCASM_SET_PARAM, NCASM_TYPES) { it.size == NcAsmType.SET_LENGTH[it.u8(1)] && it.u8(2) in BIT && it.u8(3) in BIT },
        Rule(SonyTable.T1, T1.EQEBB_SET_PARAM, EQ_TYPES) {
            if (it.u8(1) == EqType.CUSTOM_EQ) it.size >= 3 && it.u8(2) <= MAX_BANDS && it.size == 3 + it.u8(2)
            else it.size >= 4 && it.u8(3) <= MAX_BANDS && it.size == 4 + it.u8(3)
        },
        Rule(SonyTable.T1, T1.GENERAL_SETTING_SET_PARAM, GS_TYPES) { it.size == 4 && it.u8(2) == 0 && it.u8(3) in BIT },
        Rule(SonyTable.T1, T1.AUDIO_SET_PARAM, setOf(AudioType.CONNECTION_MODE, AudioType.UPSCALING, AudioType.CONNECTION_MODE_WITH_LDAC_STATUS)) {
            it.size == 3 && it.u8(2) in BIT
        },
        Rule(SonyTable.T1, T1.SYSTEM_SET_PARAM, setOf(SystemType.PLAYBACK_CONTROL_BY_WEARING)) { it.size == 3 && it.u8(2) in BIT },
        Rule(SonyTable.T1, T1.SYSTEM_SET_PARAM, setOf(SystemType.SMART_TALKING_MODE_TYPE2)) { it.size == 4 && it.u8(2) in BIT && it.u8(3) in BIT },
        // Confirmation prompts: switch fixed-message alerts on, answer one.
        Rule(SonyTable.T1, T1.ALERT_SET_STATUS, setOf(SonyAlert.FIXED_MESSAGE)) { it.size == 3 && it.u8(2) in BIT },
        Rule(SonyTable.T1, T1.ALERT_SET_PARAM, setOf(SonyAlert.FIXED_MESSAGE)) { it.size == 4 && it.u8(3) in BIT },

        // ---- Table 2.
        Rule(SonyTable.T2, T2.CONNECT_GET_SUPPORT_FUNCTION, setOf(0x00), len(2)),
        Rule(SonyTable.T2, T2.VOICE_GUIDANCE_GET_PARAM, VG_TYPES, len(2)),
        Rule(SonyTable.T2, T2.VOICE_GUIDANCE_SET_PARAM, VG_TYPES) { it.size == 3 && it.u8(2) in BIT },
    )

    /** More bands than any Sony EQ has (10); guards a corrupt length byte. */
    private const val MAX_BANDS = 16

    fun isAllowed(table: SonyTable, payload: ByteArray): Boolean {
        if (payload.size < 2) return false
        val command = payload.u8(0)
        val type = payload.u8(1)
        return RULES.any { it.table == table && it.command == command && type in it.types && it.valid(payload) }
    }

    fun isAllowed(message: SonyMessage): Boolean = isAllowed(message.table, message.payload)

    fun check(message: SonyMessage) {
        require(isAllowed(message)) { "Sony message $message is not on Earshot's allow-list" }
    }
}

package dev.earshot.protocol.sony

/** One documented MDR v2 inquiry for the Lab. [group] is its function block, see [SonyReads.groupOf]. */
class SonyRead(val table: SonyTable, payload: ByteArray, val name: String) {
    private val body = payload.copyOf()
    val payload: ByteArray get() = body.copyOf()
    val command: Int get() = body.u8(0)
    val type: Int get() = body.u8(1)
    val group: Int get() = SonyReads.groupOf(table, command)
    fun message(): SonyMessage = SonyMessage(table, body.copyOf())

    /** `T1 66 17`: the table and the message bytes, as the notes write them. */
    val address: String get() = "${table.name} " + body.joinToString(" ") { "%02x".format(it) }
}

/**
 * The Lab's read list for Sony (MDR v2). Command bytes come in blocks of ten and the neighbours of
 * every GET are writes; table 1 also holds firmware update (`30`-`39`, `3E`, `3F`), the test
 * command `0F`, usage logging (`C4`, `C9`) and factory reset (SYSTEM type `09`), and table 2 the
 * unpair command (`3C`). So the Lab never walks ids: it sends the connect inquiries every MDR v2
 * headset answers, then the GET for each function the headset announced in its support lists,
 * built with the same helpers the driver uses. Every one must be a GET command and pass
 * [SonyWriteGuard] ([isRead]).
 */
object SonyReads {
    /** The GET commands of table 1 the Lab may send (CAPABILITY, STATUS and PARAM inquiries). */
    val T1_GETS: Set<Int> = setOf(
        T1.CONNECT_GET_PROTOCOL_INFO, T1.CONNECT_GET_CAPABILITY_INFO, T1.CONNECT_GET_DEVICE_INFO, T1.CONNECT_GET_SUPPORT_FUNCTION,
        T1.COMMON_GET_STATUS,
        T1.POWER_GET_CAPABILITY, T1.POWER_GET_STATUS, T1.POWER_GET_PARAM,
        T1.EQEBB_GET_CAPABILITY, T1.EQEBB_GET_STATUS, T1.EQEBB_GET_PARAM,
        T1.NCASM_GET_CAPABILITY, T1.NCASM_GET_PARAM,
        T1.GENERAL_SETTING_GET_CAPABILITY, T1.GENERAL_SETTING_GET_PARAM,
        T1.AUDIO_GET_CAPABILITY, T1.AUDIO_GET_PARAM,
        T1.SYSTEM_GET_PARAM,
    )

    val T2_GETS: Set<Int> = setOf(T2.CONNECT_GET_SUPPORT_FUNCTION, T2.VOICE_GUIDANCE_GET_PARAM)

    /** Table-2 groups are numbered from here, so both tables' blocks fit one id space. */
    const val TABLE2_GROUPS = 0x100

    /** The function block of [command]: its high nibble, plus [TABLE2_GROUPS] on table 2. */
    fun groupOf(table: SonyTable, command: Int): Int =
        (command and 0xF0) + if (table == SonyTable.T2) TABLE2_GROUPS else 0

    val GROUP_NAMES: Map<Int, String> = linkedMapOf(
        0x00 to "Connect",
        0x10 to "Common",
        0x20 to "Power and battery",
        0x50 to "Equaliser",
        0x60 to "Noise cancelling and ambient",
        0xD0 to "General settings",
        0xE0 to "Audio",
        0xF0 to "System",
        TABLE2_GROUPS + 0x00 to "Connect (table 2)",
        TABLE2_GROUPS + 0x40 to "Voice guidance",
    )

    fun groupName(group: Int): String = GROUP_NAMES[group] ?: "Block %02x".format(group and 0xFF)

    /** A message the Lab may send: a GET of its table and on the allow-list. */
    fun isRead(message: SonyMessage): Boolean {
        val gets = if (message.table == SonyTable.T1) T1_GETS else T2_GETS
        return message.payload.size >= 2 && message.command in gets && SonyWriteGuard.isAllowed(message)
    }

    private fun read1(payload: ByteArray, name: String) = SonyRead(SonyTable.T1, payload, name)
    private fun read2(payload: ByteArray, name: String) = SonyRead(SonyTable.T2, payload, name)

    /** The inquiries every MDR v2 headset answers; also all the Lab sends before the support lists are known. */
    val CONNECT: List<SonyRead> = listOf(
        read1(SonyProtocolInfo.GET, "Protocol info"),
        read1(SonyCapabilityInfo.GET, "Capability info"),
        read1(SonyDeviceInfo.get(SonyDeviceInfo.MODEL_NAME), "Model name"),
        read1(SonyDeviceInfo.get(SonyDeviceInfo.FW_VERSION), "Firmware version"),
        read1(SonyDeviceInfo.get(SonyDeviceInfo.SERIES_AND_COLOR), "Series and colour"),
        read1(SonySupportFunctions.GET, "Support functions (table 1)"),
    )

    private val BATTERY_NAMES = mapOf(
        PowerType.BATTERY to "Battery",
        PowerType.LEFT_RIGHT_BATTERY to "Battery left / right",
        PowerType.CRADLE_BATTERY to "Battery case",
        PowerType.BATTERY_WITH_THRESHOLD to "Battery (with threshold)",
        PowerType.LR_BATTERY_WITH_THRESHOLD to "Battery left / right (with threshold)",
        PowerType.CRADLE_BATTERY_WITH_THRESHOLD to "Battery case (with threshold)",
    )

    /** `DisplayLanguage.ENGLISH`, sent with the capability inquiries that take it, as the driver does. */
    private const val ENGLISH: Byte = 0x01

    /**
     * Every documented inquiry for a headset whose support lists are [t1] and [t2] (empty = not read
     * yet: only [CONNECT]); [table2] = the protocol info says table 2 is on.
     */
    fun forSupport(t1: Set<Int>, t2: Set<Int>, table2: Boolean): List<SonyRead> = buildList {
        addAll(CONNECT)
        if (SonyFunction.CODEC_INDICATOR in t1) add(read1(SonyMessages.get(T1.COMMON_GET_STATUS, 0x02), "Codec indicator"))
        // Battery: function 0x20 + n reads POWER type n.
        for ((type, name) in BATTERY_NAMES) if (0x20 + type in t1) add(read1(SonyBattery.get(type), name))
        for (fn in listOf(SonyFunction.AUTO_POWER_OFF, SonyFunction.AUTO_POWER_OFF_WITH_WEARING_DETECTION)) {
            if (fn !in t1) continue
            val type = fn - 0x20
            add(read1(SonyAutoPowerOff.getCapability(type), "Auto power-off choices"))
            add(read1(SonyAutoPowerOff.getParam(type), "Auto power-off"))
        }
        for ((fn, type) in SonyFunction.EQ_TYPE_FOR_FUNCTION) {
            if (fn !in t1) continue
            add(read1(SonyEqCapability.get(type) + byteArrayOf(ENGLISH), "EQ capability"))
            if (type == EqType.PRESET_EQ) add(read1(SonyMessages.get(T1.EQEBB_GET_STATUS, type), "EQ status"))
            add(read1(SonyEqSetting.get(type), "EQ preset and bands"))
        }
        for ((fn, type) in SonyFunction.NCASM_TYPE_FOR_FUNCTION) {
            if (fn !in t1) continue
            if (SonyNcAsmShape.hasLevel(type)) add(read1(SonyNcAsm.getCapability(type), "Noise control: ambient range"))
            add(read1(SonyNcAsm.getParam(type), "Noise control"))
        }
        for (gs in SonyFunction.GENERAL_SETTING_1..SonyFunction.GENERAL_SETTING_4) {
            if (gs !in t1) continue
            add(read1(SonyGeneralSetting.getCapability(gs), "General setting %02x: name".format(gs)))
            add(read1(SonyGeneralSetting.getParam(gs), "General setting %02x".format(gs)))
        }
        if (SonyFunction.UPSCALING_AUTO_OFF in t1) {
            add(read1(SonyAudio.getCapability(AudioType.UPSCALING), "DSEE type"))
            add(read1(SonyAudio.getParam(AudioType.UPSCALING), "DSEE"))
        }
        if (SonyFunction.CONNECTION_MODE_QUALITY in t1) add(read1(SonyAudio.getParam(AudioType.CONNECTION_MODE), "Connection priority"))
        if (SonyFunction.CONNECTION_MODE_QUALITY_WITH_LDAC in t1) {
            add(read1(SonyAudio.getParam(AudioType.CONNECTION_MODE_WITH_LDAC_STATUS), "Connection priority (LDAC status)"))
        }
        if (SonyFunction.PLAYBACK_CONTROL_BY_WEARING in t1) add(read1(SonySystem.getParam(SystemType.PLAYBACK_CONTROL_BY_WEARING), "Pause when taken off"))
        if (SonyFunction.SMART_TALKING_MODE_TYPE2 in t1) add(read1(SonySystem.getParam(SystemType.SMART_TALKING_MODE_TYPE2), "Speak-to-chat"))
        if (table2) {
            add(read2(SonySupportFunctions.GET, "Support functions (table 2)"))
            for ((fn, type) in SonyFunction.VOICE_GUIDANCE_TYPE_FOR_FUNCTION) {
                if (fn in t2) add(read2(SonyVoiceGuidance.getParam(type), "Voice guidance"))
            }
        }
    }.distinctBy { it.address }.filter { isRead(it.message()) }
}

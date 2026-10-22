package dev.earshot.protocol.sony

/**
 * Builders and parsers for the MDR v2 messages the driver uses. Layouts from Sound Connect 13.3.0's
 * validators (facts only) and mos9527/SonyHeadphonesClient's `ProtocolV2T1/T2` structs (MIT);
 * golden frames in `SonyMessagesTest` come from mos9527's WF-1000XM5 and WF-LC900 captures.
 *
 * Wire conversions live here, so the app sees human units: percentages, dB, minutes, booleans.
 * Watch the enums: `OnOffSettingValue`, `EnableDisable` and `GsSettingValue` use **0 = on**, while
 * `NcAsmOnOffValue` uses 1 = on.
 */
object SonyMessages {
    /** NTFY commands have low nibble 5, 9 or D (`25`, `29`, `69`, `99`, `F9`, `FD`…). */
    fun isNotify(command: Int): Boolean = (command and 0x0F) in setOf(0x05, 0x09, 0x0D)

    /** A plain GET `[command, type]`. */
    fun get(command: Int, type: Int): ByteArray = byteArrayOf(command.toByte(), type.toByte())

    /** The reply command for a GET: the next command (`66` → `67`, `04` → `05`). */
    fun replyTo(getCommand: Int): Int = getCommand + 1

    /** `OnOffSettingValue` / `GsSettingValue` / `EnableDisable`: 0 = on, 1 = off. */
    fun onOffByte(on: Boolean): Byte = if (on) 0 else 1
    fun isOn(b: Int): Boolean? = when (b) { 0 -> true; 1 -> false; else -> null }
}

// ---- CONNECT ------------------------------------------------------------------------------

/**
 * `CONNECT_RET_PROTOCOL_INFO`. v1 replies with 4 bytes `[01, 00, version u16 BE]`; v2 with exactly
 * 8 `[01, 00, version u32 BE, table1, table2]` where the table bytes are `EnableDisable` (0 = on).
 * WF-1000XM5 fw 6.1.0: `01 00 03 00 30 18 00 00` = v2, version 0x03003018, both tables on.
 */
data class SonyProtocolInfo(val generation: Int, val version: Int, val table1: Boolean, val table2: Boolean) {
    val isV2: Boolean get() = generation == 2

    /** Sound Connect only runs MDR v2 versions it knows ([SonyProtocolVersion.isKnown]). */
    val isKnownVersion: Boolean get() = isV2 && SonyProtocolVersion.isKnown(version)

    val versionText: String get() = if (isV2) "%08x".format(version) else "%04x".format(version)

    companion object {
        val GET: ByteArray = SonyMessages.get(T1.CONNECT_GET_PROTOCOL_INFO, 0x00)

        fun parse(p: ByteArray): SonyProtocolInfo? {
            if (p.u8(0) != T1.CONNECT_RET_PROTOCOL_INFO) return null
            return when {
                p.size >= 8 -> SonyProtocolInfo(2, p.u32(2), p.u8(6) == 0, p.u8(7) == 0)
                p.size >= 4 -> SonyProtocolInfo(1, p.u16(2), table1 = true, table2 = false)
                else -> null
            }
        }
    }
}

/**
 * Which v2 protocol versions are known. Sound Connect accepts 0x01000000, 0x02000000–0x02001000 and
 * 0x03000000–0x03003037 (with gaps it lists one by one) and ends init on anything else; 0x03999999
 * and 0x04xxxxxx take a newer path. The gaps are not reproduced: Earshot treats the whole of
 * majors 1–3 as known and anything else as read-only (an inference, not app behaviour).
 */
object SonyProtocolVersion {
    fun isKnown(version: Int): Boolean = (version ushr 24) in 1..3 && version != 0x03999999
}

/** `CONNECT_GET_DEVICE_INFO [04, type]` → `[05, type, …]`. */
object SonyDeviceInfo {
    const val MODEL_NAME = 0x01
    const val FW_VERSION = 0x02
    const val SERIES_AND_COLOR = 0x03

    fun get(type: Int): ByteArray = SonyMessages.get(T1.CONNECT_GET_DEVICE_INFO, type)

    /** MODEL_NAME and FW_VERSION: `[05, type, len, UTF-8 …]`. Null for another reply. */
    fun parseString(p: ByteArray, type: Int): String? {
        if (p.u8(0) != T1.CONNECT_RET_DEVICE_INFO || p.u8(1) != type || p.size < 3) return null
        val len = minOf(p.u8(2), p.size - 3)
        return String(p, 3, len, Charsets.UTF_8).trim { it <= ' ' }.ifEmpty { null }
    }

    /** SERIES_AND_COLOR_INFO: exactly `[05, 03, series, colour]`. */
    fun parseSeries(p: ByteArray): Pair<Int, Int>? {
        if (p.u8(0) != T1.CONNECT_RET_DEVICE_INFO || p.u8(1) != SERIES_AND_COLOR || p.size < 4) return null
        return p.u8(2) to p.u8(3)
    }

    /** `ModelSeries` (v2). */
    fun seriesName(series: Int): String? = when (series) {
        0x10 -> "EXTRA BASS"
        0x11 -> "ULT"
        0x20 -> "h.ear"
        0x30 -> "1000X"
        0x40 -> "Sports"
        0x50 -> "Casual"
        0x60 -> "LinkBuds"
        0x70 -> "Neckband"
        0x90 -> "INZONE"
        else -> null
    }
}

/** `CONNECT_GET_SUPPORT_FUNCTION [06, 00]` → `[07, 00, n, (functionType, priority) × n]`, same on both tables. */
object SonySupportFunctions {
    val GET: ByteArray = SonyMessages.get(T1.CONNECT_GET_SUPPORT_FUNCTION, 0x00)

    fun parse(p: ByteArray): Set<Int>? {
        if (p.u8(0) != T1.CONNECT_RET_SUPPORT_FUNCTION || p.size < 3) return null
        val n = minOf(p.u8(2), (p.size - 3) / 2)
        return (0 until n).map { p.u8(3 + it * 2) }.toSet()
    }
}

/** `CONNECT_GET_CAPABILITY_INFO [02, 00]`: the app sends it before device info. Its string is the headset's own address; not logged. */
object SonyCapabilityInfo {
    val GET: ByteArray = SonyMessages.get(T1.CONNECT_GET_CAPABILITY_INFO, 0x00)
}

// ---- POWER --------------------------------------------------------------------------------

/** `BatteryChargingStatus`: NOT_CHARGING 0, CHARGING 1, UNKNOWN 2, CHARGED 3. */
data class SonyBatteryLevel(val percent: Int, val charging: Boolean, val full: Boolean = false)

/** One battery reply or push: which part(s) it describes. */
sealed interface SonyBatteryReading {
    data class Single(val level: SonyBatteryLevel) : SonyBatteryReading
    data class LeftRight(val left: SonyBatteryLevel, val right: SonyBatteryLevel) : SonyBatteryReading
    data class Case(val level: SonyBatteryLevel) : SonyBatteryReading
}

/**
 * Battery: GET `[22, type]` → `[23, type, …]`, push `[25, type, …]`.
 * ```
 *  00 single              level, charging
 *  01 left/right          L level, L charging, R level, R charging
 *  02 case                level, charging
 *  08 / 09 / 0A           the same plus threshold byte(s), ignored here
 * ```
 * WF-1000XM5: `23 09 00 00 64 00 64 64` = left 0 %, right 100 %; `23 0a 35 00 1e` = case 53 %.
 */
object SonyBattery {
    fun get(type: Int): ByteArray = SonyMessages.get(T1.POWER_GET_STATUS, type)

    fun parse(p: ByteArray): SonyBatteryReading? {
        if (p.u8(0) != T1.POWER_RET_STATUS && p.u8(0) != T1.POWER_NTFY_STATUS) return null
        return when (p.u8(1)) {
            PowerType.BATTERY, PowerType.BATTERY_WITH_THRESHOLD -> level(p, 2)?.let { SonyBatteryReading.Single(it) }
            PowerType.LEFT_RIGHT_BATTERY, PowerType.LR_BATTERY_WITH_THRESHOLD -> {
                val l = level(p, 2) ?: return null
                val r = level(p, 4) ?: return null
                SonyBatteryReading.LeftRight(l, r)
            }
            PowerType.CRADLE_BATTERY, PowerType.CRADLE_BATTERY_WITH_THRESHOLD -> level(p, 2)?.let { SonyBatteryReading.Case(it) }
            else -> null
        }
    }

    /** The status type to read for a support list, single → L/R → case; the plain variant wins over the threshold one. */
    fun typesFor(functions: Set<Int>): List<Int> = buildList {
        when {
            SonyFunction.BATTERY in functions -> add(PowerType.BATTERY)
            SonyFunction.BATTERY_WITH_THRESHOLD in functions -> add(PowerType.BATTERY_WITH_THRESHOLD)
        }
        when {
            SonyFunction.LEFT_RIGHT_BATTERY in functions -> add(PowerType.LEFT_RIGHT_BATTERY)
            SonyFunction.LR_BATTERY_WITH_THRESHOLD in functions -> add(PowerType.LR_BATTERY_WITH_THRESHOLD)
        }
        when {
            SonyFunction.CRADLE_BATTERY in functions -> add(PowerType.CRADLE_BATTERY)
            SonyFunction.CRADLE_BATTERY_WITH_THRESHOLD in functions -> add(PowerType.CRADLE_BATTERY_WITH_THRESHOLD)
        }
    }

    private fun level(p: ByteArray, at: Int): SonyBatteryLevel? {
        if (p.size < at + 2) return null
        val percent = p.u8(at).takeIf { it in 0..100 } ?: return null
        val status = p.u8(at + 1)
        return SonyBatteryLevel(percent, charging = status == 0x01, full = status == 0x03)
    }

    /** `POWER_SET_STATUS [24, 03, 01]` USER_POWER_OFF. */
    val USER_POWER_OFF: ByteArray = byteArrayOf(T1.POWER_SET_STATUS.toByte(), PowerType.POWER_OFF.toByte(), PowerType.USER_POWER_OFF.toByte())
}

/** One auto-power-off choice (`AutoPowerOffElements` / `…WearingDetectionElements`). */
enum class SonyAutoOff(val code: Int, val minutes: Int?, val label: String) {
    MIN_5(0x00, 5, "5 min"),
    MIN_15(0x04, 15, "15 min"),
    MIN_30(0x01, 30, "30 min"),
    MIN_60(0x02, 60, "1 hour"),
    MIN_180(0x03, 180, "3 hours"),
    WHEN_REMOVED(0x10, null, "When taken off"),
    OFF(0x11, null, "Off"),
    ;

    companion object {
        fun fromCode(code: Int): SonyAutoOff? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Auto power off, type 0x04 (or 0x05 with "when taken off"): capability `[21, type, n, element × n]`,
 * GET `[26, type]` → `[27, type, current, lastSelected]`, SET `[28, type, current, lastSelected]`.
 * WF-1000XM5: `27 05 10 00` = "when taken off", last timer 5 min.
 */
object SonyAutoPowerOff {
    fun typeFor(functions: Set<Int>): Int? = when {
        SonyFunction.AUTO_POWER_OFF_WITH_WEARING_DETECTION in functions -> PowerType.AUTO_POWER_OFF_WEARING_DETECTION
        SonyFunction.AUTO_POWER_OFF in functions -> PowerType.AUTO_POWER_OFF
        else -> null
    }

    fun getCapability(type: Int): ByteArray = SonyMessages.get(T1.POWER_GET_CAPABILITY, type)
    fun getParam(type: Int): ByteArray = SonyMessages.get(T1.POWER_GET_PARAM, type)

    fun parseCapability(p: ByteArray): List<SonyAutoOff>? {
        if (p.u8(0) != T1.POWER_RET_CAPABILITY || p.size < 3) return null
        val n = minOf(p.u8(2), p.size - 3)
        return (0 until n).mapNotNull { SonyAutoOff.fromCode(p.u8(3 + it)) }
    }

    /** `(current, lastSelected)`. */
    fun parseParam(p: ByteArray): Pair<SonyAutoOff?, SonyAutoOff?>? {
        if (p.u8(0) != T1.POWER_RET_PARAM && p.u8(0) != T1.POWER_NTFY_PARAM) return null
        if (p.u8(1) != PowerType.AUTO_POWER_OFF && p.u8(1) != PowerType.AUTO_POWER_OFF_WEARING_DETECTION) return null
        if (p.size < 3) return null
        return SonyAutoOff.fromCode(p.u8(2)) to SonyAutoOff.fromCode(p.u8(3))
    }

    /** [lastTimer] is what the headset restores when auto-off is switched back on: the last real timer. */
    fun set(type: Int, choice: SonyAutoOff, lastTimer: SonyAutoOff?): ByteArray {
        val last = if (choice.minutes != null) choice else lastTimer?.takeIf { it.minutes != null } ?: SonyAutoOff.MIN_5
        return byteArrayOf(T1.POWER_SET_PARAM.toByte(), type.toByte(), choice.code.toByte(), last.code.toByte())
    }
}

// ---- NCASM --------------------------------------------------------------------------------

/** Noise cancelling, ambient sound or both off: how Earshot's three slots read on Sony. */
enum class SonyNoiseMode { NOISE_CANCELLING, AMBIENT, OFF }

/**
 * The noise / ambient setting, whatever the inquired [type]. Every NCASM param starts
 * `[67|68|69, type, valueChangeStatus, totalEffect]` (`valueChangeStatus` UNDER_CHANGING 0 while a
 * slider moves, CHANGED 1; `totalEffect` `NcAsmOnOffValue`, ON = the NC/ambient block is active).
 * Bytes after that, per type (offsets from the command byte):
 * ```
 *  01  NC on/off                       [4] nc on
 *  11  NC on/off + ASM on/off          [4] nc on, [5] ambient mode, [6] asm on
 *  12  NC mode switch + ASM on/off     [4] NcValue, [5] ambient mode, [6] asm on
 *  13  NC on/off + ASM seamless        [4] nc on, [5] ambient mode, [6] level
 *  14  NC mode switch + ASM seamless   [4] NcValue, [5] ambient mode, [6] level
 *  15/16 mode + NcValue + seamless     [4] NcAsmMode, [5] NcValue, [6] ambient mode, [7] level
 *  17/18 mode + seamless               [4] NcAsmMode (NC 0 / ASM 1), [5] ambient mode, [6] level
 *  19  17 + noise adaptation           ... [7] auto ambient level on, [8] sensitivity
 *  21  ASM on/off                      [4] ambient mode, [5] asm on
 *  22  ASM seamless                    [4] ambient mode, [5] level
 * ```
 * Ambient mode NORMAL 0 / VOICE 1 ("focus on voice"); NcValue OFF 0, ON_SINGLE 1, ON_DUAL 2, AUTO 3,
 * AUTO_SINGLE 4, AUTO_DUAL 5; sensitivity STANDARD 0, HIGH 1, LOW 2. WF-1000XM5:
 * `67 17 01 00 01 00 14` = off, last mode ambient at level 20, normal.
 */
data class SonyNcAsm(
    val type: Int,
    val effectOn: Boolean,
    /** `NcAsmMode` (types 15–19): 0 NC, 1 ambient. */
    val ncAsmMode: Int? = null,
    /** `NcValue` (types 12, 14, 15, 16). */
    val ncValue: Int? = null,
    /** NC on/off (types 01, 11, 13). */
    val ncOn: Boolean? = null,
    /** Ambient on/off (types 11, 12, 21). */
    val asmOn: Boolean? = null,
    val focusOnVoice: Boolean = false,
    /** The headset's own ambient level (types 13–19, 22); its range comes from the capability. */
    val ambientLevel: Int? = null,
    /** Type 19: automatic ambient level (noise adaptation). */
    val autoAmbient: Boolean? = null,
    /** Type 19: 0 standard, 1 high, 2 low. */
    val autoAmbientSensitivity: Int? = null,
) {
    val hasNoiseCancelling: Boolean get() = SonyNcAsmShape.hasNc(type)
    val hasAmbient: Boolean get() = SonyNcAsmShape.hasAmbient(type)
    val hasLevel: Boolean get() = SonyNcAsmShape.hasLevel(type)

    val mode: SonyNoiseMode
        get() = when {
            !effectOn -> SonyNoiseMode.OFF
            ncAsmMode != null -> if (ncAsmMode == MODE_NC) SonyNoiseMode.NOISE_CANCELLING else SonyNoiseMode.AMBIENT
            type == NcAsmType.NC_ON_OFF -> if (ncOn == true) SonyNoiseMode.NOISE_CANCELLING else SonyNoiseMode.OFF
            type == NcAsmType.ASM_ON_OFF -> if (asmOn == true) SonyNoiseMode.AMBIENT else SonyNoiseMode.OFF
            type == NcAsmType.ASM_SEAMLESS -> SonyNoiseMode.AMBIENT
            ncOn == true || (ncValue != null && ncValue != NC_VALUE_OFF) -> SonyNoiseMode.NOISE_CANCELLING
            asmOn == false -> SonyNoiseMode.OFF
            else -> SonyNoiseMode.AMBIENT
        }

    /**
     * The same setting switched to [target], keeping everything else. Noise cancelling on the
     * NcValue types keeps the last NcValue seen (or ON_DUAL); untested which values each model takes.
     */
    fun withMode(target: SonyNoiseMode): SonyNcAsm {
        require(target != SonyNoiseMode.NOISE_CANCELLING || hasNoiseCancelling) { "no noise cancelling" }
        require(target != SonyNoiseMode.AMBIENT || hasAmbient) { "no ambient sound" }
        val nc = target == SonyNoiseMode.NOISE_CANCELLING
        val amb = target == SonyNoiseMode.AMBIENT
        val ncValueOn = ncValue?.takeIf { it != NC_VALUE_OFF } ?: NC_VALUE_ON_DUAL
        return when (type) {
            NcAsmType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            -> copy(effectOn = nc || amb, ncAsmMode = if (amb) MODE_ASM else if (nc) MODE_NC else ncAsmMode ?: MODE_NC, ncValue = if (nc) ncValueOn else ncValue)
            NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmType.MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA,
            -> copy(effectOn = nc || amb, ncAsmMode = if (amb) MODE_ASM else if (nc) MODE_NC else ncAsmMode ?: MODE_NC)
            NcAsmType.NC_ON_OFF_AND_ASM_SEAMLESS -> copy(effectOn = nc || amb, ncOn = nc)
            NcAsmType.NC_MODE_SWITCH_AND_ASM_SEAMLESS -> copy(effectOn = nc || amb, ncValue = if (nc) ncValueOn else NC_VALUE_OFF)
            NcAsmType.NC_ON_OFF_AND_ASM_ON_OFF -> copy(effectOn = nc || amb, ncOn = nc, asmOn = amb)
            NcAsmType.NC_MODE_SWITCH_AND_ASM_ON_OFF -> copy(effectOn = nc || amb, ncValue = if (nc) ncValueOn else NC_VALUE_OFF, asmOn = amb)
            NcAsmType.NC_ON_OFF -> copy(effectOn = nc, ncOn = nc)
            NcAsmType.ASM_ON_OFF -> copy(effectOn = amb, asmOn = amb)
            else -> copy(effectOn = amb)
        }
    }

    /** `NCASM_SET_PARAM` for this setting, `valueChangeStatus` CHANGED. */
    fun encodeSet(): ByteArray {
        val len = NcAsmType.SET_LENGTH[type] ?: throw IllegalArgumentException("NCASM type %02x is not driven".format(type))
        val b = ByteArray(len)
        b[0] = T1.NCASM_SET_PARAM.toByte()
        b[1] = type.toByte()
        b[2] = 0x01 // CHANGED
        b[3] = onOff(effectOn)
        val voice = (if (focusOnVoice) 1 else 0).toByte()
        val level = (ambientLevel ?: 0).toByte()
        when (type) {
            NcAsmType.NC_ON_OFF -> b[4] = onOff(ncOn == true)
            NcAsmType.NC_ON_OFF_AND_ASM_ON_OFF -> { b[4] = onOff(ncOn == true); b[5] = voice; b[6] = onOff(asmOn == true) }
            NcAsmType.NC_MODE_SWITCH_AND_ASM_ON_OFF -> { b[4] = (ncValue ?: 0).toByte(); b[5] = voice; b[6] = onOff(asmOn == true) }
            NcAsmType.NC_ON_OFF_AND_ASM_SEAMLESS -> { b[4] = onOff(ncOn == true); b[5] = voice; b[6] = level }
            NcAsmType.NC_MODE_SWITCH_AND_ASM_SEAMLESS -> { b[4] = (ncValue ?: 0).toByte(); b[5] = voice; b[6] = level }
            NcAsmType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            -> { b[4] = (ncAsmMode ?: 0).toByte(); b[5] = (ncValue ?: 0).toByte(); b[6] = voice; b[7] = level }
            NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            NcAsmType.MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
            -> { b[4] = (ncAsmMode ?: 0).toByte(); b[5] = voice; b[6] = level }
            NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA -> {
                b[4] = (ncAsmMode ?: 0).toByte(); b[5] = voice; b[6] = level
                b[7] = onOff(autoAmbient == true); b[8] = (autoAmbientSensitivity ?: 0).toByte()
            }
            NcAsmType.ASM_ON_OFF -> { b[4] = voice; b[5] = onOff(asmOn == true) }
            NcAsmType.ASM_SEAMLESS -> { b[4] = voice; b[5] = level }
        }
        return b
    }

    companion object {
        const val MODE_NC = 0
        const val MODE_ASM = 1
        const val NC_VALUE_OFF = 0
        const val NC_VALUE_ON_DUAL = 2

        fun getParam(type: Int): ByteArray = SonyMessages.get(T1.NCASM_GET_PARAM, type)
        fun getCapability(type: Int): ByteArray = SonyMessages.get(T1.NCASM_GET_CAPABILITY, type)

        /** The NCASM type a support list selects; the richest one when several are listed. */
        fun typeFor(functions: Set<Int>): Int? =
            SonyFunction.NCASM_TYPE_FOR_FUNCTION.entries.filter { it.key in functions }.maxByOrNull { it.key }?.value

        /** `NCASM_RET_PARAM` / `NCASM_NTFY_PARAM`; null for another type or a short frame. */
        fun parse(p: ByteArray): SonyNcAsm? {
            if (p.u8(0) != T1.NCASM_RET_PARAM && p.u8(0) != T1.NCASM_NTFY_PARAM) return null
            val type = p.u8(1)
            val len = NcAsmType.SET_LENGTH[type] ?: return null
            if (p.size < len) return null
            val effect = p.u8(3) == 1
            fun on(i: Int) = p.u8(i) == 1
            return when (type) {
                NcAsmType.NC_ON_OFF -> SonyNcAsm(type, effect, ncOn = on(4))
                NcAsmType.NC_ON_OFF_AND_ASM_ON_OFF -> SonyNcAsm(type, effect, ncOn = on(4), focusOnVoice = on(5), asmOn = on(6))
                NcAsmType.NC_MODE_SWITCH_AND_ASM_ON_OFF -> SonyNcAsm(type, effect, ncValue = p.u8(4), focusOnVoice = on(5), asmOn = on(6))
                NcAsmType.NC_ON_OFF_AND_ASM_SEAMLESS -> SonyNcAsm(type, effect, ncOn = on(4), focusOnVoice = on(5), ambientLevel = p.u8(6))
                NcAsmType.NC_MODE_SWITCH_AND_ASM_SEAMLESS -> SonyNcAsm(type, effect, ncValue = p.u8(4), focusOnVoice = on(5), ambientLevel = p.u8(6))
                NcAsmType.MODE_NC_ASM_AUTO_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
                NcAsmType.MODE_NC_ASM_DUAL_SINGLE_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
                -> SonyNcAsm(type, effect, ncAsmMode = p.u8(4), ncValue = p.u8(5), focusOnVoice = on(6), ambientLevel = p.u8(7))
                NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
                NcAsmType.MODE_NC_NCSS_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS,
                -> SonyNcAsm(type, effect, ncAsmMode = p.u8(4), focusOnVoice = on(5), ambientLevel = p.u8(6))
                NcAsmType.MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA -> SonyNcAsm(
                    type, effect, ncAsmMode = p.u8(4), focusOnVoice = on(5), ambientLevel = p.u8(6),
                    autoAmbient = on(7), autoAmbientSensitivity = p.u8(8),
                )
                NcAsmType.ASM_ON_OFF -> SonyNcAsm(type, effect, focusOnVoice = on(4), asmOn = on(5))
                NcAsmType.ASM_SEAMLESS -> SonyNcAsm(type, effect, focusOnVoice = on(4), ambientLevel = p.u8(5))
                else -> null
            }
        }

        /**
         * `NCASM_RET_CAPABILITY` for the seamless types: `[61, type, n, (ambientMode, min, max, step) × n]`.
         * The NORMAL entry's range (or the first one's); null when the reply has none.
         */
        fun parseAmbientRange(p: ByteArray): IntRange? {
            if (p.u8(0) != T1.NCASM_RET_CAPABILITY || p.size < 3) return null
            val n = minOf(p.u8(2), (p.size - 3) / 4)
            val entries = (0 until n).map { i -> val o = 3 + i * 4; Triple(p.u8(o), p.u8(o + 1), p.u8(o + 2)) }
            val pick = entries.firstOrNull { it.first == 0 } ?: entries.firstOrNull() ?: return null
            return if (pick.third >= pick.second) pick.second..pick.third else null
        }

        private fun onOff(on: Boolean): Byte = if (on) 1 else 0
    }
}

/** What each NCASM type can do. */
object SonyNcAsmShape {
    fun hasNc(type: Int): Boolean = type == NcAsmType.NC_ON_OFF || type in 0x11..0x19
    fun hasAmbient(type: Int): Boolean = type in 0x11..0x19 || type == NcAsmType.ASM_ON_OFF || type == NcAsmType.ASM_SEAMLESS
    fun hasLevel(type: Int): Boolean = type in 0x13..0x19 || type == NcAsmType.ASM_SEAMLESS

    /** mos9527 caps the level at 20 and Plutoberth (v1) at 19; used when the capability can't be read. */
    val DEFAULT_LEVEL_RANGE = 1..20
}

// ---- EQEBB --------------------------------------------------------------------------------

/**
 * `EQEBB_RET_CAPABILITY` for the preset types: `[51, type, bandCount, levelSteps, presetCount,
 * (presetId, nameLen, name) × presetCount]`; CUSTOM_EQ (0x31) stops after `levelSteps`. Names are
 * empty on the models captured (the app localises the id), so [SonyEqPresets] names them.
 * WF-LC900: `51 00 0a 0d 08 …` = 10 bands, 13 steps (±6 dB), 8 presets.
 */
data class SonyEqCapability(val type: Int, val bandCount: Int, val steps: Int, val presets: List<Int>) {
    val customizable: Boolean get() = type != EqType.PRESET_EQ_NONCUSTOMIZABLE && bandCount > 0
    val range: IntRange get() = SonyEq.rangeFor(steps)

    companion object {
        fun get(type: Int): ByteArray = SonyMessages.get(T1.EQEBB_GET_CAPABILITY, type)

        fun parse(p: ByteArray): SonyEqCapability? {
            if (p.u8(0) != T1.EQEBB_RET_CAPABILITY || p.size < 4) return null
            val type = p.u8(1)
            val presets = ArrayList<Int>()
            if (type != EqType.CUSTOM_EQ && p.size >= 5) {
                var o = 5
                repeat(p.u8(4)) {
                    if (o >= p.size) return@repeat
                    presets += p.u8(o)
                    o += 2 + p.u8(o + 1).coerceAtLeast(0)
                }
            }
            return SonyEqCapability(type, p.u8(2), p.u8(3), presets)
        }
    }
}

/**
 * The EQ setting: GET `[56, type]` → `[57, type, presetId, n, step × n]` (push `59`); CUSTOM_EQ has
 * no preset byte: `[57, 31, n, step × n]`. Steps are unsigned, centred: dB = step − (steps − 1) / 2,
 * so ±10 dB in 21 steps (the 6-value layout, Clear Bass first, mos9527) and ±6 in 13 (10 bands).
 * WF-1000XM5: `57 00 a0 06 14 0b 0a 0d 09 0c` = custom, Clear Bass +10, 400 Hz +1, … 16 kHz +2.
 */
data class SonyEqSetting(val type: Int, val presetId: Int?, val steps: List<Int>) {
    fun bandsDb(levelSteps: Int): List<Int> = steps.map { it - SonyEq.center(levelSteps) }

    companion object {
        fun get(type: Int): ByteArray = SonyMessages.get(T1.EQEBB_GET_PARAM, type)

        fun parse(p: ByteArray): SonyEqSetting? {
            if (p.u8(0) != T1.EQEBB_RET_PARAM && p.u8(0) != T1.EQEBB_NTFY_PARAM) return null
            val type = p.u8(1)
            return if (type == EqType.CUSTOM_EQ) {
                if (p.size < 3) return null
                val n = minOf(p.u8(2), p.size - 3)
                SonyEqSetting(type, null, (0 until n).map { p.u8(3 + it) })
            } else {
                if (p.size < 4) return null
                val n = minOf(p.u8(3), p.size - 4)
                SonyEqSetting(type, p.u8(2), (0 until n).map { p.u8(4 + it) })
            }
        }
    }
}

object SonyEq {
    /** The level a step count centres on: 21 → 10, 13 → 6. */
    fun center(steps: Int): Int = ((steps - 1) / 2).coerceAtLeast(0)
    fun rangeFor(steps: Int): IntRange = center(steps).let { -it..it }

    /** When the capability can't be read: the two layouts mos9527 knows. */
    fun defaultSteps(bandCount: Int): Int = if (bandCount >= 10) 13 else 21

    /** Select a preset; the headset applies its own band values (`[58, type, id, 00]`). */
    fun setPreset(type: Int, presetId: Int): ByteArray =
        byteArrayOf(T1.EQEBB_SET_PARAM.toByte(), type.toByte(), presetId.toByte(), 0)

    /** Write band levels (dB) under [presetId]; CUSTOM_EQ has no preset byte. */
    fun setBands(type: Int, presetId: Int?, bandsDb: List<Int>, levelSteps: Int): ByteArray {
        val c = center(levelSteps)
        val raw = bandsDb.map { (it + c).coerceIn(0, levelSteps - 1).toByte() }
        val head = if (type == EqType.CUSTOM_EQ) byteArrayOf(T1.EQEBB_SET_PARAM.toByte(), type.toByte(), raw.size.toByte())
        else byteArrayOf(T1.EQEBB_SET_PARAM.toByte(), type.toByte(), (presetId ?: SonyEqPresets.CUSTOM).toByte(), raw.size.toByte())
        return head + raw.toByteArray()
    }

    fun typeFor(functions: Set<Int>): Int? =
        SonyFunction.EQ_TYPE_FOR_FUNCTION.entries.firstOrNull { it.key in functions }?.value

    /** Band names for the two known layouts; "Band n" otherwise. */
    fun bandNames(count: Int): List<String> = when (count) {
        6 -> listOf("Clear Bass", "400 Hz", "1 kHz", "2.5 kHz", "6.3 kHz", "16 kHz")
        5 -> listOf("400 Hz", "1 kHz", "2.5 kHz", "6.3 kHz", "16 kHz")
        10 -> listOf("31 Hz", "63 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")
        else -> (1..count).map { "Band $it" }
    }
}

/** `EqPresetId` names, as Sound Connect shows them. */
object SonyEqPresets {
    const val OFF = 0x00
    const val CUSTOM = 0xA0

    fun label(id: Int): String = when (id) {
        0x00 -> "Off"
        0x01 -> "Rock"
        0x02 -> "Pop"
        0x03 -> "Jazz"
        0x04 -> "Dance"
        0x05 -> "EDM"
        0x06 -> "R&B / Hip-hop"
        0x07 -> "Acoustic"
        0x10 -> "Bright"
        0x11 -> "Excited"
        0x12 -> "Mellow"
        0x13 -> "Relaxed"
        0x14 -> "Vocal"
        0x15 -> "Treble boost"
        0x16 -> "Bass boost"
        0x17 -> "Speech"
        0x20 -> "Gaming"
        0x21, 0x22, 0x23 -> "FPS ${id - 0x20}"
        0x30 -> "Heavy"
        0x31 -> "Clear"
        0x32 -> "Hard"
        0x33 -> "Soft"
        CUSTOM -> "Manual"
        in 0xA1..0xA5 -> "Custom ${id - 0xA0}"
        in 0xB0..0xBB -> "Artist ${id - 0xAF}"
        else -> "Preset %02X".format(id)
    }

    /** Custom slots keep their id when bands are edited; any other preset becomes [CUSTOM]. */
    fun isUserSlot(id: Int?): Boolean = id != null && id in CUSTOM..0xA5
}

// ---- GENERAL_SETTING ----------------------------------------------------------------------

/**
 * A setting the headset describes itself (D1–D4): capability `[D1, type, settingType, stringFormat,
 * len, name, len, summary]` with settingType BOOLEAN 0 / LIST 1 and stringFormat RAW_NAME 0 /
 * ENUM_NAME 1; GET `[D6, type]` → `[D7, type, settingType, value]` with value **ON 0 / OFF 1**; SET
 * `[D8, type, 00, value]`. WF-1000XM5: D1 = `SIDETONE_SETTING`, D2 = `MULTIPOINT_SETTING`.
 */
data class SonyGeneralSetting(val type: Int, val isBoolean: Boolean, val name: String, val enumName: Boolean) {
    /** A human title: Sound Connect's wording for the enum names it knows, else the name tidied up. */
    val title: String
        get() = if (!enumName) name else when (name) {
            "MULTIPOINT_SETTING" -> "Connect to 2 devices at once"
            "SIDETONE_SETTING" -> "Hear your voice on calls"
            "TOUCH_PANEL_SETTING" -> "Touch controls"
            "TAP_SENSITIVITY_SETTING" -> "Tap sensitivity"
            "VOICE_GUIDANCE_SETTING" -> "Voice guidance"
            else -> name.removeSuffix("_SETTING").split('_').joinToString(" ").lowercase().replaceFirstChar { it.uppercase() }
        }

    companion object {
        fun getCapability(type: Int): ByteArray =
            byteArrayOf(T1.GENERAL_SETTING_GET_CAPABILITY.toByte(), type.toByte(), DISPLAY_LANGUAGE_ENGLISH)

        fun getParam(type: Int): ByteArray = SonyMessages.get(T1.GENERAL_SETTING_GET_PARAM, type)

        fun parseCapability(p: ByteArray): SonyGeneralSetting? {
            if (p.u8(0) != T1.GENERAL_SETTING_RET_CAPABILITY || p.size < 5) return null
            val len = minOf(p.u8(4), p.size - 5)
            val name = String(p, 5, len, Charsets.UTF_8).trim()
            return SonyGeneralSetting(p.u8(1), p.u8(2) == 0, name, p.u8(3) == 1)
        }

        /** The boolean value, or null for a list setting / another reply. */
        fun parseBoolean(p: ByteArray): Pair<Int, Boolean>? {
            if (p.u8(0) != T1.GENERAL_SETTING_RET_PARAM && p.u8(0) != T1.GENERAL_SETTING_NTFY_PARAM) return null
            if (p.size < 4 || p.u8(2) != 0) return null
            val on = SonyMessages.isOn(p.u8(3)) ?: return null
            return p.u8(1) to on
        }

        fun setBoolean(type: Int, on: Boolean): ByteArray =
            byteArrayOf(T1.GENERAL_SETTING_SET_PARAM.toByte(), type.toByte(), 0, SonyMessages.onOffByte(on))

        /** `DisplayLanguage.ENGLISH`, as mos9527 asks. */
        private const val DISPLAY_LANGUAGE_ENGLISH: Byte = 0x01
    }
}

// ---- AUDIO / SYSTEM / VOICE GUIDANCE --------------------------------------------------------

/**
 * AUDIO: DSEE (type 01): capability `[E1, 01, UpscalingType]`, GET `[E6, 01]` → `[E7, 01, OFF 0 | AUTO 1]`,
 * SET `[E8, 01, 0|1]`. Connection priority (type 00, or 02 with LDAC status): `[E7, 00, PriorMode]`
 * with SOUND_QUALITY 0, CONNECTION_QUALITY 1; SET `[E8, 00, mode]`. WF-1000XM5: `e1 01 02` = DSEE
 * Extreme, `e7 01 01` = on.
 */
object SonyAudio {
    fun getCapability(type: Int): ByteArray = SonyMessages.get(T1.AUDIO_GET_CAPABILITY, type)
    fun getParam(type: Int): ByteArray = SonyMessages.get(T1.AUDIO_GET_PARAM, type)

    fun parseUpscalingType(p: ByteArray): Int? =
        if (p.u8(0) == T1.AUDIO_RET_CAPABILITY && p.u8(1) == AudioType.UPSCALING && p.size >= 3) p.u8(2) else null

    fun upscalingName(type: Int?): String = when (type) {
        0x00 -> "DSEE HX"
        0x02 -> "DSEE Extreme"
        0x03 -> "DSEE Ultimate"
        else -> "DSEE"
    }

    /** `(type, value)` of an AUDIO param reply or push. */
    fun parseParam(p: ByteArray): Pair<Int, Int>? {
        if (p.u8(0) != T1.AUDIO_RET_PARAM && p.u8(0) != T1.AUDIO_NTFY_PARAM) return null
        return if (p.size >= 3) p.u8(1) to p.u8(2) else null
    }

    fun setUpscaling(on: Boolean): ByteArray =
        byteArrayOf(T1.AUDIO_SET_PARAM.toByte(), AudioType.UPSCALING.toByte(), (if (on) 1 else 0).toByte())

    /** [mode] SOUND_QUALITY_PRIOR 0 / CONNECTION_QUALITY_PRIOR 1. */
    fun setConnectionMode(type: Int, mode: Int): ByteArray =
        byteArrayOf(T1.AUDIO_SET_PARAM.toByte(), type.toByte(), mode.toByte())

    fun connectionModeTypeFor(functions: Set<Int>): Int? = when {
        SonyFunction.CONNECTION_MODE_QUALITY in functions -> AudioType.CONNECTION_MODE
        SonyFunction.CONNECTION_MODE_QUALITY_WITH_LDAC in functions -> AudioType.CONNECTION_MODE_WITH_LDAC_STATUS
        else -> null
    }
}

/**
 * SYSTEM: pause when taken off (type 01) `[F7, 01, ON 0 | OFF 1]`, SET `[F8, 01, value]`;
 * speak-to-chat (type 0C) `[F7, 0C, value, previewValue]`, SET `[F8, 0C, value, OFF]` (the second
 * byte is "preview mode" in mos9527, not confirmed in the app; sent as off like mos9527 does).
 * WF-1000XM5: `f7 0c 01 01` = speak-to-chat off, `f7 01 00` = pause when taken off on.
 */
object SonySystem {
    fun getParam(type: Int): ByteArray = SonyMessages.get(T1.SYSTEM_GET_PARAM, type)

    /** `(type, on)` of a SYSTEM param reply or push for the two boolean types driven. */
    fun parseBoolean(p: ByteArray): Pair<Int, Boolean>? {
        if (p.u8(0) != T1.SYSTEM_RET_PARAM && p.u8(0) != T1.SYSTEM_NTFY_PARAM) return null
        val type = p.u8(1)
        if (type != SystemType.PLAYBACK_CONTROL_BY_WEARING && type != SystemType.SMART_TALKING_MODE_TYPE2) return null
        if (p.size < 3) return null
        return type to (SonyMessages.isOn(p.u8(2)) ?: return null)
    }

    fun setPlaybackByWearing(on: Boolean): ByteArray =
        byteArrayOf(T1.SYSTEM_SET_PARAM.toByte(), SystemType.PLAYBACK_CONTROL_BY_WEARING.toByte(), SonyMessages.onOffByte(on))

    fun setSpeakToChat(on: Boolean): ByteArray =
        byteArrayOf(T1.SYSTEM_SET_PARAM.toByte(), SystemType.SMART_TALKING_MODE_TYPE2.toByte(), SonyMessages.onOffByte(on), SonyMessages.onOffByte(false))
}

/** Table 2 voice guidance: GET `[46, type]` → `[47, type, ON 0 | OFF 1, …]`, SET `[48, type, value]`. */
object SonyVoiceGuidance {
    fun typeFor(functions: Set<Int>): Int? =
        SonyFunction.VOICE_GUIDANCE_TYPE_FOR_FUNCTION.entries.firstOrNull { it.key in functions }?.value

    fun getParam(type: Int): ByteArray = SonyMessages.get(T2.VOICE_GUIDANCE_GET_PARAM, type)

    fun parse(p: ByteArray): Boolean? =
        if (p.u8(0) == T2.VOICE_GUIDANCE_RET_PARAM && p.u8(1) in setOf(0x00, 0x01, 0x03) && p.size >= 3) SonyMessages.isOn(p.u8(2)) else null

    fun set(type: Int, on: Boolean): ByteArray =
        byteArrayOf(T2.VOICE_GUIDANCE_SET_PARAM.toByte(), type.toByte(), SonyMessages.onOffByte(on))
}

/**
 * Confirmation prompts. Earshot switches FIXED_MESSAGE alerts on (`[94, 00, ENABLE 0]`, as the app
 * and mos9527 do); the headset then asks before changes that disconnect or cost battery with
 * `[99, 00, messageType, actionType]`. A POSITIVE_NEGATIVE (1) prompt is answered `[98, 00,
 * messageType, NEGATIVE 0 | POSITIVE 1]`; CONFIRMATION_ONLY (0) needs no answer.
 */
data class SonyAlert(val messageType: Int, val actionType: Int) {
    val needsAnswer: Boolean get() = actionType == ACTION_POSITIVE_NEGATIVE

    fun answer(positive: Boolean): ByteArray =
        byteArrayOf(T1.ALERT_SET_PARAM.toByte(), FIXED_MESSAGE.toByte(), messageType.toByte(), (if (positive) 1 else 0).toByte())

    /** A readable line for the few prompts the driver's own settings can trigger. */
    val text: String
        get() = when (messageType) {
            0x06, 0x07 -> "The headphones reconnect to apply the multipoint change"
            0x08, 0x2B -> "This combination uses more battery"
            0x1C -> "Sound-quality priority limits the connection"
            0x70, 0x71, 0x72, 0x73 -> "Two connections with LDAC may limit sound quality"
            else -> "The headphones asked to confirm a change (message %02x)".format(messageType)
        }

    companion object {
        const val FIXED_MESSAGE = 0x00
        const val ACTION_POSITIVE_NEGATIVE = 0x01

        val ENABLE: ByteArray = byteArrayOf(T1.ALERT_SET_STATUS.toByte(), FIXED_MESSAGE.toByte(), 0x00)

        fun parse(p: ByteArray): SonyAlert? =
            if (p.u8(0) == T1.ALERT_NTFY_PARAM && p.u8(1) == FIXED_MESSAGE && p.size >= 4) SonyAlert(p.u8(2), p.u8(3)) else null
    }
}

internal fun ByteArray.u8(i: Int): Int = if (i in indices) this[i].toInt() and 0xFF else -1
internal fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.u32(i: Int): Int = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)

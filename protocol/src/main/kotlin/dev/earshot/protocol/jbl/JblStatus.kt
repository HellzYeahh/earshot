package dev.earshot.protocol.jbl

import dev.earshot.protocol.jbl.JblCommands as C
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A battery reading: [percent] null when the headset says it doesn't know (a bud in its case reads 0xFF). */
data class JblBattery(val percent: Int?, val charging: Boolean) {
    companion object {
        /** TLV battery `[charging, level]`; a level above 100 is unknown. */
        fun ofPair(charging: Int, level: Int) = JblBattery(level.takeIf { it <= 100 }, charging != 0)

        /** Device-info byte 22: bit 7 charging, bits 0–6 the level, above 100 unknown. */
        fun ofByte(b: Int) = JblBattery((b and 0x7F).takeIf { it <= 100 }, b and 0x80 != 0)
    }
}

/**
 * The `AA 12` device info a headset sends by itself right after the link opens (and in answer to
 * `AA 11 00`). Offsets count from the payload, three bytes after the `AA` (JBL notes §3, checked
 * against jbl-control's Wave Buds 2 capture): name 16 bytes at 0, product id little-endian at
 * 16–17, battery byte at 19, firmware at 26–28. The Bluetooth address at 20–25 is not kept.
 */
data class JblDeviceInfo(val name: String?, val productId: Int?, val battery: JblBattery?, val firmware: String?) {
    companion object {
        fun parse(p: JblFrame): JblDeviceInfo? {
            if (p.command != C.DEVICE_INFO || p.size < NAME_SIZE) return null
            val bytes = p.payload
            val name = String(bytes, 0, NAME_SIZE, Charsets.UTF_8).substringBefore('\u0000').trim().ifEmpty { null }
            val pid = if (p.size >= 18) p[16] or (p[17] shl 8) else null
            val battery = if (p.size >= 20) JblBattery.ofByte(p[19]) else null
            val firmware = if (p.size >= 29) "${p[26]}.${p[27]}.${p[28]}" else null
            return JblDeviceInfo(name, pid, battery, firmware)
        }

        private const val NAME_SIZE = 16
    }
}

/**
 * Everything a session has read from an H-AA headset, folded frame by frame ([fold]) in arrival
 * order: replies and pushes are the same frames on this protocol, so both go through one path.
 * Null = not reported (yet); a feature the headset never answered stays null and Earshot neither
 * shows nor writes it.
 */
data class JblStatus(
    val info: JblDeviceInfo? = null,
    /** The `0x91` mode numbers the headset listed ([C.WIRE_MODES]); null until it answered. */
    val modes: List<Int>? = null,
    /** The one mode that is on, or 0 for Off. */
    val activeMode: Int? = null,
    /** Manual noise-cancelling level (1..7), only when the headset answers the level read. */
    val ancLevel: Int? = null,
    /** EQ category ([C.EQ_PRESETS], [C.EQ_CUSTOM] or another); null until the EQ read answered. */
    val eqCategory: Int? = null,
    val voicePrompts: Boolean? = null,
    val firmware: String? = null,
    val left: JblBattery? = null,
    val right: JblBattery? = null,
    val case: JblBattery? = null,
    val headband: JblBattery? = null,
    /**
     * The two buds from the older `0x25` read, which names them primary and secondary, not left and
     * right: shown only as one figure (the lower).
     */
    val budPair: Pair<JblBattery, JblBattery>? = null,
    /** TLV ids the headset answered at least once. */
    val infoIds: Set<Int> = emptySet(),
    /** The last device ACK: the command it answers and its status (0 = OK). */
    val lastAck: Pair<Int, Int>? = null,
) {
    /** Earbuds when the headset reported a bud's battery; else null (the model table decides). */
    val reportsBuds: Boolean get() = C.FID_BATTERY_LEFT in infoIds || C.FID_BATTERY_RIGHT in infoIds

    fun fold(f: JblFrame): JblStatus = try {
        when (f.command) {
            C.DEVICE_INFO -> JblDeviceInfo.parse(f)?.let { i -> copy(info = i, firmware = firmware ?: i.firmware) } ?: this
            C.LISTENING -> foldListening(f)
            C.INFO -> foldTlv(f)
            C.EQ -> foldEq(f)
            C.VOICE_PROMPTS -> if (f.size >= 2 && f[0] == 0x05) copy(voicePrompts = f[1] != 0) else this
            C.BATTERY -> foldTwsBattery(f)
            C.ACK -> if (f.size >= 2) copy(lastAck = f[0] to f[1]) else this
            else -> this
        }
    } catch (_: IndexOutOfBoundsException) {
        this // a cut frame: keep what was known
    }

    private fun pairs(f: JblFrame): Map<Int, Int> =
        (1 until f.size - 1 step 2).associate { i -> f[i] to f[i + 1] }

    private fun foldListening(f: JblFrame): JblStatus = when (f.sub) {
        C.MODE_STATUS, C.MODE_SET -> {
            val flags = pairs(f).filterKeys { it in C.WIRE_MODES }
            if (flags.isEmpty()) this
            else copy(modes = flags.keys.sorted(), activeMode = C.WIRE_MODES.sorted().firstOrNull { flags[it] == 1 } ?: 0)
        }
        C.LEVEL_STATUS -> pairs(f)[C.LEVEL_MANUAL_ANC]?.let { copy(ancLevel = it) } ?: this
        else -> this
    }

    /** `[marker][fid u16 BE][len][value]…`; a cut last entry keeps the bytes that came. */
    private fun foldTlv(f: JblFrame): JblStatus {
        if (f.size < 1 || f[0] == 0x01) return this // our own GET echoed back carries no values
        var s = this
        var i = 1
        val ids = infoIds.toMutableSet()
        while (i + 3 <= f.size) {
            val fid = (f[i] shl 8) or f[i + 1]
            val len = f[i + 2]
            val end = minOf(i + 3 + len, f.size)
            val v = (i + 3 until end).map { f[it] }
            ids += fid
            s = s.withTlv(fid, v)
            i += 3 + len
        }
        return s.copy(infoIds = ids)
    }

    private fun withTlv(fid: Int, v: List<Int>): JblStatus = when (fid) {
        C.FID_FIRMWARE -> if (v.size >= 3) copy(firmware = "${v[0]}.${v[1]}.${v[2]}") else this
        C.FID_BATTERY_LEFT -> if (v.size >= 2) copy(left = JblBattery.ofPair(v[0], v[1])) else this
        C.FID_BATTERY_RIGHT -> if (v.size >= 2) copy(right = JblBattery.ofPair(v[0], v[1])) else this
        C.FID_BATTERY_CASE -> if (v.size >= 2) copy(case = JblBattery.ofPair(v[0], v[1])) else this
        C.FID_BATTERY_HEADBAND -> if (v.size >= 2) copy(headband = JblBattery.ofPair(v[0], v[1])) else this
        else -> this
    }

    /** The EQ return: category at 1; a Wave Buds 2 with EQ off answers category 0 and no bands. */
    private fun foldEq(f: JblFrame): JblStatus {
        if (f.size < C.EQ_HEADER_SIZE || f[0] == C.EQ_GET) return this
        return copy(eqCategory = f[1])
    }

    /** `AA 25 len 01 slaveChg masterChg slave% master% caseChg case% …` (older TWS models). */
    private fun foldTwsBattery(f: JblFrame): JblStatus {
        if (f.size < 7 || f[0] != 0x01) return this
        return copy(budPair = JblBattery.ofPair(f[1], f[3]) to JblBattery.ofPair(f[2], f[4]), case = JblBattery.ofPair(f[5], f[6]))
    }

    companion object {
        /** The ten band gains of an EQ return, in dB (for tests and the Lab). */
        fun eqGains(f: JblFrame): List<Float> {
            if (f.command != C.EQ || f.size < C.EQ_HEADER_SIZE) return emptyList()
            val bytes = f.payload
            val count = f[15]
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until count).mapNotNull { band ->
                val at = C.EQ_HEADER_SIZE + band * C.EQ_BAND_SIZE + 1
                if (at + 4 > bytes.size) null else buffer.getFloat(at)
            }
        }
    }
}

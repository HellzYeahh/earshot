package dev.earshot.protocol.jbl

import dev.earshot.protocol.jbl.JblCommands as C
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The one gate every H-AA frame passes before it leaves the phone: an allow-list of exact shapes,
 * refuse-by-default. A command id alone is never enough; the payload must be one Earshot builds in
 * [JblCommands] (sub-command, value ranges, the EQ layout byte by byte).
 *
 * [NEVER] wins over everything: factory reset (`95`, jbl-control blocks it too), power off (`97`),
 * firmware transfer (`F0`), the charging case's OTA launch and screensaver delete (`A4`, `A3`),
 * clearing analytics (`15`), factory ANC tuning (`74`), and the two commands whose purpose was not
 * traced (`61` "sync app status", `B0` LE Audio / Auracast). JBL notes §4.
 */
object JblWriteGuard {
    val NEVER: Set<Int> = setOf(0x95, 0x97, 0xF0, 0xA4, 0xA3, 0x15, 0x74, 0x61, 0xB0)

    fun isAllowed(f: JblFrame): Boolean = f.command !in NEVER && (isRead(f) || isWrite(f))

    /** Frames that only read (the Lab sends nothing else) and the two link chores: pushes on, heartbeat. */
    fun isRead(f: JblFrame): Boolean {
        if (f.command in NEVER) return false
        val p = f.payload.map { it.toInt() and 0xFF }
        return when (f.command) {
            C.NOTIFY_ON -> p == listOf(0x01, 0x01)
            C.DEVICE_INFO_GET -> p.isEmpty() && !f.longLength
            C.LISTENING -> p == listOf(C.MODE_GET) || p == listOf(C.LEVEL_GET)
            C.INFO -> f.longLength && isTlvGet(p)
            C.EQ -> !f.longLength && p == listOf(C.EQ_GET, C.EQ_CURRENT)
            C.VOICE_PROMPTS -> p == listOf(0x04)
            C.BATTERY -> p == listOf(0x00)
            C.SERIAL, C.VOICE_AWARE, C.FIND, C.SAV -> p == listOf(0x01)
            C.HEARTBEAT -> p == listOf(0x01, 0x00, 0x01, 0x00)
            else -> false
        }
    }

    /** The settings Earshot changes: mode, manual noise-cancelling level, EQ preset, voice prompts. */
    fun isWrite(f: JblFrame): Boolean {
        if (f.command in NEVER) return false
        val p = f.payload.map { it.toInt() and 0xFF }
        return when (f.command) {
            C.LISTENING -> isModeSet(p) || isLevelSet(p)
            C.EQ -> f.longLength && isEqSet(f.payload)
            C.VOICE_PROMPTS -> p.size == 2 && p[0] == 0x03 && p[1] in 0..1
            else -> false
        }
    }

    private fun isTlvGet(p: List<Int>): Boolean {
        if (p.size < 3 || p[0] != 0x01 || (p.size - 1) % 2 != 0) return false
        val ids = (1 until p.size step 2).map { (p[it] shl 8) or p[it + 1] }
        return ids.all { it in C.INFO_READ_FIDS } && ids.distinct().size == ids.size
    }

    /** `10` then `[mode, on]` pairs: known modes, each once, on is 0 or 1, at most one on. */
    private fun isModeSet(p: List<Int>): Boolean {
        if (p.size < 3 || p[0] != C.MODE_SET || (p.size - 1) % 2 != 0) return false
        val pairs = (1 until p.size step 2).map { p[it] to p[it + 1] }
        return pairs.all { (mode, on) -> mode in C.WIRE_MODES && on in 0..1 } &&
            pairs.map { it.first }.distinct().size == pairs.size &&
            pairs.count { it.second == 1 } <= 1
    }

    private fun isLevelSet(p: List<Int>): Boolean = p.size == 3 && p[0] == C.LEVEL_SET && p[1] == C.LEVEL_MANUAL_ANC && p[2] in C.ANC_LEVELS

    /** Exactly what [JblCommands.eqSet] builds: a known category, the app's constants, the ten bands in order, gains within ±6 dB. */
    private fun isEqSet(bytes: ByteArray): Boolean {
        if (bytes.size != C.EQ_SET_SIZE) return false
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.get(0).toInt() != C.EQ_SET) return false
        if (C.eqPreset(b.get(1).toInt() and 0xFF) == null) return false
        if (b.getFloat(2) != C.EQ_CALIBRATION || b.get(6).toInt() != C.EQ_SAMPLE_RATE_KHZ) return false
        if (b.getFloat(7) != 0f || b.getFloat(11) != 0f || b.get(15).toInt() != C.EQ_BAND_HZ.size) return false
        return C.EQ_BAND_HZ.withIndex().all { (i, hz) ->
            val at = C.EQ_HEADER_SIZE + i * C.EQ_BAND_SIZE
            val gain = b.getFloat(at + 1)
            b.get(at).toInt() == C.EQ_BAND_PEAK && !gain.isNaN() && gain in -C.EQ_MAX_DB..C.EQ_MAX_DB &&
                b.getFloat(at + 5) == hz.toFloat() && b.get(at + 9).toInt() == C.EQ_Q_TIMES_TEN
        }
    }
}

package dev.earshot.protocol.jbl

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * H-AA command ids and the frames Earshot sends. Every frame here is one JBL's app sends (JBL notes
 * §3) and jbl-control (MIT) sent to a Wave Buds 2; the golden bytes are in JblWireTest. Anything
 * else is refused by [JblWriteGuard], and its never list wins over this file.
 */
object JblCommands {
    /** Device ACK: `[command, status]`, status 0 = OK. */
    const val ACK = 0x00

    /** Device-info TLV ("Protocol 3"): marker 00 set / 01 get / 02 return, then `[fid u16 BE][len][value]`. */
    const val INFO = 0x06
    const val DEVICE_INFO_GET = 0x11
    const val DEVICE_INFO = 0x12
    const val FIND = 0x23
    const val BATTERY = 0x25
    const val SAV = 0x80
    const val LISTENING = 0x91
    const val VOICE_PROMPTS = 0x93
    const val SERIAL = 0x94
    const val VOICE_AWARE = 0x98
    const val NOTIFY_ON = 0x9B
    const val EQ = 0xA2
    const val HEARTBEAT = 0xB1

    /** Commands the headset sends with a two-byte length (the app's long form; jbl-control saw 06 and A2). */
    val LONG_IN: Set<Int> = setOf(INFO, EQ, 0xA9)

    /** Commands the phone always sends with a two-byte length; the EQ write picks it per frame. */
    val LONG_OUT: Set<Int> = setOf(INFO)

    // ---- 0x91: listening modes and their levels -------------------------------------------------

    const val MODE_SET = 0x10
    const val MODE_GET = 0x11
    const val MODE_STATUS = 0x12
    const val LEVEL_SET = 0x20
    const val LEVEL_GET = 0x21
    const val LEVEL_STATUS = 0x22

    /** Level type of the manual noise-cancelling level (jbl-control clamps it to 1..7 on a Wave Buds 2). */
    const val LEVEL_MANUAL_ANC = 0x04
    val ANC_LEVELS = 1..7

    /** The mode numbers in the `[mode, on]` pairs of `0x91 10`/`12`; all pairs off is Off. */
    const val WIRE_ANC = 1
    const val WIRE_AMBIENT = 2
    const val WIRE_TALKTHRU = 3
    val WIRE_MODES = setOf(WIRE_ANC, WIRE_AMBIENT, WIRE_TALKTHRU)

    // ---- 0x06 TLV feature ids ------------------------------------------------------------------

    const val FID_NAME = 0x01
    const val FID_PRODUCT_ID = 0x02
    const val FID_FIRMWARE = 0x05
    const val FID_TWS = 0x06
    const val FID_AUTO_OFF = 0x0B
    const val FID_SPATIAL = 0x0C
    const val FID_SIDETONE = 0x0D
    const val FID_CALL = 0x1F
    const val FID_BATTERY_HEADBAND = 0x19
    const val FID_BATTERY_LEFT = 0x1A
    const val FID_BATTERY_RIGHT = 0x1B
    const val FID_BATTERY_CASE = 0x1C
    const val FID_SAFE_SOUND = 0x20

    /** jbl-control's probe list (the ids a Wave Buds 2 answers), plus the headband battery from the notes. */
    val INFO_READ_FIDS: List<Int> = listOf(
        FID_NAME, FID_FIRMWARE, FID_TWS, FID_AUTO_OFF, FID_SPATIAL, FID_SIDETONE,
        FID_BATTERY_LEFT, FID_BATTERY_RIGHT, FID_BATTERY_CASE, FID_SAFE_SOUND, FID_BATTERY_HEADBAND,
    )

    val BATTERY_FIDS: List<Int> = listOf(FID_BATTERY_LEFT, FID_BATTERY_RIGHT, FID_BATTERY_CASE, FID_BATTERY_HEADBAND)

    // ---- 0xA2: EQ --------------------------------------------------------------------------------

    const val EQ_OFF = 0
    const val EQ_CUSTOM = 230
    const val EQ_CURRENT = 0xFF
    const val EQ_SET = 0x03
    const val EQ_GET = 0x01

    /** The ten bands of every H-AA EQ. */
    val EQ_BAND_HZ: List<Int> = listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** jbl-control clamps to ±6 dB, the gain range most models list (`eqGainRange`). */
    const val EQ_MAX_DB = 6.0f

    /** One of JBL's preset curves: its category id and the gains of [EQ_BAND_HZ], as JBL's app sends them. */
    class EqPreset(val category: Int, val label: String, val gainsDb: List<Float>)

    /** The app's presets (gains via jbl-control); Off is category 0 with flat bands. */
    val EQ_PRESETS: List<EqPreset> = listOf(
        EqPreset(EQ_OFF, "Off", List(10) { 0f }),
        EqPreset(1, "Jazz", listOf(4.0f, 3.0f, 1.0f, 2.5f, -1.5f, -1.5f, 0.0f, 1.0f, 3.0f, 4.0f)),
        EqPreset(2, "Vocal", listOf(-1.5f, -3.0f, -3.0f, 1.0f, 3.5f, 3.5f, 3.0f, 1.5f, 0.0f, -1.5f)),
        EqPreset(3, "Bass", listOf(4.0f, 4.0f, 3.0f, 2.5f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)),
        EqPreset(6, "Club", listOf(2.0f, 1.5f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 2.0f)),
        EqPreset(7, "Studio", listOf(-2.0f, -1.0f, -1.0f, -0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)),
        EqPreset(9, "Extreme Bass", listOf(4.0f, 4.5f, 4.5f, 3.5f, -1.0f, 0.0f, 0.0f, 2.0f, 2.0f, 0.0f)),
    )

    fun eqPreset(category: Int): EqPreset? = EQ_PRESETS.firstOrNull { it.category == category }

    /** jbl-control sends calibration −1.0 and 48 kHz, as the app does. */
    const val EQ_CALIBRATION = -1.0f
    const val EQ_SAMPLE_RATE_KHZ = 48

    /** Band type 1 = peak; Q is sent ×10 (10 = 1.0). */
    const val EQ_BAND_PEAK = 1
    const val EQ_Q_TIMES_TEN = 10

    // ---- Frames --------------------------------------------------------------------------------

    private fun frame(command: Int, vararg bytes: Int, long: Boolean? = null) =
        JblFrame(command, ByteArray(bytes.size) { bytes[it].toByte() }, longLength = long ?: (command in LONG_OUT))

    /** Switches pushes on (`AA 9B 02 01 01`): the app's "device multi status", jbl-control's notify_on. */
    fun notifyOn() = frame(NOTIFY_ON, 0x01, 0x01)

    fun deviceInfoGet() = frame(DEVICE_INFO_GET)

    fun modeGet() = frame(LISTENING, MODE_GET)

    /**
     * Switch to [active] (a [WIRE_MODES] number, or null for Off) as `0x91 10` pairs, one for each
     * mode the headset reported ([reported]), at most one of them on. The app and jbl-control send
     * all three pairs; a headset that reports fewer gets only its own.
     */
    fun modeSet(reported: List<Int>, active: Int?): JblFrame {
        require(reported.isNotEmpty() && reported.all { it in WIRE_MODES } && reported.distinct().size == reported.size) { "bad mode list $reported" }
        require(active == null || active in reported) { "mode $active is not one of $reported" }
        val pairs = reported.sorted().flatMap { listOf(it, if (it == active) 1 else 0) }
        return frame(LISTENING, MODE_SET, *pairs.toIntArray())
    }

    fun levelGet() = frame(LISTENING, LEVEL_GET)

    fun ancLevelSet(level: Int): JblFrame {
        require(level in ANC_LEVELS) { "noise-cancelling level $level is outside $ANC_LEVELS" }
        return frame(LISTENING, LEVEL_SET, LEVEL_MANUAL_ANC, level)
    }

    /** A TLV GET of [fids] (`AA 06 len16 01 [fid u16 BE]…`). */
    fun infoGet(fids: List<Int> = INFO_READ_FIDS): JblFrame {
        require(fids.isNotEmpty() && fids.all { it in 0..0xFFFF })
        val bytes = listOf(0x01) + fids.flatMap { listOf(it shr 8, it and 0xFF) }
        return frame(INFO, *bytes.toIntArray(), long = true)
    }

    fun eqGet() = frame(EQ, EQ_GET, EQ_CURRENT, long = false)

    /**
     * The whole EQ (`AA A2 len16 03 cat cal f32 sr L f32 R f32 n [type gain f32 freq f32 Q]×n`), every
     * float little-endian. Gains are clamped to ±[EQ_MAX_DB].
     */
    fun eqSet(category: Int, gainsDb: List<Float>): JblFrame {
        require(category in 0..0xFF)
        require(gainsDb.size == EQ_BAND_HZ.size) { "expected ${EQ_BAND_HZ.size} gains" }
        val buffer = ByteBuffer.allocate(EQ_SET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(EQ_SET.toByte()).put(category.toByte())
        buffer.putFloat(EQ_CALIBRATION).put(EQ_SAMPLE_RATE_KHZ.toByte())
        buffer.putFloat(0f).putFloat(0f)
        buffer.put(EQ_BAND_HZ.size.toByte())
        for ((gain, hz) in gainsDb.zip(EQ_BAND_HZ)) {
            buffer.put(EQ_BAND_PEAK.toByte()).putFloat(gain.coerceIn(-EQ_MAX_DB, EQ_MAX_DB)).putFloat(hz.toFloat()).put(EQ_Q_TIMES_TEN.toByte())
        }
        return JblFrame(EQ, buffer.array(), longLength = true)
    }

    /** 2 + 4 + 1 + 8 + 1 header bytes, then 10 bytes per band. */
    const val EQ_HEADER_SIZE = 16
    const val EQ_BAND_SIZE = 10
    val EQ_SET_SIZE: Int = EQ_HEADER_SIZE + EQ_BAND_SIZE * EQ_BAND_HZ.size

    fun voicePromptsGet() = frame(VOICE_PROMPTS, 0x04)

    fun voicePromptsSet(on: Boolean) = frame(VOICE_PROMPTS, 0x03, if (on) 1 else 0)

    /** Battery of TWS models without the TLV ids (`AA 25 01 00`). */
    fun batteryGet() = frame(BATTERY, 0x00)

    /** The app's heartbeat answer, sent only after device info arrived (JBL notes §2). */
    fun heartbeatEcho() = frame(HEARTBEAT, 0x01, 0x00, 0x01, 0x00)

    // Reads only the Lab sends (jbl-control's probes on a Wave Buds 2).
    fun serialGet() = frame(SERIAL, 0x01)
    fun voiceAwareGet() = frame(VOICE_AWARE, 0x01)
    fun findGet() = frame(FIND, 0x01)
    fun savGet() = frame(SAV, 0x01)

    fun name(command: Int): String = when (command) {
        ACK -> "ACK"
        INFO -> "Device info (TLV)"
        DEVICE_INFO_GET, DEVICE_INFO -> "Device info"
        FIND -> "Find my buds"
        BATTERY -> "Battery"
        SAV -> "Smart Audio & Video"
        LISTENING -> "Listening mode"
        VOICE_PROMPTS -> "Voice prompts"
        SERIAL -> "Serial number"
        VOICE_AWARE -> "VoiceAware"
        NOTIFY_ON -> "Notifications on"
        EQ -> "EQ"
        HEARTBEAT -> "Heartbeat"
        else -> "AA %02X".format(command)
    }
}

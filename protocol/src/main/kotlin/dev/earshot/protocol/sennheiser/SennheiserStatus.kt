package dev.earshot.protocol.sennheiser

import dev.earshot.protocol.gaia.GaiaCommand
import kotlin.math.roundToInt

/** `0x1000` EQ layout (generation 2): band count and gain range in tenths of a dB. */
data class SennheiserEqConfig(val bands: Int, val minTenths: Int, val maxTenths: Int, val presets: Int = 0, val userPresets: Int = 0) {
    /** The range in whole dB (Earshot's EQ sliders step in dB): ±6 on the Momentum 4 and 5. */
    val rangeDb: IntRange get() = ceilDiv(minTenths) .. (maxTenths / 10)

    private fun ceilDiv(tenths: Int): Int = if (tenths < 0) -((-tenths) / 10) else (tenths + 9) / 10

    companion object {
        fun parse(payload: ByteArray): SennheiserEqConfig? {
            if (payload.size < 3) return null
            val bands = payload[0].toInt() and 0xFF
            val min = payload[1].toInt()
            val max = payload[2].toInt()
            // A sane layout or nothing: a garbage reply must not size the sliders.
            if (bands !in 1..16 || min >= 0 || max <= 0) return null
            return SennheiserEqConfig(bands, min, max, payload.getOrNull(3)?.toInt()?.and(0xFF) ?: 0, payload.getOrNull(4)?.toInt()?.and(0xFF) ?: 0)
        }
    }
}

/**
 * What Earshot knows about a Sennheiser headset, in decoded units (percent, seconds, 0..100 level,
 * tenths of a dB): every reply and push goes through [apply]. Null = not read or not reported.
 * The app maps this onto `HeadphoneState`; the few wire-to-human conversions it needs live in
 * [SennheiserWire] so they stay in this module.
 */
data class SennheiserStatus(
    /** The Sennheiser (0x0495) features and their versions, from `0x0001`; null until read (or refused). */
    val features: Map<Int, Int>? = null,
    /** Qualcomm Core's feature list (0x001D), for logs: none of it is driven. */
    val qualcommFeatures: Map<Int, Int>? = null,
    val modelId: String? = null,
    val firmware: String? = null,
    /** `[p]`, `[L, R]` or `[L, R, case]`; null entries are 0xFF (unknown). */
    val battery: List<Int?>? = null,
    /** 0 not charging, 1 charging, 2 complete; one per part. */
    val charging: List<Int>? = null,
    val ancOn: Boolean? = null,
    val transparencyOn: Boolean? = null,
    /** `0x1A03` 0..100, 0 = full ANC, 100 = full transparency. */
    val ancLevel: Int? = null,
    /** Earbuds `0x1803`. */
    val transparencyLevel: Int? = null,
    val pauseInTransparency: Boolean? = null,
    /** Mode table: [SennheiserCommands.MODE_ANTI_WIND] etc. → state. */
    val ancModes: Map<Int, Int> = emptyMap(),
    val onHeadDetection: Boolean? = null,
    val smartPause: Boolean? = null,
    val autoAnswer: Boolean? = null,
    val comfortCall: Boolean? = null,
    val autoOffSeconds: Int? = null,
    val sidetone: Int? = null,
    val eqConfig: SennheiserEqConfig? = null,
    /** Every band's gain in tenths of a dB. */
    val eqGains: List<Int>? = null,
    val bassBoost: Boolean? = null,
    /** Per part: 0 unknown, 1 in case, 2 not on head, 3 on head. */
    val wear: List<Int>? = null,
) {
    /** By the model string once read, else null (the caller falls back on the Bluetooth name). */
    val model: SennheiserModel? get() = SennheiserModel.fromModelId(modelId)

    fun has(feature: Int): Boolean = features?.containsKey(feature) == true

    /** The generation-2 EQ: feature 8 at version ≥ 6. Gen-1 reuses its ids for other commands, so it is left alone. */
    val eqGen2: Boolean get() = (features?.get(SennheiserFeature.USER_EQ) ?: 0) >= SennheiserFeature.EQ_GEN2_MIN_VERSION

    /**
     * Fold one response or notification into the status. A SET's empty response changes nothing;
     * an error frame is the caller's business. Unknown ids are ignored.
     */
    fun apply(command: Int, payload: ByteArray): SennheiserStatus {
        if (GaiaCommand.isError(command) || GaiaCommand.type(command) == GaiaCommand.TYPE_COMMAND) return this
        val push = GaiaCommand.isNotification(command)
        val b = payload
        return when (GaiaCommand.request(command)) {
            SennheiserCommands.BATTERY_GET -> if (b.isEmpty()) this else copy(battery = SennheiserWire.battery(b))
            SennheiserCommands.CHARGING_GET -> if (b.isEmpty()) this else copy(charging = b.map { it.toInt() and 0xFF })
            SennheiserCommands.WEAR_STATE_GET -> if (b.isEmpty()) this else copy(wear = b.map { it.toInt() and 0xFF })
            SennheiserCommands.ANC_GET -> SennheiserWire.flag(b)?.let { copy(ancOn = it) } ?: this
            SennheiserCommands.TH_GET -> SennheiserWire.flag(b)?.let { copy(transparencyOn = it) } ?: this
            SennheiserCommands.ANC_LEVEL_GET -> SennheiserWire.percent(b)?.let { copy(ancLevel = it) } ?: this
            SennheiserCommands.TH_LEVEL_GET -> SennheiserWire.percent(b)?.let { copy(transparencyLevel = it) } ?: this
            SennheiserCommands.TH_BEHAVIOUR_GET -> SennheiserWire.flag(b)?.let { copy(pauseInTransparency = it) } ?: this
            SennheiserCommands.ANC_MODE_GET -> if (b.size < 2) this else copy(ancModes = ancModes + SennheiserWire.ancModes(b))
            SennheiserCommands.ON_HEAD_GET -> SennheiserWire.flag(b)?.let { copy(onHeadDetection = it) } ?: this
            SennheiserCommands.SMART_PAUSE_GET -> SennheiserWire.flag(b)?.let { copy(smartPause = it) } ?: this
            SennheiserCommands.AUTO_ANSWER_GET -> SennheiserWire.flag(b)?.let { copy(autoAnswer = it) } ?: this
            SennheiserCommands.COMFORT_CALL_GET -> SennheiserWire.flag(b)?.let { copy(comfortCall = it) } ?: this
            SennheiserCommands.AUTO_OFF_GET -> SennheiserWire.autoOffSeconds(b)?.let { copy(autoOffSeconds = it) } ?: this
            SennheiserCommands.SIDETONE_GET -> if (b.isEmpty()) this else copy(sidetone = b[0].toInt() and 0xFF)
            SennheiserCommands.FIRMWARE_GET -> SennheiserWire.firmware(b)?.let { copy(firmware = it) } ?: this
            SennheiserCommands.MODEL_ID_GET -> SennheiserWire.modelId(b)?.let { copy(modelId = it) } ?: this
            // EQ ids mean something else on generation-1 firmware: only read them on generation 2.
            SennheiserCommands.EQ_CONFIG_GET -> if (!eqGen2) this else SennheiserEqConfig.parse(b)?.let { copy(eqConfig = it) } ?: this
            SennheiserCommands.EQ_ALL_GET -> if (!eqGen2 || b.isEmpty()) this else copy(eqGains = SennheiserWire.gains(b))
            // The push for 0x1002 carries every gain; its GET reply (one band) is not used.
            SennheiserCommands.EQ_BAND_GET -> if (!eqGen2 || !push || b.isEmpty()) this else copy(eqGains = SennheiserWire.gains(b))
            SennheiserCommands.EQ_SUBMODE_GET -> if (!eqGen2 || b.isEmpty()) this else copy(bassBoost = (b.last().toInt() and 0x01) != 0)
            else -> this
        }
    }
}

/** Wire ↔ human conversions for Sennheiser payloads. Pure; golden-tested against captures. */
object SennheiserWire {
    fun flag(b: ByteArray): Boolean? = if (b.isEmpty()) null else b[0].toInt() != 0

    fun percent(b: ByteArray): Int? = if (b.isEmpty()) null else (b[0].toInt() and 0xFF).takeIf { it <= 100 }

    /** 0..100 per part, 0xFF (or anything above 100) = unknown. */
    fun battery(b: ByteArray): List<Int?> = b.map { (it.toInt() and 0xFF).takeIf { p -> p <= 100 } }

    /** Mode-table pairs `[mode, state]…`, in any order (the Momentum 5 sends `01 00 03 00 02 00`). */
    fun ancModes(b: ByteArray): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        var i = 0
        while (i + 1 < b.size) {
            out[b[i].toInt() and 0xFF] = b[i + 1].toInt() and 0xFF
            i += 2
        }
        return out
    }

    /** One mode-table pair; the Momentum 4 accepts a single pair as well as the whole table. */
    fun ancModePair(mode: Int, state: Int): ByteArray = byteArrayOf(mode.toByte(), state.toByte())

    /** `[major u16, minor u16, patch u16]` → "3.37.3". */
    fun firmware(b: ByteArray): String? {
        if (b.size < 6) return null
        return (0 until 3).joinToString(".") { i -> u16(b, i * 2).toString() }
    }

    /** NUL-terminated UTF-8 model string. */
    fun modelId(b: ByteArray): String? {
        val end = b.indexOf(0).let { if (it < 0) b.size else it }
        return String(b, 0, end, Charsets.UTF_8).trim().ifEmpty { null }
    }

    /** `[timer, seconds u16]` → seconds. */
    fun autoOffSeconds(b: ByteArray): Int? = if (b.size < 3) null else u16(b, 1)

    /** Auto power off for the power-off timer (timer 0). */
    fun autoOffSet(seconds: Int): ByteArray = byteArrayOf(0, (seconds shr 8).toByte(), seconds.toByte())

    /** The power-off timer is timer 0: `0x0601 [00]`. */
    val AUTO_OFF_TIMER: ByteArray get() = byteArrayOf(0)

    /** The choices Smart Control offers, in seconds (0 = never). */
    val AUTO_OFF_CHOICES: List<Int> = listOf(0, 900, 1800, 3600)

    fun gains(b: ByteArray): List<Int> = b.map { it.toInt() }

    fun eqBandSet(band: Int, tenths: Int): ByteArray = byteArrayOf(band.toByte(), tenths.toByte())

    fun dbToTenths(db: Int): Int = db * 10

    fun tenthsToDb(tenths: Int): Int = (tenths / 10.0).roundToInt()

    /**
     * Earshot's noise slider (0..10, 10 = most cancelling) ↔ the ANC↔transparency level
     * (0..100, 0 = full ANC): level = (10 − n) × 10.
     */
    fun levelFromCancelling(cancelling: Int): Int = (10 - cancelling.coerceIn(0, 10)) * 10

    fun cancellingFromLevel(level: Int): Int = 10 - (level.coerceIn(0, 100) / 10.0).roundToInt()

    /** Earbud transparency presets the app offers: low / medium / high. */
    val TRANSPARENCY_PRESETS: List<Int> = listOf(1, 50, 100)

    /** The preset index nearest to [level]. */
    fun transparencyPreset(level: Int): Int = TRANSPARENCY_PRESETS.indices.minBy { kotlin.math.abs(TRANSPARENCY_PRESETS[it] - level) }

    private fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
}

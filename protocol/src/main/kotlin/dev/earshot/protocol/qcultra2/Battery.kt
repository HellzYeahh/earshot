package dev.earshot.protocol.qcultra2

/**
 * One `[2.2]` record. [percent] is null when the wire value is 0xFF (unknown / not present);
 * [remainingMinutes] is bytes 1-2 big-endian, null for `ffff` (what the headphones always send).
 */
data class BatteryReading(val componentCode: Int, val percent: Int?, val remainingMinutes: Int? = null) {
    val component: BatteryComponent? get() = BatteryComponent.fromCode(componentCode)
}

/**
 * `[2.2]` BatteryLevel (Bose app: `[percent][remaining-minutes u16 BE, 0xFFFF = unknown][componentId]`,
 * more than 4 bytes = repeated records).
 *
 * Headphones (fw 10.12 `5affff00` = 90 %): one record, component 0. Percent 0xFF means unknown.
 *
 * Earbuds ("edith", fixture `3cffff01 3cffff02 3cffff04 50ffff03`): several records, identified
 * by id, never by position: 1 = right, 2 = left, 3 = case, 4 = combined buds.
 */
object Battery {
    const val UNKNOWN = 0xFF

    /** Headline percentage: byte 0 of the payload, or null when empty / 0xFF. */
    fun parsePercent(payload: ByteArray): Int? {
        if (payload.isEmpty()) return null
        val v = payload.u8(0)
        return if (v == UNKNOWN || v > 100) null else v
    }

    /** All 4-byte records; a trailing partial record is ignored. */
    fun parseReadings(payload: ByteArray): List<BatteryReading> {
        val out = ArrayList<BatteryReading>(payload.size / 4)
        var i = 0
        while (i + 3 < payload.size) {
            val level = payload.u8(i)
            val minutes = payload.u16be(i + 1).takeIf { it != 0xFFFF }
            out.add(BatteryReading(payload.u8(i + 3), if (level == UNKNOWN || level > 100) null else level, minutes))
            i += 4
        }
        return out
    }

    /**
     * The figure to show as "battery": the combined-buds record (id 4) when present, else the
     * lower of right/left (bosectl's fallback), else the single headphones record (id 0),
     * else the first record that has a value.
     */
    fun aggregate(readings: List<BatteryReading>): Int? {
        readings.firstOrNull { it.componentCode == BatteryComponent.COMBINED_BUDS.code && it.percent != null }
            ?.let { return it.percent }
        readings.filter { it.componentCode == BatteryComponent.RIGHT.code || it.componentCode == BatteryComponent.LEFT.code }
            .mapNotNull { it.percent }.minOrNull()?.let { return it }
        readings.firstOrNull { it.componentCode == BatteryComponent.SINGLE.code && it.percent != null }
            ?.let { return it.percent }
        return readings.firstNotNullOfOrNull { it.percent }
    }

    /** Convenience: readings + aggregate in one go. */
    fun parse(payload: ByteArray): BatteryStatus {
        val readings = parseReadings(payload)
        return BatteryStatus(aggregate(readings) ?: parsePercent(payload), readings)
    }
}

data class BatteryStatus(val percent: Int?, val readings: List<BatteryReading>)

/**
 * `[2.3]` per Quiet Rebellion "charging state"; the Bose app calls `[2.3]` **AuxCableDetection**.
 * Wolverine fw 10.12 answers FuncNotSupp. Charging is `[2.5]` ([ChargerDetect]).
 */
@Deprecated("[2.3] is AuxCableDetection; charging is [2.5]", ReplaceWith("ChargerDetect"))
object ChargingState {
    fun parse(payload: ByteArray): Boolean? = if (payload.isEmpty()) null else payload[0] != 0.toByte()
}

// ── Other Status [2.x] functions ─────────────────────────────────────────────────────────

/** `[2.5]` ChargerDetect: `[1]` = charger connected. fw 10.12 reads `00`. Pushed once `[9.2]` covers block 2. */
object ChargerDetect {
    fun parse(payload: ByteArray): Boolean? = Toggle.parse(payload)
}

/**
 * `[2.21]` MicMute (Bose `StatusMicMuteEnabledResponse`): `[1]` = true. fw 10.12 reads `01`;
 * whether that means "muted right now" or "the mute feature is on" is not settled (the app has a
 * GET for it and no setter, so Earshot exposes it read-only).
 */
object MicMute {
    fun parse(payload: ByteArray): Boolean? = Toggle.parse(payload)
}

/** `[2.16]` OperationMode: 0 Unknown, 1 Normal, 2 OtaOnly; fw 10.12 reads `01`. */
object OperationMode {
    fun parse(payload: ByteArray): OperationModeCode? = if (payload.isEmpty()) null else OperationModeCode.fromCode(payload.u8(0))
}

/**
 * `[2.12]` Button: the button event the headphones push (once `[9.2]` covers block 2), same
 * layout as `[1.9]`: `[buttonId][eventType][actionMode]...`. Never seen on hardware yet; the
 * parse reuses [Buttons] and only the first three bytes matter.
 */
object ButtonEventPush {
    fun parse(payload: ByteArray): ButtonConfig? = Buttons.parse(payload)
}

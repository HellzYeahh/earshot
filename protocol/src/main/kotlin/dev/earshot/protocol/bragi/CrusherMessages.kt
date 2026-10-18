package dev.earshot.protocol.bragi

/**
 * The BOS-RPC request builders and response parsers for the Crusher's user-facing features, each a
 * thin wrapper over [ProtoWriter] / [ProtoReader] with the field numbers from the protobuf
 * definitions the Skullcandy app ships (see [BosRpcPacket]). Human-unit conversions stay in the app
 * layer; these are the wire structs only.
 */

// ── hearing (service 4): ANC / transparency ──────────────────────────────────────────────

/** `bos.hearing.Mode`. OFF=1, ANC=2 (QuietControl/"Quiet"), AT=3 (audio transparency/"Aware"). */
enum class HearingMode(val wire: Int) {
    UNSPECIFIED(0), OFF(1), ANC(2), AT(3);
    companion object { fun fromWire(v: Int) = entries.firstOrNull { it.wire == v } ?: UNSPECIFIED }
}

object Hearing {
    /**
     * `ModeConfigure {1:mode, 2:activate, 3:preset?, 4:gain?}`. To switch off ANC/AT send
     * `{mode:OFF, activate:true}`. [preset]/[gain] are omitted when null (leave unchanged); they
     * are invalid for OFF.
     */
    fun modeConfigure(mode: HearingMode, activate: Boolean, preset: Int? = null, gain: Int? = null): ByteArray =
        ProtoWriter.build {
            enum(1, mode.wire, writeDefault = mode != HearingMode.UNSPECIFIED)
            bool(2, activate)
            if (preset != null) uint32(3, preset, writeDefault = true)
            if (gain != null) uint32(4, gain, writeDefault = true)
        }

    /** `ModeReadRequest {1:subscribe}`. */
    fun modeRead(subscribe: Boolean): ByteArray = ProtoWriter.build { bool(1, subscribe) }

    /** `ModeState {1:active(enum), 2:anc{1:preset,2:gain}, 3:at{1:preset,2:gain}}`. */
    data class ModeState(val active: HearingMode, val ancPreset: Int, val ancGain: Int, val atPreset: Int, val atGain: Int)

    fun parseModeState(body: ByteArray): ModeState {
        val f = ProtoReader.map(body)
        fun item(field: Int): Pair<Int, Int> {
            val m = f[field]?.bytes?.let { ProtoReader.map(it) } ?: return 0 to 0
            return (m[1]?.int ?: 0) to (m[2]?.int ?: 0)
        }
        val (ap, ag) = item(2); val (tp, tg) = item(3)
        return ModeState(HearingMode.fromWire(f[1]?.int ?: 0), ap, ag, tp, tg)
    }

    /** `ModeInfo {1:anc{1:preset_count,2:gain_max}, 2:at{...}}`. */
    data class ModeInfo(val ancPresetCount: Int, val ancGainMax: Int, val atPresetCount: Int, val atGainMax: Int)

    fun parseModeInfo(body: ByteArray): ModeInfo {
        val f = ProtoReader.map(body)
        fun item(field: Int): Pair<Int, Int> {
            val m = f[field]?.bytes?.let { ProtoReader.map(it) } ?: return 0 to 0
            return (m[1]?.int ?: 0) to (m[2]?.int ?: 0)
        }
        val (ap, ag) = item(1); val (tp, tg) = item(2)
        return ModeInfo(ap, ag, tp, tg)
    }

    // Sidetone ------------------------------------------------------------------------------

    /** `SidetoneConfigure {1:enabled?, 2:volume?}`. Unset fields leave that attribute unchanged. */
    fun sidetoneConfigure(enabled: Boolean? = null, volume: Int? = null): ByteArray = ProtoWriter.build {
        if (enabled != null) bool(1, enabled, writeDefault = true)
        if (volume != null) uint32(2, volume, writeDefault = true)
    }

    fun sidetoneRead(subscribe: Boolean): ByteArray = ProtoWriter.build { bool(1, subscribe) }

    /** `SidetoneState {1:enabled, 2:volume}`. */
    data class SidetoneState(val enabled: Boolean, val volume: Int)
    fun parseSidetoneState(body: ByteArray): SidetoneState {
        val f = ProtoReader.map(body); return SidetoneState(f[1]?.bool ?: false, f[2]?.int ?: 0)
    }

    /** `SidetoneInfo {1:volume_max}`. */
    fun parseSidetoneInfo(body: ByteArray): Int = ProtoReader.map(body)[1]?.int ?: 0

    // Parametric EQ -------------------------------------------------------------------------

    /** `PEQBand {1:frequency(Hz), 2:gain(dB float), 3:q(float)}`. */
    data class PeqBand(val frequencyHz: Int, val gainDb: Float, val q: Float) {
        fun encode(): ByteArray = ProtoWriter.build {
            uint32(1, frequencyHz, writeDefault = true)
            float(2, gainDb, writeDefault = true)
            float(3, q, writeDefault = true)
        }
        companion object {
            fun parse(body: ByteArray): PeqBand {
                val f = ProtoReader.map(body)
                return PeqBand(f[1]?.int ?: 0, f[2]?.float ?: 0f, f[3]?.float ?: 0f)
            }
        }
    }

    /** `PEQConfigure {1:id, 2:activate, 3:persist, 4:repeated bands, 5:clear}`. */
    fun peqConfigure(id: Int, activate: Boolean, persist: Boolean, bands: List<PeqBand>, clear: Boolean = false): ByteArray =
        ProtoWriter.build {
            uint32(1, id, writeDefault = true)
            bool(2, activate)
            bool(3, persist)
            bands.forEach { message(4, it.encode()) }
            bool(5, clear)
        }

    /** `PEQInfo {1:max_band_count,2:min_freq,3:max_freq,4:min_gain,5:max_gain,6:gain_steps,7:min_q,8:max_q,9:q_steps}`. */
    data class PeqInfo(
        val maxBandCount: Int, val minFreq: Int, val maxFreq: Int,
        val minGain: Float, val maxGain: Float, val gainSteps: Int,
        val minQ: Float, val maxQ: Float, val qSteps: Int,
    )
    fun parsePeqInfo(body: ByteArray): PeqInfo {
        val f = ProtoReader.map(body)
        return PeqInfo(
            f[1]?.int ?: 0, f[2]?.int ?: 0, f[3]?.int ?: 0,
            f[4]?.float ?: 0f, f[5]?.float ?: 0f, f[6]?.int ?: 0,
            f[7]?.float ?: 0f, f[8]?.float ?: 0f, f[9]?.int ?: 0,
        )
    }
}

// ── spatial_audio (service 17): Bose TrueSpatial ─────────────────────────────────────────

/** `bos.spatial_audio.AudioSourceMode`. OFF=1, FIXED_TO_HEAD=2 ("Still"), FIXED_TO_ROOM=3 ("Motion"). */
enum class SpatialMode(val wire: Int) {
    UNSPECIFIED(0), OFF(1), STILL(2), MOTION(3);
    companion object { fun fromWire(v: Int) = entries.firstOrNull { it.wire == v } ?: UNSPECIFIED }
}

object SpatialAudio {
    /** `AudioSourceModeSetRequest {1:mode}`. */
    fun setMode(mode: SpatialMode): ByteArray = ProtoWriter.build { enum(1, mode.wire, writeDefault = mode != SpatialMode.UNSPECIFIED) }

    /** `InfoGetRequest {1:subscribe}`. */
    fun infoGet(subscribe: Boolean): ByteArray = ProtoWriter.build { bool(1, subscribe) }

    /** `Info {1:is_available, 2:audio_source_mode}`. */
    data class Info(val available: Boolean, val mode: SpatialMode)
    fun parseInfo(body: ByteArray): Info {
        val f = ProtoReader.map(body); return Info(f[1]?.bool ?: false, SpatialMode.fromWire(f[2]?.int ?: 0))
    }
}

// ── crusher (service 23): haptic bass ────────────────────────────────────────────────────

object Crusher {
    /** `CrusherRequest {1:is_active?, 2:intensity?, 3:mode?}`. Unset fields leave that attribute unchanged. */
    fun stateSet(isActive: Boolean? = null, intensity: Int? = null, mode: Int? = null): ByteArray = ProtoWriter.build {
        if (isActive != null) bool(1, isActive, writeDefault = true)
        if (intensity != null) uint32(2, intensity, writeDefault = true)
        if (mode != null) uint32(3, mode, writeDefault = true)
    }

    /** `CrusherReadRequest {1:subscribe}`. */
    fun stateGet(subscribe: Boolean): ByteArray = ProtoWriter.build { bool(1, subscribe) }

    /** `CrusherResponse {1:is_active, 2:intensity, 3:mode?}`. */
    data class State(val isActive: Boolean, val intensity: Int, val mode: Int?)
    fun parseState(body: ByteArray): State {
        val f = ProtoReader.map(body); return State(f[1]?.bool ?: false, f[2]?.int ?: 0, f[3]?.int)
    }

    /** `CrusherConfigResponse {1:max_intensity, 2:intensity_read_only, 3:mode_count?}`. mode 0=high,1=med,2=low. */
    data class Config(val maxIntensity: Int, val intensityReadOnly: Boolean, val modeCount: Int?)
    fun parseConfig(body: ByteArray): Config {
        val f = ProtoReader.map(body); return Config(f[1]?.int ?: 0, f[2]?.bool ?: false, f[3]?.int)
    }
}

// ── device (service 2): info and battery ─────────────────────────────────────────────────

object DeviceInfo {
    /**
     * `DevInfoRequest {1:gender?}`. The Skullcandy app always sends gender UNSPECIFIED explicitly
     * (a proto3 optional field with presence), so the body is `08 00`, not empty; Earshot does the
     * same in case a firmware is strict about it. Unspecified means "the primary unit".
     */
    fun devInfo(): ByteArray = ProtoWriter.build { enum(1, 0, writeDefault = true) }

    /**
     * `DevInfoResponse` — the fields Earshot reads: 4:model, 5:fwver, 7:bosver, 9:sn, 11:model_number,
     * **12:authenticated_access** (whether protected channels need the secure handshake).
     */
    data class DevInfoResponse(
        val model: String?, val firmware: String?, val bosVersion: String?,
        val serial: String?, val modelNumber: String?, val authenticatedAccess: Boolean,
    )
    fun parseDevInfo(body: ByteArray): DevInfoResponse {
        val f = ProtoReader.map(body)
        fun str(n: Int) = f[n]?.bytes?.toString(Charsets.UTF_8)?.trimEnd('\u0000')?.ifBlank { null }
        return DevInfoResponse(str(4), str(5), str(7), str(9), str(11), (f[12]?.int ?: 0) != 0)
    }

    /** `BattInfoResponse {1:repeated BatteryInfo{1:gender,2:level,3:status}}`. First entry = own. */
    enum class BatteryStatus(val wire: Int) { UNSPECIFIED(0), IDLE(1), CHARGING(2);
        companion object { fun fromWire(v: Int) = entries.firstOrNull { it.wire == v } ?: UNSPECIFIED } }
    data class Battery(val gender: Int, val level: Int, val status: BatteryStatus)
    fun parseBattery(body: ByteArray): List<Battery> =
        ProtoReader.repeated(body, 1).map {
            val m = ProtoReader.map(it)
            Battery(m[1]?.int ?: 0, m[2]?.int ?: 0, BatteryStatus.fromWire(m[3]?.int ?: 0))
        }
}

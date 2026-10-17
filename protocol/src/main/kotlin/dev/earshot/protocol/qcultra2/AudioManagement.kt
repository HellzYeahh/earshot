package dev.earshot.protocol.qcultra2

/*
 * AudioManagement block [5.x]: source, transport, play state, volume, latency. Layouts from the
 * Bose app 13.3.4 parsers, values checked against the fw 10.12 scan. All multi-byte integers are
 * big-endian.
 */

// ── Source [5.1] ─────────────────────────────────────────────────────────────────────────

/** `[5.1]` parsed. [mac] is set only for a Bluetooth source. */
data class AudioSource(val supportedMask: Int, val typeCode: Int, val mac: String?, val raw: ByteArray) {
    val type: AudioSourceType? get() = AudioSourceType.fromCode(typeCode)

    /** Source types offered (bit n of the big-endian mask = [AudioSourceType] code n). */
    val supportedTypeCodes: Set<Int> get() = Bitset.decode(raw, 0, minOf(2, raw.size))

    override fun equals(other: Any?): Boolean = other is AudioSource && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
}

/**
 * `[5.1]` AudioManagement.Source, GET only: `[supported(2, big-endian bitset), activeType, data...]`
 * with 6 MAC bytes when the active type is Bluetooth (fw 10.12 `000f 01 10b1df000003`: sources
 * {None, BT, Aux, USB}, active Bluetooth). There is no SET/START: the source changes implicitly
 * (plug in aux, or route via `[4.12]`).
 */
object AudioSourceQuery {
    fun parse(payload: ByteArray): AudioSource? {
        if (payload.size < 3) return null
        val type = payload.u8(2)
        val mac = if (type == AudioSourceType.BLUETOOTH.code && payload.size >= 3 + Mac.LENGTH) Mac.format(payload, 3) else null
        return AudioSource(payload.u16be(0), type, mac, payload.copyOf())
    }
}

// ── Control [5.3] ────────────────────────────────────────────────────────────────────────

/**
 * `[5.3]` Control. GET → `[supported bitset]` (one or two bytes, big-endian; fw 10.12 `1f` =
 * Stop, Play, Pause, TrackForward, TrackBack). START `[value]` sends a transport command, which
 * is what the Bose app does (bosectl's error 5 came from trying plain SET). The START is
 * untested on hardware.
 */
object AudioControl {
    fun startPayload(control: TransportControl): ByteArray = byteArrayOf(control.code.toByte())

    /** Supported [TransportControl] codes. */
    fun parseSupported(payload: ByteArray): Set<Int> = if (payload.isEmpty()) emptySet() else Bitset.decode(payload)
}

// ── Status [5.4] ─────────────────────────────────────────────────────────────────────────

data class AudioStatus(
    val statusCode: Int,
    /** Signed 16-bit track position; -1 (`ffff`) = unknown. Unit not stated by the app (seconds, probably). */
    val trackPosition: Int?,
) {
    val status: PlaybackStatus? get() = PlaybackStatus.fromCode(statusCode)
}

/** `[5.4]` GET → `[AudioStatus][trackPosition s16 BE]`; fw 10.12 `01 ffff` = playing, position unknown. */
object AudioStatusQuery {
    fun parse(payload: ByteArray): AudioStatus? {
        if (payload.isEmpty()) return null
        val pos = if (payload.size >= 3) payload.s16be(1).takeIf { it >= 0 } else null
        return AudioStatus(payload.u8(0), pos)
    }
}

// ── Volume [5.5] ─────────────────────────────────────────────────────────────────────────

data class VolumeState(val max: Int, val current: Int)

/**
 * `[5.5]` GET → `[max][current]`; fw 10.12 `1f 16` = 22 of 31 (bosectl's `1f0e` = 14 of 31).
 * SET_GET `[level]` (the Bose app's setter); untested on hardware.
 */
object Volume {
    fun parse(payload: ByteArray): VolumeState? =
        if (payload.size < 2) null else VolumeState(max = payload.u8(0), current = payload.u8(1))

    fun build(level: Int, max: Int = QcUltra2.VOLUME_MAX_FW_10_12): ByteArray {
        require(level in 0..max) { "volume $level outside 0..$max" }
        return byteArrayOf(level.toByte())
    }
}

// ── Latency [5.7] ────────────────────────────────────────────────────────────────────────

data class LatencyLevel(val min: Int, val max: Int, val current: Int) {
    val range: IntRange get() = min..max
}

/**
 * `[5.7]` AudioManagement Latency: GET → `[min s16][max s16][current s16]`, big-endian; fw 10.12
 * `0000 0208 00d2` = 0..520, current 210 (milliseconds is an inference). bosectl's captures show
 * the current value drifting 0xD7-0xDD across unrelated changes, so it may be a measurement
 * rather than a set-point. SET_GET `[s16 BE]` exists in the Bose app (`setLatency(short)`) but is
 * never called by it: **experimental, untested on hardware**.
 */
object Latency {
    fun parse(payload: ByteArray): LatencyLevel? =
        if (payload.size < 6) null else LatencyLevel(payload.s16be(0), payload.s16be(2), payload.s16be(4))

    fun build(ms: Int): ByteArray {
        require(ms in Short.MIN_VALUE..Short.MAX_VALUE) { "latency $ms does not fit a signed 16-bit value" }
        return byteArrayOf((ms shr 8).toByte(), ms.toByte())
    }
}

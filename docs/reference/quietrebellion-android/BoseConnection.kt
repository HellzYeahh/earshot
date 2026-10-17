/*
 * BoseConnection.kt – High-level BMAP operations for QC Ultra (wolverine).
 * Port of BoseConnection.cs / python/pybmap/connection.py.
 */
package net.quietrebellion

import android.util.Log
import java.io.Closeable
import kotlinx.coroutines.delay

private const val TAG = "BoseCtl"

class BoseProtocolException(message: String) : Exception(message)

class BoseConnection(private val transport: BluetoothTransport) : Closeable {

    // ── Read operations ───────────────────────────────────────────────────────

    suspend fun battery(): Int {
        val (fb, fn) = QcUltra2.Battery
        return QcUltra2.parseBattery(get(fb, fn).payload)
    }

    /** Returns true if charging, false if not, null if unavailable. */
    suspend fun chargingState(): Boolean? = try {
        val (fb, fn) = QcUltra2.ChargingState
        val p = get(fb, fn).payload
        if (p.isNotEmpty()) p[0] != 0.toByte() else null
    } catch (_: Exception) { null }

    /** Returns set of favorited mode indices and the total mode count. */
    suspend fun favorites(): Pair<Set<Int>, Int> {
        val (fb, fn) = QcUltra2.Favorites
        val p = get(fb, fn).payload
        return QcUltra2.parseFavorites(p) to (if (p.isNotEmpty()) p[0].toInt() and 0xFF else 11)
    }

    suspend fun firmware(): String {
        val (fb, fn) = QcUltra2.Firmware
        return QcUltra2.parseFirmware(get(fb, fn).payload)
    }

    // deviceName() removed – only used for rename flow which reads via tvDeviceName; re-add if a dedicated "read name" UI is needed

    suspend fun modeIndex(): Int {
        val (fb, fn) = QcUltra2.CurrentMode
        val p = get(fb, fn).payload
        return if (p.isNotEmpty()) p[0].toInt() and 0xFF else -1
    }

    suspend fun audioSettings(): AudioSettings {
        val (fb, fn) = QcUltra2.AudioSettings
        return QcUltra2.parseAudioSettings(get(fb, fn).payload)
    }

    suspend fun eq(): List<EqBand> {
        val (fb, fn) = QcUltra2.Eq
        return QcUltra2.parseEq(get(fb, fn).payload)
    }

    suspend fun sidetone(): Int {
        val (fb, fn) = QcUltra2.Sidetone
        return QcUltra2.parseSidetone(get(fb, fn).payload)
    }

    suspend fun sidetonePersistent(): Boolean {
        val (fb, fn) = QcUltra2.Sidetone
        return QcUltra2.parseSidetonePersistence(get(fb, fn).payload)
    }

    suspend fun multipoint(): Boolean {
        val (fb, fn) = QcUltra2.Multipoint
        return QcUltra2.parseMultipoint(get(fb, fn).payload)
    }

    suspend fun source(): Pair<String, String?> {
        val (fb, fn) = QcUltra2.Source
        return QcUltra2.parseSource(get(fb, fn).payload)
    }



    suspend fun autoPlayPause(): Boolean {
        val (fb, fn) = QcUltra2.AutoPlayPause
        val p = get(fb, fn).payload
        return p.isNotEmpty() && p[0] != 0.toByte()
    }

    suspend fun autoAnswer(): Boolean {
        val (fb, fn) = QcUltra2.AutoAnswer
        val p = get(fb, fn).payload
        return p.isNotEmpty() && p[0] != 0.toByte()
    }

    suspend fun pairedDeviceMacs(): List<String> {
        val (fb, fn) = QcUltra2.PairedDevices
        return QcUltra2.parsePairedDevices(get(fb, fn).payload)
    }

    /** Returns paired MACs mapped to their device names (from 4.5 DeviceInfo). */
    suspend fun pairedDevicesWithNames(): Map<String, String> {
        val macs = pairedDeviceMacs()
        val (fb, fn) = QcUltra2.DeviceInfo
        val result = LinkedHashMap<String, String>()
        for (mac in macs) {
            val macBytes = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
            val name = try {
                val pkt = BmapProtocol.build(fb, fn, Op.GET, macBytes)
                val raw = transport.sendRecv(pkt)
                val resp = BmapProtocol.parse(raw)
                if (resp != null && resp.op == Op.STATUS) QcUltra2.parseDeviceInfo(resp.payload) else null
            } catch (_: Exception) { null }
            result[mac] = name ?: mac
        }
        return result
    }

    /** Returns dict of mode index → display name (preset 0-3 + custom 4-10). */
    suspend fun allModeNames(): Map<Int, String> {
        val (fb, fn) = QcUltra2.GetAllModes
        val pkt      = BmapProtocol.build(fb, fn, Op.START)
        val raw      = transport.sendRecv(pkt, drain = true)
        val names    = QcUltra2.MODE_NAMES.toMutableMap()
        val (cfgFb, cfgFn) = QcUltra2.ModeConfigStatus
        for (r in BmapProtocol.parseAll(raw)) {
            if (r.fblock == cfgFb && r.func == cfgFn && r.op == Op.STATUS) {
                val (idx, name) = QcUltra2.parseModeConfigBasic(r.payload) ?: continue
                names[idx] = name
            }
        }
        return names
    }

    // ── Write operations ──────────────────────────────────────────────────────


    suspend fun powerOff() {
        val (fb, fn) = QcUltra2.Power
        // Device disconnects immediately after power-off; no STATUS expected.
        try { transport.sendRecv(BmapProtocol.build(fb, fn, Op.START, byteArrayOf(0x00))) }
        catch (_: Exception) { /* expected – socket closes */ }
    }


    suspend fun setAutoPlayPause(enabled: Boolean) {
        val (fb, fn) = QcUltra2.AutoPlayPause
        val pkt = BmapProtocol.build(fb, fn, Op.SETGET, byteArrayOf(if (enabled) 1 else 0))
        throwIfError(BmapProtocol.parse(transport.sendRecv(pkt)))
    }

    suspend fun setAutoAnswer(enabled: Boolean) {
        val (fb, fn) = QcUltra2.AutoAnswer
        val pkt = BmapProtocol.build(fb, fn, Op.SETGET, byteArrayOf(if (enabled) 1 else 0))
        throwIfError(BmapProtocol.parse(transport.sendRecv(pkt)))
    }

    suspend fun enterPairingMode() {
        val (fb, fn) = QcUltra2.Pairing
        // Device enters pairing and typically disconnects – socket close is expected.
        try { transport.sendRecv(BmapProtocol.build(fb, fn, Op.START, byteArrayOf(0x01))) }
        catch (_: Exception) { }
    }

    suspend fun setModeByIndex(index: Int, announce: Boolean = false) {
        val (fb, fn) = QcUltra2.CurrentMode
        // Second payload byte: voice-prompt announcement of the new mode, observed in official
        // app traffic. Confirmed non-functional on current wolverine firmware (no audible effect) —
        // kept as a documented no-op rather than silently dropped, in case a future firmware or
        // other device honors it. No UI exposes this; always called with announce=false for now.
        val pkt = BmapProtocol.build(fb, fn, Op.START, byteArrayOf(index.toByte(), if (announce) 1 else 0))
        throwIfError(BmapProtocol.parse(transport.sendRecv(pkt)))
    }

    suspend fun setCncLevel(level: Int) {
        require(level in 0..10) { "CNC level must be 0–10" }
        writeAudioSettings(audioSettings().copy(cncLevel = level))
    }

    suspend fun setWindBlock(enabled: Boolean) =
        writeAudioSettings(audioSettings().copy(windBlock = enabled))

    suspend fun setSpatial(mode: SpatialMode) =
        writeAudioSettings(audioSettings().copy(spatial = mode))

    suspend fun setAnc(enabled: Boolean) =
        writeAudioSettings(audioSettings().copy(ancToggle = enabled))

    suspend fun setSidetone(level: Int) {
        val (fb, fn) = QcUltra2.Sidetone
        // Preserve persistence flag when user changes only the level.
        val persistent = try { sidetonePersistent() } catch (_: Exception) { true }
        val pkt = BmapProtocol.build(fb, fn, Op.SETGET, QcUltra2.buildSidetone(level, persistent))
        throwIfError(BmapProtocol.parse(transport.sendRecv(pkt)))
    }

    suspend fun setSidetonePersistent(enabled: Boolean) {
        val (fb, fn) = QcUltra2.Sidetone
        // Preserve level when user changes only persistence.
        val level = try { sidetone() } catch (_: Exception) { 0 }
        val pkt = BmapProtocol.build(fb, fn, Op.SETGET, QcUltra2.buildSidetone(level, enabled))
        throwIfError(BmapProtocol.parse(transport.sendRecv(pkt)))
    }

    suspend fun setMultipoint(enabled: Boolean) {
        val (fb, fn) = QcUltra2.Multipoint
        val pkt = BmapProtocol.build(fb, fn, Op.SETGET, QcUltra2.buildToggle(enabled))
        throwIfError(BmapProtocol.parse(transport.sendRecv(pkt)))
    }

    suspend fun setEqBand(bandId: Int, value: Int) {
        require(value in -10..10) { "EQ value must be -10..+10" }
        val (fb, fn) = QcUltra2.Eq
        val pkt = BmapProtocol.build(fb, fn, Op.SETGET, QcUltra2.buildEqBand(value, bandId))
        throwIfError(BmapProtocol.parse(transport.sendRecv(pkt)))
    }

    suspend fun setDeviceName(name: String) {
        val (fb, fn) = QcUltra2.ProductName
        val pkt = BmapProtocol.build(fb, fn, Op.SETGET, QcUltra2.buildDeviceName(name))
        throwIfError(BmapProtocol.parse(transport.sendRecv(pkt)))
    }

    /**
     * Returns true when the firmware routing table is populated, i.e. two BT devices are
     * simultaneously streaming A2DP. Detection: GET on [4.12] returns FuncNotSupp (4) when
     * the routing table is empty; any other response means routing is available.
     */
    suspend fun canRoute(): Boolean {
        val (fb, fn) = QcUltra2.Routing
        val pkt  = BmapProtocol.build(fb, fn, Op.GET)
        val resp = BmapProtocol.parse(transport.sendRecv(pkt)) ?: return false
        return !(resp.op == Op.ERROR && resp.payload.getOrNull(0) == 4.toByte())
    }

    suspend fun switchToDevice(mac: String) {
        val (fb, fn) = QcUltra2.Routing
        // The firmware routing table is only populated during the brief window when the target
        // device's A2DP stream starts (before the headphone pauses it). We retry several times
        // with short delays to catch that window.
        // User should start playback on the target device and tap Switch at the same time.
        for (attempt in 0 until 5) {
            if (attempt > 0) delay(500)
            val pkt  = BmapProtocol.build(fb, fn, Op.START, QcUltra2.buildRouting(mac))
            val resp = BmapProtocol.parse(transport.sendRecv(pkt)) ?: return
            if (resp.op == Op.ERROR && resp.payload.getOrNull(0) == 4.toByte()) {
                Log.d(TAG, "switchToDevice: attempt $attempt FuncNotSupp – routing table empty, retrying")
                continue   // routing table not ready yet, try again
            }
            throwIfError(resp)
            Log.d(TAG, "switchToDevice: success on attempt $attempt")
            return
        }
        throw BoseProtocolException(
            "Switch not available: start playback on the target device and immediately tap Switch, then try again."
        )
    }

    suspend fun setFavorites(favSet: Set<Int>, totalModes: Int = 11) {
        val (fb, fn) = QcUltra2.Favorites
        val pkt = BmapProtocol.build(fb, fn, Op.SETGET, QcUltra2.buildFavorites(totalModes, favSet))
        throwIfError(BmapProtocol.parse(transport.sendRecv(pkt)))
    }

    suspend fun writeAudioSettings(s: AudioSettings) {
        val (fb, fn) = QcUltra2.AudioSettings
        val payload  = QcUltra2.buildAudioSettings(s)
        Log.d(TAG, "writeAudioSettings payload=${payload.hex()}")
        val pkt  = BmapProtocol.build(fb, fn, Op.SETGET, payload)
        val raw  = transport.sendRecv(pkt)
        Log.d(TAG, "writeAudioSettings response=${raw.hex()}")
        throwIfError(BmapProtocol.parse(raw))
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun get(fblock: Byte, func: Byte): BmapResponse {
        val pkt  = BmapProtocol.build(fblock, func, Op.GET)
        Log.d(TAG, "GET [${fblock.hex()}.${func.hex()}] → ${pkt.hex()}")
        val raw  = transport.sendRecv(pkt)
        Log.d(TAG, "GET [${fblock.hex()}.${func.hex()}] ← ${raw.hex()}")
        val resp = BmapProtocol.parse(raw)
        Log.d(TAG, "GET parsed: ${resp?.format() ?: "null"}")
        throwIfError(resp)
        return resp ?: error("No response for GET [$fblock.$func]")
    }


    private fun throwIfError(resp: BmapResponse?) {
        if (resp?.op != Op.ERROR) return
        val code = if (resp.payload.isNotEmpty()) resp.payload[0] else 0
        throw BoseProtocolException("Device error: ${BmapError.name(code)} (${resp.format()})")
    }

    override fun close() = transport.close()
}

private fun Byte.hex() = "%02x".format(this)
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }


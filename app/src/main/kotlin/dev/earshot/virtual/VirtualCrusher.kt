package dev.earshot.virtual

import dev.earshot.protocol.bragi.BosMessageType
import dev.earshot.protocol.bragi.BosRpcPacket
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.bragi.Hearing
import dev.earshot.protocol.bragi.HearingMode
import dev.earshot.protocol.bragi.ProtoWriter
import dev.earshot.protocol.bragi.SpatialMode

/**
 * An in-process stand-in for the Skullcandy Crusher 1080 ANC, speaking BOS-RPC the way the
 * Skullcandy app's definitions describe it. It is the device side of every call Earshot makes, so
 * the real [dev.earshot.bt.BragiConnection] and protocol layer can be exercised end to end without
 * the headphones (unit tests, demo mode).
 *
 * What it models: hearing mode (Off / ANC "Quiet" / AT "Aware") with gain, sidetone, parametric EQ,
 * TrueSpatial, Crusher bass and battery. Streaming reads ([BragiServices.MODE_READ] and friends)
 * answer with the current state and then push every change until aborted, like the real firmware.
 *
 * It does not claim to match hardware byte for byte where the app's definitions are silent (the
 * STATUS body encoding, for one): those spots follow the most likely reading and are marked.
 *
 * Thread-safety: every public method is synchronised.
 */
class VirtualCrusher(
    /** Whether the unit announces `authenticated_access` in `dev_info` (the secure-session gate). */
    val authenticatedAccess: Boolean = false,
) {
    // ── Identity ─────────────────────────────────────────────────────────────────────────
    val model = "CHP300"
    val modelNumber = "S6CNW"
    val firmware = "1.0.0-virtual"
    val bosVersion = "bos-virtual"
    val serial = "VIRTUAL-CRUSHER-1080"

    // ── State ────────────────────────────────────────────────────────────────────────────
    var hearingMode = HearingMode.ANC; private set
    var ancPreset = 1; private set
    var ancGain = 7; private set
    var atPreset = 1; private set
    var atGain = 5; private set
    val ancGainMax = 10
    val atGainMax = 10

    var spatial = SpatialMode.OFF; private set
    var spatialAvailable = true

    var sidetoneEnabled = true; private set
    var sidetoneVolume = 3; private set
    val sidetoneVolumeMax = 5

    var crusherActive = true; private set
    var crusherIntensity = 5; private set
    var crusherMode = 1; private set
    val crusherMaxIntensity = 10
    val crusherModeCount = 3

    var batteryPercent = 68
    var charging = false

    var peqEnabled = false; private set
    var peqCurrent = 0; private set
    val peqBands = mutableListOf<Hearing.PeqBand>()

    /** Every request that reached the device, in order (tests assert on this). */
    val received = mutableListOf<BosRpcPacket>()

    private data class Sub(val address: BragiServices.Address, val tag: Int)
    private val subs = mutableListOf<Sub>()

    // ── Dispatch ─────────────────────────────────────────────────────────────────────────

    /** Handle one request; returns the frames to send back, in order (responses, then any pushes). */
    @Synchronized
    fun handle(packet: BosRpcPacket): List<BosRpcPacket> {
        received += packet
        val a = packet.address
        if (packet.type == BosMessageType.ABORT) {
            subs.removeAll { it.address == a }
            return emptyList()
        }
        if (packet.type != BosMessageType.REQUEST) return emptyList()
        val out = ArrayList<BosRpcPacket>()
        val tag = packet.tag
        fun final(payload: ByteArray) { out += reply(a, tag, payload, final = true); out += status(a, tag) }
        fun ok() { out += status(a, tag) }
        fun stream(payload: ByteArray) { out += reply(a, tag, payload, final = false) }

        when (a) {
            BragiServices.DEV_INFO -> final(devInfo())
            BragiServices.BATT_INFO -> final(battInfo())
            BragiServices.HEARING_MODE_INFO -> final(ProtoWriter.build {
                message(1, ProtoWriter.build { uint32(1, 1, true); uint32(2, ancGainMax, true) })
                message(2, ProtoWriter.build { uint32(1, 1, true); uint32(2, atGainMax, true) })
            })
            BragiServices.MODE_READ -> { subscribe(a, tag, packet); stream(modeState()) }
            BragiServices.MODE_CONFIGURE -> { configureMode(packet.payload); ok(); out += pushes(BragiServices.MODE_READ, modeState()) }
            BragiServices.SIDETONE_INFO -> final(ProtoWriter.build { uint32(1, sidetoneVolumeMax, true) })
            BragiServices.SIDETONE_READ -> { subscribe(a, tag, packet); stream(sidetoneState()) }
            BragiServices.SIDETONE_CONFIGURE -> { configureSidetone(packet.payload); ok(); out += pushes(BragiServices.SIDETONE_READ, sidetoneState()) }
            BragiServices.SPATIAL_INFO_GET -> { subscribe(a, tag, packet); stream(spatialInfo()) }
            BragiServices.SPATIAL_MODE_SET -> { setSpatial(packet.payload); ok(); out += pushes(BragiServices.SPATIAL_INFO_GET, spatialInfo()) }
            BragiServices.CRUSHER_CONFIG_GET -> final(ProtoWriter.build {
                uint32(1, crusherMaxIntensity, true); bool(2, false); uint32(3, crusherModeCount, true)
            })
            BragiServices.CRUSHER_STATE_GET -> { subscribe(a, tag, packet); stream(crusherState()) }
            BragiServices.CRUSHER_STATE_SET -> { setCrusher(packet.payload); final(crusherState()); out += pushes(BragiServices.CRUSHER_STATE_GET, crusherState()) }
            BragiServices.PEQ_INFO -> final(peqInfo())
            BragiServices.PEQ_READ_STATE -> { subscribe(a, tag, packet); stream(peqState()) }
            BragiServices.PEQ_CONFIGURE -> { configurePeq(packet.payload); ok(); out += pushes(BragiServices.PEQ_READ_STATE, peqState()) }
            BragiServices.PEQ_ENABLE -> { peqEnabled = true; ok(); out += pushes(BragiServices.PEQ_READ_STATE, peqState()) }
            BragiServices.PEQ_DISABLE -> { peqEnabled = false; ok(); out += pushes(BragiServices.PEQ_READ_STATE, peqState()) }
            else -> out += BosRpcPacket(BosMessageType.STATUS, a.service, a.method, tag, byteArrayOf(UNKNOWN_METHOD))
        }
        return out
    }

    /** A button press or app elsewhere changes the mode: push to subscribers. Tests and demo mode use it. */
    @Synchronized
    fun setHearingModeOnDevice(mode: HearingMode): List<BosRpcPacket> { hearingMode = mode; return pushes(BragiServices.MODE_READ, modeState()) }

    // ── Replies ──────────────────────────────────────────────────────────────────────────

    private fun reply(a: BragiServices.Address, tag: Int, payload: ByteArray, final: Boolean) =
        BosRpcPacket(BosMessageType.RESPONSE, a.service, a.method, tag, payload, isFinal = final)

    /** STATUS with an empty body = success (the body encoding of errors is not confirmed on hardware). */
    private fun status(a: BragiServices.Address, tag: Int) =
        BosRpcPacket(BosMessageType.STATUS, a.service, a.method, tag)

    private fun subscribe(a: BragiServices.Address, tag: Int, p: BosRpcPacket) {
        subs.removeAll { it.address == a }
        val wantsPush = p.payload.isEmpty() || (p.payload.size >= 2 && p.payload[0] == 0x08.toByte() && p.payload[1] != 0.toByte())
        if (wantsPush) subs += Sub(a, tag)
    }

    private fun pushes(a: BragiServices.Address, payload: ByteArray): List<BosRpcPacket> =
        subs.filter { it.address == a }.map { reply(a, it.tag, payload, final = false) }

    // ── Payloads ─────────────────────────────────────────────────────────────────────────

    private fun devInfo() = ProtoWriter.build {
        string(3, "Skullcandy"); string(4, model); string(5, firmware); string(7, bosVersion)
        string(9, serial); string(11, modelNumber); bool(12, authenticatedAccess)
    }

    private fun battInfo() = ProtoWriter.build {
        message(1, ProtoWriter.build { uint32(2, batteryPercent, true); uint32(3, if (charging) 2 else 1, true) })
    }

    private fun modeState() = ProtoWriter.build {
        enum(1, hearingMode.wire)
        message(2, ProtoWriter.build { uint32(1, ancPreset, true); uint32(2, ancGain, true) })
        message(3, ProtoWriter.build { uint32(1, atPreset, true); uint32(2, atGain, true) })
    }

    private fun sidetoneState() = ProtoWriter.build { bool(1, sidetoneEnabled); uint32(2, sidetoneVolume, true) }
    private fun spatialInfo() = ProtoWriter.build { bool(1, spatialAvailable); enum(2, spatial.wire) }
    private fun crusherState() = ProtoWriter.build { bool(1, crusherActive); uint32(2, crusherIntensity, true); uint32(3, crusherMode, true) }
    private fun peqState() = ProtoWriter.build { bool(1, peqEnabled); uint32(2, peqCurrent, true) }

    private fun peqInfo() = ProtoWriter.build {
        uint32(1, 5, true); uint32(2, 20, true); uint32(3, 20000, true)
        float(4, -12f, true); float(5, 12f, true); uint32(6, 49, true)
        float(7, 0.5f, true); float(8, 10f, true); uint32(9, 20, true)
    }

    // ── Mutations (ranges as the definitions state them) ─────────────────────────────────

    private fun fields(body: ByteArray) = dev.earshot.protocol.bragi.ProtoReader.map(body)

    private fun configureMode(body: ByteArray) {
        val f = fields(body)
        val mode = HearingMode.fromWire(f[1]?.int ?: 0)
        val activate = f[2]?.bool ?: false
        val preset = f[3]?.int
        val gain = f[4]?.int
        when (mode) {
            HearingMode.ANC -> { preset?.let { ancPreset = it }; gain?.let { ancGain = it.coerceIn(0, ancGainMax) } }
            HearingMode.AT -> { preset?.let { atPreset = it }; gain?.let { atGain = it.coerceIn(0, atGainMax) } }
            else -> Unit
        }
        if (activate && mode != HearingMode.UNSPECIFIED) hearingMode = mode
    }

    private fun configureSidetone(body: ByteArray) {
        val f = fields(body)
        f[1]?.let { sidetoneEnabled = it.bool }
        f[2]?.let { sidetoneVolume = it.int.coerceIn(0, sidetoneVolumeMax) }
    }

    private fun setSpatial(body: ByteArray) {
        val m = SpatialMode.fromWire(fields(body)[1]?.int ?: 0)
        if (m != SpatialMode.UNSPECIFIED) spatial = m
    }

    private fun setCrusher(body: ByteArray) {
        val f = fields(body)
        f[1]?.let { crusherActive = it.bool }
        f[2]?.let { crusherIntensity = it.int.coerceIn(0, crusherMaxIntensity) }
        f[3]?.let { crusherMode = it.int.coerceIn(0, crusherModeCount - 1) }
    }

    private fun configurePeq(body: ByteArray) {
        val f = fields(body)
        peqCurrent = f[1]?.int ?: 0
        val bands = dev.earshot.protocol.bragi.ProtoReader.repeated(body, 4).map { Hearing.PeqBand.parse(it) }
        if (f[5]?.bool == true) peqBands.clear()
        if (bands.isNotEmpty()) { peqBands.clear(); peqBands += bands }
        if (f[2]?.bool == true) peqEnabled = true
    }

    companion object {
        /** STATUS code for an unknown method. The real value is not confirmed; this only has to be non-zero. */
        const val UNKNOWN_METHOD: Byte = 0x60
    }
}

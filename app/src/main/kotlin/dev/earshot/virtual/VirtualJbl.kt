package dev.earshot.virtual

import dev.earshot.bt.ByteTransport
import dev.earshot.protocol.jbl.JblCommands as C
import dev.earshot.protocol.jbl.JblFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The headset side of a JBL Wave Buds 2 (firmware 2.7.0, H-AA), in-process: demo mode and the
 * connection / session tests. Every answer follows jbl-control's capture of a real pair (MIT, JBL
 * notes §2–3); the defaults reproduce that capture byte for byte (right bud in its case, Ambient
 * Aware on, EQ off, voice prompts on), so [answer] of the captured reads gives the captured frames.
 *  - the device info (`AA 12`) comes by itself when the link opens ([hello]) and on `AA 11 00`;
 *  - `AA 9B` (pushes on) is not answered, but the pair then pushes the call-status TLV, as it did;
 *  - the level read `AA 91 01 21` is silent unless [ancLevel] is set, as on a real Wave Buds 2;
 *  - the older-earbuds battery read (`AA 25`) is never answered (an assumption: the capture never sent
 *    it, and a protocol-3 pair reports its batteries in the TLV);
 *  - ANC and TalkThru are declined (silently, status unchanged) while [bothBudsIn] is false;
 *  - the EQ with the category Off is the all-zero frame the real buds sent, a preset carries ten bands;
 *  - everything else, including the commands Earshot must never send, gets silence.
 * [received] records every frame that reached the headset, so tests can prove what never left the phone.
 */
class VirtualJbl(
    var name: String = "JBL Wave Buds 2",
    var productId: Int = 0x2100,
    var firmware: List<Int> = listOf(2, 7, 0),
    /** The pair's Bluetooth address; made up by default (a documentation address, RFC 7042). */
    val address: ByteArray = byteArrayOf(0x00, 0x00, 0x5E, 0x00, 0x53, 0x01),
    var serial: String = "VIRTUALWB2000001",
    /** Battery levels in percent; [UNKNOWN] when the headset doesn't know (a bud in its case). */
    var left: Int = 100,
    var right: Int = UNKNOWN,
    var case: Int = 100,
    /** The one listening mode that is on ([C.WIRE_ANC], [C.WIRE_AMBIENT], [C.WIRE_TALKTHRU]); null = Off. */
    var mode: Int? = C.WIRE_AMBIENT,
    /** False: the buds decline ANC and TalkThru without a word ("it needs both buds in", jbl-control). */
    var bothBudsIn: Boolean = true,
    /** Manual noise-cancelling level (1..7); null = the level read goes unanswered, as on a Wave Buds 2. */
    var ancLevel: Int? = null,
    var eqCategory: Int = C.EQ_OFF,
    var eqGains: MutableList<Float> = MutableList(C.EQ_BAND_HZ.size) { 0f },
    var voicePrompts: Boolean = true,
) {
    /** Every frame received from the phone, in order. */
    val received = mutableListOf<JblFrame>()

    var leftCharging = false
    var rightCharging = false
    var caseCharging = false

    /** True once the phone sent `AA 9B 02 01 01`. Pushes are not gated on it: tests may push any time. */
    var notificationsOn = false
        private set

    /** How many heartbeat echoes (`AA B1 04 01 00 01 00`) the phone sent back. */
    var heartbeatEchoes = 0
        private set

    /**
     * Where the headset's own frames (pushes) go: [VirtualJblTransport] sets it while it is open. A
     * push with nothing attached only updates the state and returns the frame.
     */
    var link: ((JblFrame) -> Unit)? = null

    /** What the headset sends by itself the moment the link opens: the device info. */
    fun hello(): List<JblFrame> = listOf(deviceInfo())

    /** Every frame to send back for [frame], in order (empty = silence). */
    fun answer(frame: JblFrame): List<JblFrame> {
        received += frame
        val p = frame.payload.map { it.toInt() and 0xFF }
        return when (frame.command) {
            C.NOTIFY_ON -> if (p == listOf(0x01, 0x01)) {
                notificationsOn = true
                listOf(callStatus())
            } else emptyList()
            C.DEVICE_INFO_GET -> listOf(deviceInfo())
            C.LISTENING -> listening(p)
            C.INFO -> if (p.firstOrNull() == TLV_GET) listOfNotNull(tlvReturn(p)) else emptyList()
            C.EQ -> when {
                p.firstOrNull() == C.EQ_GET -> listOf(eqStatus())
                p.firstOrNull() == C.EQ_SET && p.size == C.EQ_SET_SIZE -> {
                    applyEq(frame.payload)
                    emptyList()
                }
                else -> emptyList()
            }
            C.VOICE_PROMPTS -> when {
                p == listOf(0x04) -> listOf(make(C.VOICE_PROMPTS, 0x05, voicePrompts.bit()))
                p.size == 2 && p[0] == 0x03 -> {
                    voicePrompts = p[1] != 0
                    emptyList()
                }
                else -> emptyList()
            }
            // The captured answers of the reads the Lab sends.
            C.VOICE_AWARE -> if (p == listOf(0x01)) listOf(make(C.VOICE_AWARE, 0x02, 0x02, 0x00)) else emptyList()
            C.FIND -> if (p == listOf(0x01)) listOf(make(C.FIND, 0x02, 0x00, 0x00)) else emptyList()
            C.SERIAL -> if (p == listOf(0x01)) listOf(JblFrame(C.SERIAL, byteArrayOf(0x02) + serialBytes())) else emptyList()
            C.SAV -> if (p == listOf(0x01)) {
                listOf(make(C.SAV, 0x02, 0x00, 0x01, 0x2E, 0x00, 0x18, 0x01, 0xFF, 0xFF))
            } else emptyList()
            C.HEARTBEAT -> {
                if (p == listOf(0x01, 0x00, 0x01, 0x00)) heartbeatEchoes++
                emptyList()
            }
            // `AA 25` (the older-earbuds battery) included: assumed silent on a Wave Buds 2, never captured.
            else -> emptyList()
        }
    }

    // ---- Pushes (a bud tap, a battery change, the heartbeat) ------------------------------------

    /** The levels changed on the headset: set them and push the three battery TLVs. Returns the frame. */
    fun pushBattery(left: Int, right: Int, case: Int): JblFrame {
        this.left = left
        this.right = right
        this.case = case
        return push(tlv(listOf(C.FID_BATTERY_LEFT, C.FID_BATTERY_RIGHT, C.FID_BATTERY_CASE)))
    }

    /** The user tapped a bud: switch to [wire] (null = Off) and push the `91 12` status. */
    fun pushMode(wire: Int?): JblFrame {
        require(wire == null || wire in C.WIRE_MODES) { "mode $wire is not one of ${C.WIRE_MODES}" }
        mode = wire
        return push(modeStatus())
    }

    /** The heartbeat (`AA B1 04 01 00 01 00`); the phone must echo it once it has the device info. */
    fun pushHeartbeat(): JblFrame = push(C.heartbeatEcho())

    private fun push(frame: JblFrame): JblFrame {
        link?.invoke(frame)
        return frame
    }

    // ---- Frames the headset sends --------------------------------------------------------------

    /**
     * `AA 12 25` + 37 bytes: name (16, zero padded), product id (little-endian), model byte 0,
     * battery byte, address (6), firmware (3) and the eight bytes the capture ended with.
     */
    private fun deviceInfo(): JblFrame {
        val payload = name16() +
            bytes(productId and 0xFF, productId shr 8, 0x00, batteryByte()) +
            address.copyOf(ADDRESS_SIZE) +
            bytes(*firmware.toIntArray()) +
            bytes(0x64, 0xF2, 0x96, 0x10, 0xFF, 0xFF, 0x02, 0x00)
        return JblFrame(C.DEVICE_INFO, payload)
    }

    /** Bit 7 charging, bits 0–6 the lower known bud level; 0x7F (above 100) when no bud reports. */
    private fun batteryByte(): Int {
        val level = listOf(left, right).filter { it in 0..100 }.minOrNull() ?: 0x7F
        return level or if (leftCharging || rightCharging) 0x80 else 0
    }

    /** The call-status TLV (`1F` = 0) the buds pushed right after the pushes were switched on. */
    private fun callStatus(): JblFrame = JblFrame(C.INFO, bytes(TLV_RETURN, 0x00, C.FID_CALL, 0x01, 0x00))

    /** `91 12` and one `[mode, on]` pair for each mode, in mode order; all pairs 0 is Off. */
    private fun modeStatus(): JblFrame {
        val pairs = C.WIRE_MODES.sorted().flatMap { listOf(it, if (mode == it) 1 else 0) }
        return make(C.LISTENING, C.MODE_STATUS, *pairs.toIntArray())
    }

    private fun listening(p: List<Int>): List<JblFrame> = when (p.firstOrNull()) {
        C.MODE_GET -> listOf(modeStatus())
        C.MODE_SET -> {
            applyModeSet(p)
            listOf(modeStatus())
        }
        C.LEVEL_GET -> ancLevel?.let { listOf(make(C.LISTENING, C.LEVEL_STATUS, C.LEVEL_MANUAL_ANC, it)) } ?: emptyList()
        C.LEVEL_SET -> {
            if (ancLevel != null && p.size == 3 && p[1] == C.LEVEL_MANUAL_ANC) ancLevel = p[2].coerceIn(C.ANC_LEVELS)
            emptyList()
        }
        else -> emptyList()
    }

    /**
     * A mode write is any subset of the `[mode, on]` pairs. The pair switched on wins; a pair that
     * switches off the current mode with none on leaves Off; pairs for other modes change nothing.
     * ANC and TalkThru are declined while [bothBudsIn] is false (the answer is then the old status).
     */
    private fun applyModeSet(p: List<Int>) {
        val pairs = (1 until p.size - 1 step 2).map { p[it] to p[it + 1] }.filter { it.first in C.WIRE_MODES }
        val turnedOn = pairs.lastOrNull { it.second == 1 }?.first
        if (turnedOn != null) {
            if (turnedOn != C.WIRE_AMBIENT && !bothBudsIn) return
            mode = turnedOn
        } else if (mode != null && pairs.any { it.first == mode }) {
            mode = null
        }
    }

    /** The TLV return for the ids of a GET (`01` then `[fid u16 BE]…`): known ids only, in request order. */
    private fun tlvReturn(p: List<Int>): JblFrame? {
        val ids = (1 until p.size - 1 step 2).map { (p[it] shl 8) or p[it + 1] }.filter { tlvValue(it) != null }
        // Nothing the headset knows: no frame at all (the capture only shows the ids it answers).
        return if (ids.isEmpty()) null else tlv(ids)
    }

    private fun tlv(ids: List<Int>): JblFrame {
        var payload = bytes(TLV_RETURN)
        for (fid in ids) {
            val value = tlvValue(fid) ?: continue
            payload += bytes(fid shr 8, fid and 0xFF, value.size) + value
        }
        return JblFrame(C.INFO, payload)
    }

    /** The values of the capture: name, firmware, TWS 6, auto-off, spatial, sidetone, batteries, SafeSound. */
    private fun tlvValue(fid: Int): ByteArray? = when (fid) {
        C.FID_NAME -> name16()
        C.FID_FIRMWARE -> bytes(*firmware.toIntArray())
        C.FID_TWS -> bytes(0x06)
        C.FID_AUTO_OFF -> bytes(0x00, 0x00, 0x00)
        C.FID_SPATIAL -> bytes(0x00, 0x00)
        C.FID_SIDETONE -> bytes(0x00, 0x02)
        C.FID_BATTERY_LEFT -> bytes(leftCharging.bit(), left)
        C.FID_BATTERY_RIGHT -> bytes(rightCharging.bit(), right)
        C.FID_BATTERY_CASE -> bytes(caseCharging.bit(), case)
        C.FID_SAFE_SOUND -> bytes(0x00)
        else -> null // the headband battery (0x19) and anything else: a Wave Buds 2 leaves it out
    }

    /**
     * The EQ return. Off is what the real buds sent: `04` and 115 zero bytes (no bands, no
     * calibration). A preset has the layout of the write (calibration −1.0, 48 kHz, ten peak bands
     * at the app's frequencies, Q 1.0) with the gains last written.
     */
    private fun eqStatus(): JblFrame {
        val payload = ByteArray(C.EQ_SET_SIZE)
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, EQ_RETURN.toByte())
        if (eqCategory != C.EQ_OFF) {
            b.put(1, eqCategory.toByte())
            b.putFloat(2, C.EQ_CALIBRATION)
            b.put(6, C.EQ_SAMPLE_RATE_KHZ.toByte())
            b.put(15, C.EQ_BAND_HZ.size.toByte())
            for ((i, hz) in C.EQ_BAND_HZ.withIndex()) {
                val at = C.EQ_HEADER_SIZE + i * C.EQ_BAND_SIZE
                b.put(at, C.EQ_BAND_PEAK.toByte())
                b.putFloat(at + 1, eqGains.getOrElse(i) { 0f })
                b.putFloat(at + 5, hz.toFloat())
                b.put(at + 9, C.EQ_Q_TIMES_TEN.toByte())
            }
        }
        return JblFrame(C.EQ, payload, longLength = true)
    }

    /** Store the category and the ten gains of an EQ write ([C.eqSet]'s layout); no answer follows. */
    private fun applyEq(payload: ByteArray) {
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        eqCategory = b.get(1).toInt() and 0xFF
        eqGains = MutableList(C.EQ_BAND_HZ.size) { i -> b.getFloat(C.EQ_HEADER_SIZE + i * C.EQ_BAND_SIZE + 1) }
    }

    private fun name16(): ByteArray = name.toByteArray(Charsets.UTF_8).copyOf(NAME_SIZE)

    private fun serialBytes(): ByteArray = serial.padEnd(NAME_SIZE, '0').take(NAME_SIZE).toByteArray(Charsets.US_ASCII)

    private fun Boolean.bit() = if (this) 1 else 0

    private fun make(command: Int, vararg values: Int) = JblFrame(command, bytes(*values))

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    companion object {
        /** A battery level the headset doesn't know (a bud in its case). */
        const val UNKNOWN = 0xFF

        private const val TLV_GET = 0x01
        private const val TLV_RETURN = 0x02
        private const val EQ_RETURN = 0x04
        private const val NAME_SIZE = 16
        private const val ADDRESS_SIZE = 6
    }
}

/**
 * A [ByteTransport] whose far end is a [VirtualJbl]. The phone writes one whole frame per write()
 * (one GATT write each), so each write is decoded with [JblFrame.decodeWhole]; the answers come back
 * through an unbounded channel. The headset's unsolicited device info is the first thing on the
 * link, as with the real pair; frames that don't decode are ignored.
 */
class VirtualJblTransport(private val unit: VirtualJbl) : ByteTransport {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val attach: (JblFrame) -> Unit = ::push

    override var isConnected: Boolean = true
    override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

    init {
        unit.link = attach
        unit.hello().forEach(::push)
    }

    override suspend fun write(bytes: ByteArray) {
        if (!isConnected) throw IOException("The virtual JBL link is closed")
        val frame = JblFrame.decodeWhole(bytes) ?: return
        unit.answer(frame).forEach(::push)
    }

    /** Push a frame as the headset would (a bud tap, a battery change). */
    fun push(frame: JblFrame) {
        inbound.trySend(frame.encode())
    }

    override fun close() {
        isConnected = false
        inbound.close()
        if (unit.link === attach) unit.link = null
    }
}

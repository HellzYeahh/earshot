package dev.earshot.virtual

import dev.earshot.bt.ByteTransport
import dev.earshot.protocol.sony.SonyMessage
import dev.earshot.protocol.sony.SonyTable
import dev.earshot.protocol.sony.T1
import dev.earshot.protocol.sony.T2
import dev.earshot.protocol.sony.TandemDataType
import dev.earshot.protocol.sony.TandemFrame
import dev.earshot.protocol.sony.TandemFrameBuffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** The Sony demo entry. An extension rather than an enum flag, so other drivers' entries merge cleanly. */
val DemoDevice.isSony: Boolean get() = this == DemoDevice.SONY_WH1000XM5

/**
 * The headset side of a Sony WH-1000XM5 on MDR v2, in-process: demo mode and the end-to-end tests.
 * Message layouts are the ones `:protocol`'s `sony` package parses (checked there against
 * mos9527's WF-1000XM5 and LinkBuds Clip captures); the XM5's exact support list and capability
 * replies are a best guess from the protocol notes, since no WH-1000XM5 capture was available.
 *
 * Like a real unit it answers what it supports, pushes a NTFY for every change, ACKs every
 * unknown command and then ignores it, and stays silent while [silent]. It switches confirmation
 * prompts on when asked and raises one when multipoint changes.
 */
class VirtualSony(
    var batteryPercent: Int = 70,
    var charging: Boolean = false,
    /** NCASM type 0x17 state: total effect, mode (0 NC, 1 ambient), focus on voice, level 1..20. */
    var ncEffect: Boolean = true,
    var ncMode: Int = 0,
    var focusOnVoice: Boolean = false,
    var ambientLevel: Int = 15,
    var eqPreset: Int = 0x00,
    var eqBands: MutableList<Int> = MutableList(6) { 10 },
    var autoOff: Int = 0x10,
    var autoOffLast: Int = 0x00,
    var multipoint: Boolean = true,
    var touchPanel: Boolean = true,
    var dsee: Boolean = true,
    var connectionMode: Int = 0,
    var speakToChat: Boolean = false,
    var pauseWhenTakenOff: Boolean = true,
    var voiceGuidance: Boolean = true,
    var protocolVersion: Int = 0x03003010,
    /** v1 headsets reply to protocol info with 4 bytes. */
    var mdrV1: Boolean = false,
) {
    /** Every message received (deduplicated), for the tests. */
    val received = mutableListOf<SonyMessage>()

    /** Confirmation-prompt answers received: `(messageType, positive)`. */
    val alertAnswers = mutableListOf<Pair<Int, Boolean>>()
    var alertsEnabled = false
        private set
    var poweredOff = false
        private set

    /** While true nothing is ACKed or answered: an app holding the channel, a dead link. */
    var silent = false

    /** Phone frames to drop entirely (never seen, so never ACKed): the phone must resend. */
    var dropIncoming = 0

    /** ACKs to "lose" after the frame was processed: the phone resends and the duplicate is only ACKed. */
    var dropAcks = 0

    /** Every ACK seq the phone sent, and every data-frame seq it used, in order. */
    val phoneAckSeqs = mutableListOf<Int>()
    val phoneDataSeqs = mutableListOf<Int>()

    private var lastPhoneSeq = -1
    private var nextSeq = 0

    /** Bytes to put on the wire towards the phone. */
    internal var emit: (ByteArray) -> Unit = {}

    /** A new connection: powered on, both sequence numbers back to their start. */
    internal fun attach(emit: (ByteArray) -> Unit) {
        this.emit = emit
        poweredOff = false
        lastPhoneSeq = -1
        nextSeq = 0
    }

    private fun frame(table: SonyTable, payload: ByteArray) {
        val f = TandemFrame(table.dataType, nextSeq, payload)
        // The phone ACKs every frame at once; a real unit waits for that ACK, this one just moves on.
        nextSeq = nextSeq xor 1
        emit(f.encode())
    }

    private fun reply(payload: ByteArray) = frame(SonyTable.T1, payload)
    private fun reply2(payload: ByteArray) = frame(SonyTable.T2, payload)

    /** Push something the headset changed by itself (a button press, the battery). */
    fun push(table: SonyTable, payload: ByteArray) = frame(table, payload)

    /** A frame from the phone. */
    internal fun onFrame(f: TandemFrame) {
        if (f.isAck) {
            phoneAckSeqs += f.seq
            return
        }
        if (silent || poweredOff) return
        if (dropIncoming > 0) {
            dropIncoming--
            return
        }
        phoneDataSeqs += f.seq
        val duplicate = f.seq == lastPhoneSeq
        lastPhoneSeq = f.seq
        if (dropAcks > 0) dropAcks-- else if (TandemDataType.needsAck(f.dataType)) emit(TandemFrame.ack(f.seq xor 1).encode())
        if (duplicate) return
        val table = SonyTable.forDataType(f.dataType) ?: return
        val m = SonyMessage(table, f.payload)
        received += m
        if (table == SonyTable.T1) answerT1(m.payload) else answerT2(m.payload)
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun onOff(on: Boolean) = if (on) 0 else 1
    private fun bit(on: Boolean) = if (on) 1 else 0
    private fun prefixed(s: String): ByteArray = s.toByteArray().let { byteArrayOf(it.size.toByte()) + it }

    private fun ncParam(cmd: Int) = b(cmd, 0x17, 0x01, bit(ncEffect), ncMode, bit(focusOnVoice), ambientLevel)
    private fun eqParam(cmd: Int) = b(cmd, 0x00, eqPreset, eqBands.size) + ByteArray(eqBands.size) { eqBands[it].toByte() }
    private fun gsName(type: Int) = if (type == 0xD1) "MULTIPOINT_SETTING" else "TOUCH_PANEL_SETTING"
    private fun gsValue(type: Int) = if (type == 0xD1) multipoint else touchPanel

    private fun answerT1(p: ByteArray) {
        val cmd = p[0].toInt() and 0xFF
        val type = if (p.size > 1) p[1].toInt() and 0xFF else -1
        fun at(i: Int) = p[i].toInt() and 0xFF
        when (cmd) {
            T1.CONNECT_GET_PROTOCOL_INFO -> reply(
                if (mdrV1) b(0x01, 0x00, 0x01, 0x10)
                else b(0x01, 0x00, protocolVersion ushr 24, (protocolVersion ushr 16) and 0xFF, (protocolVersion ushr 8) and 0xFF, protocolVersion and 0xFF, 0x00, 0x00),
            )
            T1.CONNECT_GET_CAPABILITY_INFO -> reply(b(0x03, 0x00, 0x0B) + prefixed("00:00:00:DE:50:01"))
            T1.CONNECT_GET_DEVICE_INFO -> when (type) {
                0x01 -> reply(b(0x05, 0x01) + prefixed(MODEL))
                0x02 -> reply(b(0x05, 0x02) + prefixed(FIRMWARE))
                0x03 -> reply(b(0x05, 0x03, 0x30, 0x01))
            }
            T1.CONNECT_GET_SUPPORT_FUNCTION -> reply(b(0x07, 0x00, T1_FUNCTIONS.size) + T1_FUNCTIONS.flatMap { listOf(it.toByte(), 0x00) }.toByteArray())
            T1.COMMON_GET_STATUS -> if (type == 0x02) reply(b(0x13, 0x02, 0x10)) // LDAC
            T1.POWER_GET_STATUS -> if (type == 0x00) reply(b(0x23, 0x00, batteryPercent, bit(charging)))
            T1.POWER_GET_CAPABILITY -> if (type == 0x05) reply(b(0x21, 0x05, AUTO_OFF.size) + ByteArray(AUTO_OFF.size) { AUTO_OFF[it].toByte() })
            T1.POWER_GET_PARAM -> if (type == 0x05) reply(b(0x27, 0x05, autoOff, autoOffLast))
            T1.POWER_SET_PARAM -> if (type == 0x05) {
                autoOff = at(2); autoOffLast = at(3)
                reply(b(0x29, 0x05, autoOff, autoOffLast))
            }
            T1.POWER_SET_STATUS -> if (type == 0x03 && at(2) == 0x01) poweredOff = true
            T1.NCASM_GET_CAPABILITY -> if (type == 0x17) reply(b(0x61, 0x17, 0x02, 0x00, 0x01, 0x14, 0x01, 0x01, 0x01, 0x14, 0x01))
            T1.NCASM_GET_PARAM -> if (type == 0x17) reply(ncParam(0x67))
            T1.NCASM_SET_PARAM -> if (type == 0x17 && p.size == 7) {
                ncEffect = at(3) == 1; ncMode = at(4); focusOnVoice = at(5) == 1; ambientLevel = at(6)
                reply(ncParam(0x69))
            }
            T1.EQEBB_GET_CAPABILITY -> if (type == 0x00) {
                val presets = EQ_PRESETS.flatMap { listOf(it.toByte(), 0x00) }.toByteArray()
                reply(b(0x51, 0x00, 0x06, 0x15, EQ_PRESETS.size) + presets)
            }
            T1.EQEBB_GET_PARAM -> if (type == 0x00) reply(eqParam(0x57))
            T1.EQEBB_SET_PARAM -> if (type == 0x00 && at(2) in EQ_PRESETS) {
                eqPreset = at(2)
                val n = at(3)
                if (n == eqBands.size) eqBands = MutableList(n) { at(4 + it) }
                else if (n == 0) PRESET_BANDS[eqPreset]?.let { eqBands = it.toMutableList() }
                reply(eqParam(0x59))
            }
            T1.GENERAL_SETTING_GET_CAPABILITY -> if (type == 0xD1 || type == 0xD2) {
                reply(b(0xD1, type, 0x00, 0x01) + prefixed(gsName(type)) + prefixed(gsName(type) + "_SUMMARY"))
            }
            T1.GENERAL_SETTING_GET_PARAM -> if (type == 0xD1 || type == 0xD2) reply(b(0xD7, type, 0x00, onOff(gsValue(type))))
            T1.GENERAL_SETTING_SET_PARAM -> if (type == 0xD1 || type == 0xD2) {
                val on = at(3) == 0
                if (type == 0xD1) {
                    // Changing multipoint reconnects the headset: it asks first, if prompts are on.
                    if (alertsEnabled) reply(b(0x99, 0x00, 0x07, 0x01))
                    multipoint = on
                } else touchPanel = on
                reply(b(0xD9, type, 0x00, onOff(on)))
            }
            T1.AUDIO_GET_CAPABILITY -> if (type == 0x01) reply(b(0xE1, 0x01, 0x02))
            T1.AUDIO_GET_PARAM -> when (type) {
                0x00 -> reply(b(0xE7, 0x00, connectionMode))
                0x01 -> reply(b(0xE7, 0x01, bit(dsee)))
            }
            T1.AUDIO_SET_PARAM -> when (type) {
                0x00 -> { connectionMode = at(2); reply(b(0xE9, 0x00, connectionMode)) }
                0x01 -> { dsee = at(2) == 1; reply(b(0xE9, 0x01, bit(dsee))) }
            }
            T1.SYSTEM_GET_PARAM -> when (type) {
                0x01 -> reply(b(0xF7, 0x01, onOff(pauseWhenTakenOff)))
                0x0C -> reply(b(0xF7, 0x0C, onOff(speakToChat), 0x01))
            }
            T1.SYSTEM_SET_PARAM -> when (type) {
                0x01 -> { pauseWhenTakenOff = at(2) == 0; reply(b(0xF9, 0x01, onOff(pauseWhenTakenOff))) }
                0x0C -> { speakToChat = at(2) == 0; reply(b(0xF9, 0x0C, onOff(speakToChat), 0x01)) }
            }
            T1.ALERT_SET_STATUS -> if (type == 0x00) alertsEnabled = at(2) == 0
            T1.ALERT_SET_PARAM -> if (type == 0x00) alertAnswers += at(2) to (at(3) == 1)
            else -> Unit // ACKed above, otherwise ignored, as a real unit does
        }
    }

    private fun answerT2(p: ByteArray) {
        val cmd = p[0].toInt() and 0xFF
        val type = if (p.size > 1) p[1].toInt() and 0xFF else -1
        when (cmd) {
            T2.CONNECT_GET_SUPPORT_FUNCTION -> reply2(b(0x07, 0x00, T2_FUNCTIONS.size) + T2_FUNCTIONS.flatMap { listOf(it.toByte(), 0x00) }.toByteArray())
            T2.VOICE_GUIDANCE_GET_PARAM -> if (type == 0x01) reply2(b(0x47, 0x01, onOff(voiceGuidance), 0x01))
            T2.VOICE_GUIDANCE_SET_PARAM -> if (type == 0x01) voiceGuidance = (p[2].toInt() and 0xFF) == 0
        }
    }

    companion object {
        const val MODEL = "WH-1000XM5"
        const val FIRMWARE = "1.1.3"

        /**
         * Table 1 (best guess for the WH-1000XM5): codec, single battery, power off, auto-off with
         * wearing detection, keep-alive, a firmware-update function (listed, never used), preset EQ,
         * NCASM 0x6B (type 0x17), auto NCASM, prompts, playback, the two log notifiers (never
         * switched on), general settings 1 and 2, connection quality, DSEE, pause when taken off,
         * button assignment, reset (never sent) and speak-to-chat.
         */
        val T1_FUNCTIONS = listOf(
            0x11, 0x12, 0x20, 0x23, 0x25, 0x27, 0x32, 0x50, 0x6B, 0x70, 0x90, 0xA1, 0xC1, 0xC2,
            0xD1, 0xD2, 0xE1, 0xE2, 0xF1, 0xF3, 0xF9, 0xFC,
        )

        /** Table 2: pairing management, source switch, voice guidance with language switch. */
        val T2_FUNCTIONS = listOf(0x30, 0x31, 0x41)

        val AUTO_OFF = listOf(0x00, 0x01, 0x02, 0x03, 0x10, 0x11)
        val EQ_PRESETS = listOf(0x00, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0xA0, 0xA1, 0xA2)

        /** A few presets' band steps (Clear Bass first, 10 = 0 dB). */
        private val PRESET_BANDS = mapOf(
            0x00 to List(6) { 10 },
            0x10 to listOf(10, 9, 10, 12, 14, 15),
            0x16 to listOf(16, 14, 11, 10, 10, 10),
            0x14 to listOf(10, 8, 12, 14, 12, 10),
        )
    }
}

/** A [ByteTransport] whose far end is a [VirtualSony]. */
class VirtualSonyTransport(private val unit: VirtualSony) : ByteTransport {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val frames = TandemFrameBuffer()

    override var isConnected: Boolean = true
    override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

    init {
        unit.attach { bytes -> inbound.trySend(bytes) }
    }

    override suspend fun write(bytes: ByteArray) {
        if (!isConnected) throw java.io.IOException("closed")
        for (f in frames.feed(bytes)) unit.onFrame(f)
        // A real unit drops the link once it powers off.
        if (unit.poweredOff) close()
    }

    override fun close() {
        isConnected = false
        inbound.close()
    }
}

package dev.earshot.virtual

import dev.earshot.bt.ByteTransport
import dev.earshot.protocol.gaia.GaiaCommand
import dev.earshot.protocol.gaia.GaiaError
import dev.earshot.protocol.gaia.GaiaFrame
import dev.earshot.protocol.gaia.GaiaFrameBuffer
import dev.earshot.protocol.gaia.GaiaVendor
import dev.earshot.protocol.sennheiser.SennheiserCommands as C
import dev.earshot.protocol.sennheiser.SennheiserWire
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The headset side of a Sennheiser Momentum 4 (firmware 3.37.3), in-process: demo mode and the
 * end-to-end tests. Its answers and side effects follow momentum4-control's captures (MIT):
 *  - Qualcomm API 3.1 and both feature lists exactly as a Momentum 4 sends them;
 *  - writes answer with an empty response, then the notifications a real unit pushes;
 *  - `1A04 [01]` (ANC on) also switches transparency off and the level to 0; `1A04 [00]` leaves both;
 *  - `1804 [01]` sets the level to 100, `1804 [00]` restores the level from before;
 *  - a mode-table write (one pair or all three) switches transparency off and the level to 0;
 *  - auto-off writes push nothing;
 *  - registering a feature dumps its state as notifications;
 *  - a command outside its feature list answers "feature not supported", an unknown one in a
 *    known feature "command not supported" (error frames).
 * It also records every frame it received, so tests can prove what never left the phone.
 */
class VirtualSennheiser(
    var modelId: String = "M4AEBT Black",
    var battery: Int = 80,
    var ancOn: Boolean = true,
    var transparencyOn: Boolean = false,
    var level: Int = 0,
    /** Mode table: 1 anti-wind (0 off, 1 max, 2 auto), 2 comfort, 3 adaptive. */
    val modes: MutableMap<Int, Int> = linkedMapOf(1 to 1, 2 to 0, 3 to 0),
    /** Sennheiser feature list as a Momentum 4 on 3.37.3 reports it (feature → version). */
    val features: Map<Int, Int> = M4_FEATURES,
    var eqGains: MutableList<Int> = mutableListOf(0, 0, 0, 0, 0),
) {
    /** Every frame received, in order. */
    val received = mutableListOf<GaiaFrame>()

    var pauseInTransparency = false
    var transparencyLevel = 100
    var onHead = true
    var smartPause = true
    var autoAnswer = false
    var comfortCall = true
    var autoOffSeconds = 900
    var sidetone = 2
    var bassBoost = false
    var charging = 0

    /** The level transparency will restore when it is switched off. */
    private var levelBeforeTransparency = 0

    /** Every frame to send back for [frame], in order: its response or error, then pushes. */
    fun answer(frame: GaiaFrame): List<GaiaFrame> {
        received += frame
        if (frame.type != GaiaCommand.TYPE_COMMAND) return emptyList()
        return when (frame.vendor) {
            GaiaVendor.QUALCOMM -> qualcomm(frame)
            C.VENDOR -> sennheiser(frame)
            else -> emptyList() // an unknown vendor: silence
        }
    }

    private fun qualcomm(f: GaiaFrame): List<GaiaFrame> = when (f.command) {
        0x0000 -> listOf(ok(f, 3, 1))
        0x0001 -> listOf(ok(f, 0, 0x0D, 0x01, 0x0C, 0x01, 0x06, 0x02, 0x04, 0x02, 0x07, 0x01, 0x00, 0x04))
        else -> listOf(err(f, GaiaError.COMMAND_NOT_SUPPORTED))
    }

    private fun sennheiser(f: GaiaFrame): List<GaiaFrame> {
        val c = f.command
        if (c == C.SUPPORTED_FEATURES) {
            val pairs = features.flatMap { (k, v) -> listOf(k, v) }
            return listOf(ok(f, *(listOf(0) + pairs).toIntArray()))
        }
        if (GaiaCommand.feature(c) !in features) return listOf(err(f, GaiaError.FEATURE_NOT_SUPPORTED))
        val p = f.payload
        val v = p.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        return when (c) {
            C.REGISTER_NOTIFICATION -> listOf(ok(f)) + dump(v)
            C.MODEL_ID_GET -> listOf(GaiaFrame(f.vendor, GaiaCommand.response(c), modelId.toByteArray() + 0.toByte()))
            C.FIRMWARE_GET -> listOf(ok(f, 0, 3, 0, 0x25, 0, 3))
            C.BATTERY_GET -> listOf(ok(f, battery))
            C.CHARGING_GET -> listOf(ok(f, charging))
            C.WEAR_STATE_GET -> listOf(ok(f, 3))
            C.ANC_GET -> listOf(ok(f, ancOn.bit()))
            C.TH_GET -> listOf(ok(f, transparencyOn.bit()))
            C.ANC_LEVEL_GET -> listOf(ok(f, level))
            C.ANC_MODE_GET -> listOf(ok(f, *modeTable()))
            C.TH_BEHAVIOUR_GET -> listOf(ok(f, pauseInTransparency.bit()))
            // A Momentum 4 answers the earbuds' transparency level too (100, sidetone capture).
            C.TH_LEVEL_GET -> listOf(ok(f, transparencyLevel))
            C.TH_LEVEL_SET -> {
                transparencyLevel = v.coerceIn(0, 100)
                listOf(ok(f), push(C.TH_LEVEL_GET, transparencyLevel))
            }
            C.ON_HEAD_GET -> listOf(ok(f, onHead.bit()))
            C.SMART_PAUSE_GET -> listOf(ok(f, smartPause.bit()))
            C.AUTO_ANSWER_GET -> listOf(ok(f, autoAnswer.bit()))
            C.COMFORT_CALL_GET -> listOf(ok(f, comfortCall.bit()))
            C.SIDETONE_GET -> listOf(ok(f, sidetone))
            C.AUTO_OFF_GET -> listOf(ok(f, 0, autoOffSeconds shr 8, autoOffSeconds and 0xFF))
            C.EQ_CONFIG_GET -> listOf(ok(f, eqGains.size, -60, 60, 0, 0))
            C.EQ_ALL_GET -> listOf(ok(f, *eqGains.toIntArray()))
            C.EQ_SUBMODE_GET -> listOf(ok(f, bassBoost.bit()))

            C.ANC_SET -> {
                ancOn = v == 1
                val pushes = mutableListOf(push(C.ANC_GET, ancOn.bit()))
                if (ancOn) {
                    // The captured side effects: transparency off, level to 0.
                    if (transparencyOn) { transparencyOn = false; pushes += push(C.TH_GET, 0) }
                    level = 0
                    pushes += push(C.ANC_LEVEL_GET, 0)
                }
                listOf(ok(f)) + pushes
            }
            C.TH_SET -> {
                val on = v == 1
                if (on && !transparencyOn) levelBeforeTransparency = level
                if (on) level = 100 else if (transparencyOn) level = levelBeforeTransparency
                transparencyOn = on
                listOf(ok(f), push(C.TH_GET, on.bit()), push(C.ANC_LEVEL_GET, level))
            }
            C.ANC_LEVEL_SET -> {
                level = v.coerceIn(0, 100)
                listOf(ok(f), push(C.ANC_LEVEL_GET, level))
            }
            C.ANC_MODE_SET -> {
                for (i in p.indices step 2) if (i + 1 < p.size) modes[p[i].toInt() and 0xFF] = p[i + 1].toInt() and 0xFF
                transparencyOn = false
                level = 0
                listOf(ok(f), push(C.ANC_MODE_GET, *modeTable()), push(C.ANC_LEVEL_GET, 0))
            }
            C.TH_BEHAVIOUR_SET -> { pauseInTransparency = v == 1; listOf(ok(f)) }
            C.ON_HEAD_SET -> { onHead = v == 1; listOf(ok(f)) }
            C.SMART_PAUSE_SET -> { smartPause = v == 1; listOf(ok(f)) }
            C.AUTO_ANSWER_SET -> { autoAnswer = v == 1; listOf(ok(f)) }
            C.COMFORT_CALL_SET -> { comfortCall = v == 1; listOf(ok(f)) }
            C.SIDETONE_SET -> { sidetone = v; listOf(ok(f)) }
            // No push: the auto-off capture shows none, even with feature 3 registered.
            C.AUTO_OFF_SET -> { SennheiserWire.autoOffSeconds(p)?.let { autoOffSeconds = it }; listOf(ok(f)) }
            C.EQ_BAND_SET -> {
                val band = v
                if (band !in eqGains.indices || p.size < 2) listOf(err(f, GaiaError.INVALID_PARAMETER))
                else {
                    eqGains[band] = p[1].toInt()
                    listOf(ok(f), push(C.EQ_BAND_GET, *eqGains.toIntArray()))
                }
            }
            C.EQ_SUBMODE_SET -> { bassBoost = v and 1 == 1; listOf(ok(f), push(C.EQ_SUBMODE_GET, bassBoost.bit())) }
            else -> listOf(err(f, GaiaError.COMMAND_NOT_SUPPORTED))
        }
    }

    /** What a real unit pushes right after a registration (stage5 capture), per feature. */
    private fun dump(feature: Int): List<GaiaFrame> = when (feature) {
        3 -> listOf(push(C.CHARGING_GET, charging), push(C.BATTERY_GET, battery))
        13 -> listOf(push(C.ANC_GET, ancOn.bit()), push(C.ANC_MODE_GET, *modeTable()), push(C.ANC_LEVEL_GET, level))
        12 -> listOf(push(C.TH_GET, transparencyOn.bit()))
        8 -> listOf(push(C.EQ_BAND_GET, *eqGains.toIntArray()), push(C.EQ_SUBMODE_GET, bassBoost.bit()))
        // Feature 2 answers with a stray response for the wear state, as the capture shows.
        2 -> listOf(GaiaFrame(C.VENDOR, GaiaCommand.response(C.WEAR_STATE_GET), byteArrayOf(3)))
        else -> emptyList()
    }

    private fun modeTable(): IntArray = modes.flatMap { (k, v) -> listOf(k, v) }.toIntArray()

    private fun Boolean.bit() = if (this) 1 else 0

    private fun ok(f: GaiaFrame, vararg payload: Int) = GaiaFrame(f.vendor, GaiaCommand.response(f.command), bytes(payload))
    private fun err(f: GaiaFrame, code: Int) = GaiaFrame(f.vendor, GaiaCommand.error(f.command), byteArrayOf(code.toByte()))
    private fun push(getCommand: Int, vararg payload: Int) = GaiaFrame(C.VENDOR, GaiaCommand.notification(getCommand), bytes(payload))
    private fun bytes(values: IntArray) = ByteArray(values.size) { values[it].toByte() }

    companion object {
        /** `00 | 09 01 08 06 0C 01 03 02 10 02 04 02 02 01 0A 02 14 01 0B 01 0D 01 00 04` (supported-features capture). */
        val M4_FEATURES: Map<Int, Int> = linkedMapOf(9 to 1, 8 to 6, 12 to 1, 3 to 2, 16 to 2, 4 to 2, 2 to 1, 10 to 2, 20 to 1, 11 to 1, 13 to 1, 0 to 4)
    }
}

/** A [ByteTransport] whose far end is a [VirtualSennheiser]. */
class VirtualSennheiserTransport(private val unit: VirtualSennheiser) : ByteTransport {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val frames = GaiaFrameBuffer()

    override var isConnected: Boolean = true
    override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        for (frame in frames.feed(bytes)) unit.answer(frame).forEach { inbound.trySend(it.encode()) }
    }

    /** Push a frame as the headset would (a button press on the earcup, battery change). */
    fun push(frame: GaiaFrame) {
        inbound.trySend(frame.encode())
    }

    override fun close() {
        isConnected = false
        inbound.close()
    }
}

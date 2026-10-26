package dev.earshot.service

import dev.earshot.bt.JblConnection
import dev.earshot.bt.JblLinkFactory
import dev.earshot.bt.JblLog
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.jbl.JblCommands as C
import dev.earshot.protocol.jbl.JblFrame
import dev.earshot.protocol.jbl.JblModel
import dev.earshot.protocol.jbl.JblStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One connected JBL headset on H-AA, Android-free so the tests run it against a scripted transport
 * (and, in the end-to-end tests, the virtual headset). The shape of [SennheiserSession], with one
 * difference that drives everything here: H-AA has no request ids, no feature list and no error
 * replies. A reply is just the next frame of the right kind, a pushed change looks the same, and a
 * headset without a feature stays silent. So:
 *  - every frame the headset sends is folded into [status] in arrival order ([JblConnection.attach]),
 *    replies and pushes alike;
 *  - [start] sends the push registration, then probes each feature once; a feature that never
 *    answered is neither shown nor written ([answered]);
 *  - a command writes, then reads back what it touched, so the screen shows what the headset did.
 *
 * Commands are serialised by the caller (the repository's mutex); the connection allows one request
 * in flight anyway.
 */
class JblSession(
    val conn: JblConnection,
    /** The Bluetooth name: names the model until the device info gives its product id. */
    private val deviceName: String?,
) {
    private val _status = MutableStateFlow(JblStatus())
    val status: StateFlow<JblStatus> = _status.asStateFlow()

    @Volatile
    private var probed: Set<JblFeature> = emptySet()

    /** The product id the headset reports wins; its name must match a model exactly (JBL names collide across stacks). */
    val model: JblModel? get() = JblModel.fromPid(_status.value.info?.productId) ?: JblModel.fromName(deviceName)

    /** The features that answered a probe, plus anything the headset pushed by itself. */
    val answered: Set<JblFeature> get() = probed + JblStateMapping.reported(_status.value)

    init {
        // Replays what came before the session (the device info the headset sends by itself), then follows every frame.
        conn.attach { frame -> _status.update { it.fold(frame) } }
    }

    // ---- Connect --------------------------------------------------------------------------

    suspend fun start() {
        // A real link was probed by the factory; a demo link was adopted as it is.
        if (conn.deviceInfo == null && JblLinkFactory.probe(conn) == null) {
            throw HeadphoneCommandException("The headphones didn't answer")
        }
        conn.send(C.notifyOn())
        // jbl-control waits a moment before its first read: the headset is still sending what the registration set off.
        delay(NOTIFY_SETTLE_MS)
        readAll()
    }

    /** Every probe once, in the order jbl-control and the app read them. A feature that stays silent costs one timeout. */
    suspend fun readAll() {
        probe(JblFeature.MODES, C.modeGet(), attempts = 2, answers = ::isModeStatus)
        probe(JblFeature.LEVEL, C.levelGet(), answers = ::isManualLevel)
        readTlv(C.INFO_READ_FIDS, attempts = 2)
        probe(JblFeature.EQ, C.eqGet(), answers = ::isEq)
        probe(JblFeature.VOICE_PROMPTS, C.voicePromptsGet(), answers = ::isVoicePrompts)
        // Older earbuds have no battery in the TLV; their own read is `AA 25`. Known headphones never do.
        val s = _status.value
        val noTlvBattery = s.left == null && s.right == null && s.headband == null
        if (noTlvBattery && model?.earbuds != false) readTwsBattery()
    }

    /**
     * The battery from whichever read this headset answers: the TLV ids, else the older TWS read,
     * else the device info (headphones whose only figure is its battery byte).
     */
    suspend fun readBattery() {
        val s = _status.value
        when {
            s.infoIds.any { it in C.BATTERY_FIDS } -> readTlv(C.BATTERY_FIDS)
            JblFeature.TWS_BATTERY in answered -> readTwsBattery()
            s.info?.battery != null -> probe(null, C.deviceInfoGet()) { it.command == C.DEVICE_INFO }
            else -> readTlv(C.BATTERY_FIDS)
        }
    }

    // ---- Commands -------------------------------------------------------------------------

    /**
     * Switch to an Earshot slot ([JblStateMapping.SLOTS]). The headset answers with its new mode
     * (`91 12`, the same frame a push is). A pair of earbuds takes a mode only with both buds in, and
     * says so by reporting the old one.
     */
    suspend fun setMode(slotIndex: Int) {
        val slot = JblStateMapping.slot(slotIndex)
            ?: throw HeadphoneCommandException("JBL headphones have three listening modes and off, not a mode in slot $slotIndex")
        val listed = _status.value.modes ?: throw HeadphoneCommandException("These headphones didn't report their listening modes")
        if (slot.wire != null && slot.wire !in listed) throw HeadphoneCommandException("These headphones don't have ${slot.name}")
        JblLog.i("mode → ${slot.name}")
        val reply = conn.request(C.modeSet(listed, slot.wire), attempts = 1, answers = ::isModeStatus)
        // Nothing came back: ask, so a change that worked without a report still shows.
        if (reply == null && conn.request(C.modeGet(), attempts = 2, answers = ::isModeStatus) == null) {
            throw HeadphoneCommandException("The headphones didn't confirm the mode change")
        }
        val now = JblStateMapping.slotOfActive(_status.value.activeMode)
        if (now != slot) {
            val hint = if (JblStateMapping.isEarbuds(_status.value, model)) " (JBL earbuds take that mode only with both buds in)" else ""
            throw HeadphoneCommandException("The headphones stayed in ${now?.name ?: "their current mode"}$hint")
        }
    }

    suspend fun setVendorControl(id: String, value: Int) {
        if (JblStateMapping.controls(_status.value, answered).none { it.id == id }) {
            throw HeadphoneCommandException("These JBL headphones have no setting \"$id\"")
        }
        val frame = JblStateMapping.write(id, value)
            ?: throw HeadphoneCommandException("That isn't a value these JBL headphones take for \"$id\"")
        JblLog.i("$id = $value")
        conn.send(frame)
        // Read back what the write touched. A read that disagrees is logged, not thrown: the headset may simply
        // apply the change a moment later, and the state published afterwards is what it reports either way.
        val agreed = when (id) {
            JblStateMapping.ANC_LEVEL -> {
                probe(JblFeature.LEVEL, C.levelGet(), answers = ::isManualLevel)
                _status.value.ancLevel == value
            }
            JblStateMapping.EQ_PRESET -> {
                probe(JblFeature.EQ, C.eqGet(), answers = ::isEq)
                _status.value.eqCategory == C.EQ_PRESETS[value].category
            }
            else -> {
                probe(JblFeature.VOICE_PROMPTS, C.voicePromptsGet(), answers = ::isVoicePrompts)
                _status.value.voicePrompts == (value == 1)
            }
        }
        if (!agreed) JblLog.w("$id: the headset reports another value after the write")
    }

    // ---- Helpers --------------------------------------------------------------------------

    private suspend fun readTlv(fids: List<Int>, attempts: Int = 1) {
        // The call-status push is a TLV return too: only an answer carrying an id we asked for is ours.
        probe(JblFeature.TLV, C.infoGet(fids), attempts) { f -> f.command == C.INFO && f.sub == TLV_RETURN && tlvIds(f).any { it in fids } }
    }

    private suspend fun readTwsBattery() {
        probe(JblFeature.TWS_BATTERY, C.batteryGet()) { it.command == C.BATTERY }
    }

    /** One read folded into [status] by [JblConnection.attach]; a silent headset leaves the feature unknown. A dead link throws. */
    private suspend fun probe(feature: JblFeature?, frame: JblFrame, attempts: Int = 1, answers: (JblFrame) -> Boolean): JblFrame? {
        val reply = conn.request(frame, attempts = attempts, answers = answers)
        if (reply == null) JblLog.d("no answer to ${C.name(frame.command)}") else if (feature != null) probed += feature
        return reply
    }

    private fun isModeStatus(f: JblFrame) = f.command == C.LISTENING && f.sub == C.MODE_STATUS

    private fun isManualLevel(f: JblFrame) =
        f.command == C.LISTENING && f.sub == C.LEVEL_STATUS && (1 until f.size - 1 step 2).any { f[it] == C.LEVEL_MANUAL_ANC }

    /** The EQ return, not an echo of the read (its sub-command is the read's own, 01). */
    private fun isEq(f: JblFrame) = f.command == C.EQ && f.size >= C.EQ_HEADER_SIZE && f.sub != C.EQ_GET

    private fun isVoicePrompts(f: JblFrame) = f.command == C.VOICE_PROMPTS && f.sub == VOICE_PROMPTS_RETURN

    /** The ids in a TLV return: `[marker][fid u16 BE][len][value]…`. */
    private fun tlvIds(f: JblFrame): Set<Int> {
        val ids = mutableSetOf<Int>()
        var i = 1
        while (i + 3 <= f.size) {
            ids += (f[i] shl 8) or f[i + 1]
            i += 3 + f[i + 2]
        }
        return ids
    }

    companion object {
        /** jbl-control pauses this long after the push registration before its first read. */
        const val NOTIFY_SETTLE_MS = 300L

        /** The marker byte of a TLV return and the sub-command of a voice-prompt state. */
        private const val TLV_RETURN = 0x02
        private const val VOICE_PROMPTS_RETURN = 0x05
    }
}

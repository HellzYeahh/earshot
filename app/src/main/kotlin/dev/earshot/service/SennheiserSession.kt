package dev.earshot.service

import dev.earshot.bt.SennheiserConnection
import dev.earshot.bt.SennheiserLog
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.NoiseSettings
import dev.earshot.protocol.gaia.GaiaCommand
import dev.earshot.protocol.gaia.GaiaDiscovery
import dev.earshot.protocol.gaia.GaiaError
import dev.earshot.protocol.gaia.GaiaFrame
import dev.earshot.protocol.gaia.GaiaReply
import dev.earshot.protocol.gaia.GaiaVendor
import dev.earshot.protocol.sennheiser.SennheiserCommands as C
import dev.earshot.protocol.sennheiser.SennheiserFeature
import dev.earshot.protocol.sennheiser.SennheiserModel
import dev.earshot.protocol.sennheiser.SennheiserNoiseFamily
import dev.earshot.protocol.sennheiser.SennheiserSetting
import dev.earshot.protocol.sennheiser.SennheiserStatus
import dev.earshot.protocol.sennheiser.SennheiserWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One connected Sennheiser headset, Android-free so the end-to-end tests run it against a
 * [dev.earshot.virtual.VirtualSennheiser]:
 *  - [start]: Qualcomm API version, the Sennheiser feature list (the write guard opens up only for
 *    what it reports), Qualcomm's list (logged), model string and firmware, push registration for
 *    battery, ANC, transparency, wear, audio and EQ, then one read of everything the model has;
 *  - [onPush]: notifications (and the stray responses a registration triggers) fold into [status];
 *  - commands write, then re-read what they touched. Noise writes have side effects on the
 *    Momentum 4 (ANC on switches transparency off and the level to 0; transparency on sets the
 *    level to 100 and off restores it; any mode-table write switches transparency off), so after
 *    every noise write the whole noise group is read back rather than predicted.
 *
 * Commands are serialised by the caller (the repository's mutex); the connection allows one
 * request in flight anyway.
 */
class SennheiserSession(
    val conn: SennheiserConnection,
    /** The Bluetooth name: the model until `0x1206` names it exactly. */
    private val name: String?,
) {
    private val _status = MutableStateFlow(SennheiserStatus())
    val status: StateFlow<SennheiserStatus> = _status.asStateFlow()

    val model: SennheiserModel? get() = _status.value.model ?: SennheiserModel.fromName(name)

    private val family: SennheiserNoiseFamily get() = SennheiserStateMapping.family(model)

    // ---- Connect --------------------------------------------------------------------------

    suspend fun start() {
        val api = runCatching { GaiaDiscovery.apiVersion(conn, SennheiserConnection.DEFAULT_TIMEOUT_MS) }.getOrNull()
        val features = runCatching { GaiaDiscovery.features(conn, C.VENDOR, SennheiserConnection.SLOW_TIMEOUT_MS) }
            .onFailure { SennheiserLog.w("feature list failed: ${it.message}") }
            .getOrNull()
        if (features == null) {
            // Without the list the guard lets nothing else out: the headset stays read-nothing.
            SennheiserLog.w("no Sennheiser feature list (GAIA API $api): nothing else will be sent")
            return
        }
        conn.features = features
        _status.update { it.copy(features = features) }
        val qualcomm = runCatching { GaiaDiscovery.features(conn, GaiaVendor.QUALCOMM, SennheiserConnection.DEFAULT_TIMEOUT_MS) }.getOrNull()
        _status.update { it.copy(qualcommFeatures = qualcomm) }
        SennheiserLog.i("GAIA API $api, features ${features.describe()}, Qualcomm ${qualcomm?.describe() ?: "none"}")

        if (SennheiserFeature.VERSIONS in features) {
            read(C.MODEL_ID_GET, timeoutMs = SennheiserConnection.SLOW_TIMEOUT_MS)
            read(C.FIRMWARE_GET, timeoutMs = SennheiserConnection.SLOW_TIMEOUT_MS)
        }
        SennheiserLog.i("model ${_status.value.modelId ?: "unknown"} (${model?.name ?: "not driven"}), firmware ${_status.value.firmware ?: "unknown"}")

        for (feature in SennheiserFeature.NOTIFY) {
            if (feature !in features) continue
            if (feature == SennheiserFeature.USER_EQ && !_status.value.eqGen2) continue
            runCatching { conn.call(C.REGISTER_NOTIFICATION, byteArrayOf(feature.toByte())) }
                .onFailure { SennheiserLog.w("push registration for feature $feature failed: ${it.message}") }
        }
        readAll()
    }

    /** Everything the model has, one GET each; a refused one stays unknown. */
    suspend fun readAll() {
        readBattery()
        if (has(SennheiserFeature.BATTERY)) read(C.CHARGING_GET)
        readNoise()
        val settings = model?.settings.orEmpty()
        if (SennheiserSetting.PAUSE_IN_TRANSPARENCY in settings || model == null) readIf(SennheiserFeature.TRANSPARENT_HEARING, C.TH_BEHAVIOUR_GET)
        if (SennheiserSetting.ON_HEAD in settings) readIf(SennheiserFeature.DEVICE, C.ON_HEAD_GET)
        if (SennheiserSetting.SMART_PAUSE in settings) readIf(SennheiserFeature.GENERIC_AUDIO, C.SMART_PAUSE_GET)
        if (SennheiserSetting.AUTO_ANSWER in settings) readIf(SennheiserFeature.GENERIC_AUDIO, C.AUTO_ANSWER_GET)
        if (SennheiserSetting.COMFORT_CALL in settings) readIf(SennheiserFeature.GENERIC_AUDIO, C.COMFORT_CALL_GET)
        if (SennheiserSetting.SIDETONE in settings) readIf(SennheiserFeature.GENERIC_AUDIO, C.SIDETONE_GET)
        readIf(SennheiserFeature.BATTERY, C.AUTO_OFF_GET, SennheiserWire.AUTO_OFF_TIMER)
        if (_status.value.eqGen2) {
            read(C.EQ_CONFIG_GET)
            read(C.EQ_ALL_GET, byteArrayOf(0))
            read(C.EQ_SUBMODE_GET)
        }
    }

    suspend fun readBattery() {
        readIf(SennheiserFeature.BATTERY, C.BATTERY_GET, timeoutMs = SennheiserConnection.SLOW_TIMEOUT_MS)
    }

    /** The whole noise group: what every noise write can have changed. */
    suspend fun readNoise() {
        readIf(SennheiserFeature.ANC, C.ANC_GET)
        readIf(SennheiserFeature.TRANSPARENT_HEARING, C.TH_GET)
        when (family) {
            SennheiserNoiseFamily.SLIDER -> {
                readIf(SennheiserFeature.ANC, C.ANC_LEVEL_GET)
                readIf(SennheiserFeature.ANC, C.ANC_MODE_GET)
            }
            SennheiserNoiseFamily.EARBUDS -> {
                readIf(SennheiserFeature.ANC, C.ANC_MODE_GET)
                readIf(SennheiserFeature.TRANSPARENT_HEARING, C.TH_LEVEL_GET)
            }
            // The Accentum's mode table reads zeros and ignores writes: not read.
            SennheiserNoiseFamily.TOGGLES -> Unit
        }
    }

    fun onPush(frame: GaiaFrame) {
        if (frame.vendor != C.VENDOR) return
        _status.update { it.apply(frame.command, frame.payload) }
    }

    // ---- Commands -------------------------------------------------------------------------

    /**
     * Three stand-in modes ([SennheiserStateMapping.MODES]):
     *  - noise cancelling: transparency off first if on (off restores the level on a Momentum 4), then ANC on;
     *  - transparency: headbands need ANC on for it to do anything (Momentum 4 capture), so ANC on first; then transparency on;
     *  - off: transparency off if on, then ANC off.
     */
    suspend fun setMode(index: Int) {
        requireNoise()
        val s = _status.value
        when (index) {
            SennheiserStateMapping.MODE_ANC -> {
                if (s.transparencyOn == true) write(C.TH_SET, 0)
                write(C.ANC_SET, 1)
            }
            SennheiserStateMapping.MODE_TRANSPARENCY -> {
                if (!has(SennheiserFeature.TRANSPARENT_HEARING)) throw HeadphoneCommandException("These headphones have no transparency mode")
                if (family != SennheiserNoiseFamily.EARBUDS && s.ancOn != true) write(C.ANC_SET, 1)
                write(C.TH_SET, 1)
            }
            SennheiserStateMapping.MODE_OFF -> {
                if (s.transparencyOn == true) write(C.TH_SET, 0)
                write(C.ANC_SET, 0)
            }
            else -> throw HeadphoneCommandException("Sennheiser headphones have three modes: noise cancelling, transparency and off")
        }
        readNoise()
    }

    /** The noise card: ANC on/off and, on the slider models, the ANC↔transparency level. */
    suspend fun setNoise(settings: NoiseSettings) {
        requireNoise()
        val before = _status.value
        val ancChanged = settings.ancEnabled != before.ancOn
        if (ancChanged) write(C.ANC_SET, if (settings.ancEnabled) 1 else 0)
        if (family == SennheiserNoiseFamily.SLIDER && settings.ancEnabled) {
            val level = SennheiserWire.levelFromCancelling(settings.cancellingLevel)
            // ANC on resets the level to 0 on a Momentum 4: write the wanted level after it.
            val current = if (ancChanged) 0 else before.ancLevel
            if (level != current) write(C.ANC_LEVEL_SET, level)
        }
        readNoise()
    }

    suspend fun setVendorControl(id: String, value: Int) {
        val w = SennheiserStateMapping.write(id, value, _status.value, model)
            ?: throw HeadphoneCommandException("These Sennheiser headphones have no setting \"$id\" with that value")
        SennheiserLog.i("$id → ${C.name(w.command)} = $value")
        conn.call(w.command, w.payload)
        for (cmd in w.reread) read(cmd, if (cmd == C.AUTO_OFF_GET) SennheiserWire.AUTO_OFF_TIMER else ByteArray(0))
        if (w.touchesNoise) readNoise()
    }

    /** The three-band EQ onto the headset's bands (generation 2 only); then all gains are read back. */
    suspend fun setEq(current: HeadphoneEq?, target: HeadphoneEq) {
        val s = _status.value
        if (!s.eqGen2 || s.eqConfig == null) throw HeadphoneCommandException(EQ_UNSUPPORTED)
        for (payload in SennheiserStateMapping.eqWrites(s, current, target)) {
            when (val r = conn.request(C.VENDOR, C.EQ_BAND_SET, payload)) {
                is GaiaReply.Ok -> Unit
                // Smart Control treats "incorrect state" on an EQ write as success (the value was already set).
                is GaiaReply.Error -> if (r.code != GaiaError.INCORRECT_STATE) throw SennheiserConnection.refusedBy(C.EQ_BAND_SET, r.code)
            }
        }
        read(C.EQ_ALL_GET, byteArrayOf(0))
    }

    // ---- Helpers --------------------------------------------------------------------------

    private fun has(feature: Int) = _status.value.has(feature)

    private fun requireNoise() {
        if (!has(SennheiserFeature.ANC) && !has(SennheiserFeature.TRANSPARENT_HEARING)) {
            throw HeadphoneCommandException("These headphones didn't report any noise control")
        }
    }

    private suspend fun write(command: Int, value: Int) {
        SennheiserLog.i("write ${C.name(command)} = $value")
        conn.call(command, byteArrayOf(value.toByte()))
    }

    private suspend fun readIf(feature: Int, command: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = SennheiserConnection.DEFAULT_TIMEOUT_MS) {
        if (has(feature)) read(command, payload, timeoutMs)
    }

    /** One GET folded into the status; a refusal leaves the value unknown (logged), a dead link throws. */
    private suspend fun read(command: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = SennheiserConnection.DEFAULT_TIMEOUT_MS) {
        try {
            val reply = conn.call(command, payload, timeoutMs)
            _status.update { it.apply(GaiaCommand.response(command), reply) }
        } catch (e: HeadphoneCommandException) {
            if (!conn.isOpen) throw e
            SennheiserLog.d("read ${C.name(command)} failed: ${e.message}")
        }
    }

    private fun Map<Int, Int>.describe(): String = entries.joinToString(" ") { "${it.key}v${it.value}" }

    companion object {
        const val EQ_UNSUPPORTED = "These headphones' EQ needs newer firmware: update them with Sennheiser's app first"
    }
}

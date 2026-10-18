package dev.earshot.service

import dev.earshot.bt.BragiConnection
import dev.earshot.bt.BragiRequester
import dev.earshot.bt.BragiSession
import dev.earshot.core.CrusherBass
import dev.earshot.core.DeviceInfo
import dev.earshot.core.HeadphoneCapabilities
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.NoiseSettings
import dev.earshot.core.SecureSession
import dev.earshot.core.SecureSessionStatus
import dev.earshot.core.Sidetone
import dev.earshot.core.SpatialAudio
import dev.earshot.protocol.bragi.BosMessageType
import dev.earshot.protocol.bragi.BosRpcPacket
import dev.earshot.protocol.bragi.BragiModel
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.bragi.Crusher
import dev.earshot.protocol.bragi.DeviceInfo as WireDeviceInfo
import dev.earshot.protocol.bragi.Hearing
import dev.earshot.protocol.bragi.HearingMode
import dev.earshot.protocol.bragi.SpatialAudio as WireSpatialAudio
import dev.earshot.protocol.bragi.SpatialMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns Earshot's commands into BOS-RPC calls for the Skullcandy Crusher 1080 ANC and maps the
 * replies back into [HeadphoneState]. The BMAP counterpart is [QcUltra2Protocol]; the two do not
 * share a wire format, so this is a sibling rather than a [HeadphoneProtocol].
 *
 * How the Crusher's features fit Earshot's model:
 *  - **Listening modes**: three built-in, non-editable modes, `0 Quiet` (ANC), `1 Aware` (audio
 *    transparency), `2 Off`. The order lines up with the Bose models (slot 0 Quiet, slot 1 Aware), so
 *    automation rules, the tile and the widget mean the same thing on either stack. Switching is
 *    `hearing.mode_configure {mode, activate}`.
 *  - **Noise slider**: the gain of the active mode, scaled to Earshot's 0..10. There is no separate
 *    ANC switch (Off is a mode) and no Wind Block.
 *  - **Immersive Audio**: Bose TrueSpatial Off / Still / Motion. Mapped by label: the Crusher's
 *    "Still" becomes [SpatialAudio.ROOM] (label "Still") and "Motion" becomes [SpatialAudio.HEAD]
 *    (label "Motion"), the same labels the QC Ultra shows.
 *  - **Sidetone**: on/off plus a volume 0..max, folded into Off / Low / Medium / High thirds.
 *  - **Crusher bass**: [CrusherBass], a new capability (active, intensity, three feels).
 *
 * Reads are tolerant: a call the firmware refuses leaves that field as it was. Subscriptions
 * (`subscribe = true`) are made once per connection in [onConnected]; later refreshes only read.
 */
class CrusherProtocol {

    @Volatile private var ancGainMax = 10
    @Volatile private var atGainMax = 10
    @Volatile private var sidetoneMax = 5
    @Volatile private var crusherConfig: Crusher.Config? = null
    @Volatile private var lastNonOffMode = HearingMode.ANC

    /** The `authenticated_access` flag from `dev_info`: the secure session is required for protected calls. */
    @Volatile var authenticatedAccess: Boolean = false
        private set

    // ── Connect ──────────────────────────────────────────────────────────────────────────

    /**
     * Identity, the secure-session flag and the static ranges; subscribes to live changes. When the
     * headphones ask for a secure session ([authenticatedAccess]) it is set up first through [secure]
     * (the official app also carries on in the clear if that fails, so a failure is recorded in
     * [HeadphoneState.secureSession] and the reads that follow simply go as far as they can).
     */
    suspend fun onConnected(
        c: BragiRequester, current: HeadphoneState, secure: (suspend () -> BragiSession.Outcome)? = null,
    ): HeadphoneState {
        var s = current.copy(capabilities = CAPABILITIES, livePushEnabled = false, secureSession = SecureSession(), pairing = null)
        optional(c) { s = withDevInfo(s, WireDeviceInfo.parseDevInfo(c.call(BragiServices.DEV_INFO, WireDeviceInfo.devInfo()).payload)) }
        if (authenticatedAccess) {
            val outcome = secure?.invoke() ?: BragiSession.Outcome.Failed("This build cannot set up the secure session")
            s = s.copy(secureSession = outcome.toSession(), pairing = null)
        }
        optional(c) {
            Hearing.parseModeInfo(c.call(BragiServices.HEARING_MODE_INFO).payload).let { ancGainMax = it.ancGainMax.coerceAtLeast(1); atGainMax = it.atGainMax.coerceAtLeast(1) }
        }
        optional(c) { sidetoneMax = Hearing.parseSidetoneInfo(c.call(BragiServices.SIDETONE_INFO).payload).coerceAtLeast(1) }
        optional(c) { crusherConfig = Crusher.parseConfig(c.call(BragiServices.CRUSHER_CONFIG_GET).payload) }
        return read(c, s, subscribe = true)
    }

    suspend fun refresh(c: BragiRequester, current: HeadphoneState): HeadphoneState = read(c, current, subscribe = false)

    /** Battery has no push in the definitions, so it is polled (the repository calls this every minute). */
    suspend fun pollBattery(c: BragiRequester, current: HeadphoneState): HeadphoneState {
        var s = current
        optional(c) { s = withBattery(s, c.call(BragiServices.BATT_INFO).payload) }
        return s
    }

    private suspend fun read(c: BragiRequester, current: HeadphoneState, subscribe: Boolean): HeadphoneState {
        var s = current
        optional(c) { s = withBattery(s, c.call(BragiServices.BATT_INFO).payload) }
        optional(c) { s = withMode(s, c.call(BragiServices.MODE_READ, Hearing.modeRead(subscribe), streaming = true).payload) }
        optional(c) { s = withSidetone(s, c.call(BragiServices.SIDETONE_READ, Hearing.sidetoneRead(subscribe), streaming = true).payload) }
        optional(c) { s = withSpatial(s, c.call(BragiServices.SPATIAL_INFO_GET, WireSpatialAudio.infoGet(subscribe), streaming = true).payload) }
        optional(c) { s = withCrusher(s, c.call(BragiServices.CRUSHER_STATE_GET, Crusher.stateGet(subscribe), streaming = true).payload) }
        return s
    }

    /**
     * A read that the firmware may refuse or that times out: log and carry on. A lost link is not
     * skippable: it is rethrown so the repository can close the session instead of reporting Connected.
     */
    private inline fun <T> optional(c: BragiRequester, block: () -> T) {
        try {
            block()
        } catch (e: HeadphoneCommandException) {
            if (!c.isOpen || e.message == BragiConnection.MESSAGE_STOPPED_ANSWERING) throw e
            ProtocolLog.d("Crusher read skipped: ${e.message}")
        } catch (e: IllegalArgumentException) {
            ProtocolLog.w("Crusher reply did not parse: ${e.message}")
        }
    }

    // ── Commands ─────────────────────────────────────────────────────────────────────────

    /** [index]: 0 Quiet, 1 Aware, 2 Off (the Bose order for the first two). */
    suspend fun setMode(c: BragiRequester, current: HeadphoneState, index: Int): HeadphoneState {
        val mode = modeForIndex(index) ?: throw HeadphoneCommandException("There is no listening mode in slot $index")
        c.call(BragiServices.MODE_CONFIGURE, Hearing.modeConfigure(mode, activate = true))
        if (mode != HearingMode.OFF) lastNonOffMode = mode
        return current.copy(currentModeIndex = index, noise = current.modes.firstOrNull { it.index == index }?.settings?.copy(spatialAudio = current.noise?.spatialAudio ?: SpatialAudio.OFF) ?: current.noise)
    }

    /**
     * Apply [settings] against the current state: spatial audio if it changed, the active mode's
     * gain if the level changed, and off/on if `ancEnabled` changed (automations send it). Wind
     * Block has no equivalent and is ignored.
     */
    suspend fun setNoise(c: BragiRequester, current: HeadphoneState, settings: NoiseSettings): HeadphoneState {
        val before = current.noise ?: NoiseSettings()
        var s = current
        if (settings.spatialAudio != before.spatialAudio) {
            c.call(BragiServices.SPATIAL_MODE_SET, WireSpatialAudio.setMode(settings.spatialAudio.toWire()))
            s = s.copy(noise = (s.noise ?: before).copy(spatialAudio = settings.spatialAudio))
        }
        if (settings.ancEnabled != before.ancEnabled) {
            val target = if (settings.ancEnabled) lastNonOffMode else HearingMode.OFF
            c.call(BragiServices.MODE_CONFIGURE, Hearing.modeConfigure(target, activate = true))
            s = s.copy(currentModeIndex = indexForMode(target), noise = (s.noise ?: before).copy(ancEnabled = settings.ancEnabled))
        }
        if (settings.cancellingLevel != before.cancellingLevel) {
            val active = activeMode(s)
            if (active == HearingMode.OFF) throw HeadphoneCommandException("Switch to Quiet or Aware to adjust the level")
            val max = if (active == HearingMode.ANC) ancGainMax else atGainMax
            c.call(BragiServices.MODE_CONFIGURE, Hearing.modeConfigure(active, activate = false, gain = levelToGain(settings.cancellingLevel, max)))
            s = s.copy(noise = (s.noise ?: before).copy(cancellingLevel = settings.cancellingLevel))
        }
        return s
    }

    /** [persistent] is ignored: the headphones always keep the sidetone setting until a factory reset. */
    suspend fun setSidetone(c: BragiRequester, current: HeadphoneState, level: Sidetone): HeadphoneState {
        val body = if (level == Sidetone.OFF) Hearing.sidetoneConfigure(enabled = false)
        else Hearing.sidetoneConfigure(enabled = true, volume = volumeForSidetone(level))
        c.call(BragiServices.SIDETONE_CONFIGURE, body)
        return current.copy(sidetone = level, sidetonePersistent = true)
    }

    /** Any argument left null stays as it is on the headphones. */
    suspend fun setCrusherBass(c: BragiRequester, current: HeadphoneState, active: Boolean?, intensity: Int?, mode: Int?): HeadphoneState {
        val cfg = crusherConfig
        val before = current.crusher ?: throw HeadphoneCommandException("These headphones have no Crusher bass")
        if (intensity != null && intensity !in 0..before.maxIntensity) throw HeadphoneCommandException("Crusher intensity must be 0..${before.maxIntensity}")
        if (mode != null && before.modeCount > 0 && mode !in 0 until before.modeCount) throw HeadphoneCommandException("Crusher feel must be 0..${before.modeCount - 1}")
        val reply = c.call(BragiServices.CRUSHER_STATE_SET, Crusher.stateSet(active, intensity, mode))
        // The reply carries the applied state; an intensity the unit ignores (read-only dial) shows here.
        val applied = if (reply.payload.isNotEmpty()) Crusher.parseState(reply.payload) else null
        return current.copy(crusher = before.copy(
            active = applied?.isActive ?: active ?: before.active,
            intensity = applied?.intensity ?: intensity ?: before.intensity,
            mode = applied?.mode ?: mode ?: before.mode,
            intensityReadOnly = cfg?.intensityReadOnly ?: before.intensityReadOnly,
        ))
    }

    // ── Pushes ───────────────────────────────────────────────────────────────────────────

    /** A frame nobody asked for: a subscription update. Null when it is irrelevant. */
    fun onUnsolicited(packet: BosRpcPacket, current: HeadphoneState): HeadphoneState? {
        if (packet.type != BosMessageType.RESPONSE) return null
        return try {
            when (packet.address) {
                BragiServices.MODE_READ -> withMode(current, packet.payload)
                BragiServices.SIDETONE_READ -> withSidetone(current, packet.payload)
                BragiServices.SPATIAL_INFO_GET -> withSpatial(current, packet.payload)
                BragiServices.CRUSHER_STATE_GET -> withCrusher(current, packet.payload)
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            ProtocolLog.w("ignoring malformed Crusher push $packet: ${e.message}")
            null
        }
    }

    private fun BragiSession.Outcome.toSession() = when (this) {
        is BragiSession.Outcome.Secure -> SecureSession(SecureSessionStatus.SECURE)
        BragiSession.Outcome.Declined -> SecureSession(SecureSessionStatus.DECLINED, "Pairing was cancelled")
        is BragiSession.Outcome.Failed -> SecureSession(SecureSessionStatus.FAILED, reason)
    }

    // ── State mapping ────────────────────────────────────────────────────────────────────

    private fun withDevInfo(s: HeadphoneState, d: WireDeviceInfo.DevInfoResponse): HeadphoneState {
        authenticatedAccess = d.authenticatedAccess
        val model = BragiModel.fromModelNumber(d.modelNumber) ?: BragiModel.SKULLCANDY_CRUSHER_1080
        return s.copy(info = (s.info ?: DeviceInfo()).copy(
            productName = d.model, firmwareVersion = d.firmware, serialNumber = d.serial, modelName = model.displayName,
            bmapVersion = d.bosVersion?.let { "BOS $it" },
        ))
    }

    private fun withBattery(s: HeadphoneState, body: ByteArray): HeadphoneState {
        val own = WireDeviceInfo.parseBattery(body).firstOrNull() ?: return s
        return s.copy(batteryPercent = own.level.coerceIn(0, 100), isCharging = own.status == WireDeviceInfo.BatteryStatus.CHARGING)
    }

    private fun withMode(s: HeadphoneState, body: ByteArray): HeadphoneState {
        val m = Hearing.parseModeState(body)
        if (m.active == HearingMode.UNSPECIFIED) return s
        if (m.active != HearingMode.OFF) lastNonOffMode = m.active
        val spatial = s.noise?.spatialAudio ?: SpatialAudio.OFF
        val ancLevel = gainToLevel(m.ancGain, ancGainMax)
        val atLevel = gainToLevel(m.atGain, atGainMax)
        fun mode(index: Int, name: String, level: Int, enabled: Boolean) = ListeningMode(
            index = index, name = name, isPreset = true, isEditable = false, isConfigured = true,
            isFavorite = false, settings = NoiseSettings(level, enabled, false, spatial),
        )
        val modes = listOf(mode(0, "Quiet", ancLevel, true), mode(1, "Aware", atLevel, true), mode(2, "Off", ancLevel, false))
        val activeIndex = indexForMode(m.active)
        val level = when (m.active) { HearingMode.AT -> atLevel; else -> ancLevel }
        return s.copy(modes = modes, currentModeIndex = activeIndex, noise = NoiseSettings(level, m.active != HearingMode.OFF, false, spatial))
    }

    private fun withSidetone(s: HeadphoneState, body: ByteArray): HeadphoneState {
        val st = Hearing.parseSidetoneState(body)
        return s.copy(sidetone = sidetoneLevel(st.enabled, st.volume), sidetonePersistent = true)
    }

    private fun withSpatial(s: HeadphoneState, body: ByteArray): HeadphoneState {
        val info = WireSpatialAudio.parseInfo(body)
        return s.copy(noise = (s.noise ?: NoiseSettings()).copy(spatialAudio = info.mode.toCore()))
    }

    private fun withCrusher(s: HeadphoneState, body: ByteArray): HeadphoneState {
        val st = Crusher.parseState(body)
        val cfg = crusherConfig
        return s.copy(crusher = CrusherBass(
            active = st.isActive, intensity = st.intensity, maxIntensity = cfg?.maxIntensity ?: s.crusher?.maxIntensity ?: 10,
            mode = st.mode, modeCount = cfg?.modeCount ?: s.crusher?.modeCount ?: 0,
            intensityReadOnly = cfg?.intensityReadOnly ?: false,
        ))
    }

    // ── Conversions ──────────────────────────────────────────────────────────────────────

    private fun modeForIndex(i: Int) = when (i) { 0 -> HearingMode.ANC; 1 -> HearingMode.AT; 2 -> HearingMode.OFF; else -> null }
    private fun indexForMode(m: HearingMode) = when (m) { HearingMode.ANC -> 0; HearingMode.AT -> 1; HearingMode.OFF -> 2; else -> 2 }
    private fun activeMode(s: HeadphoneState): HearingMode = modeForIndex(s.currentModeIndex ?: 2) ?: HearingMode.OFF

    private fun gainToLevel(gain: Int, max: Int): Int = if (max <= 0) 0 else (gain * 10.0 / max).roundToInt().coerceIn(0, 10)
    private fun levelToGain(level: Int, max: Int): Int = (level * max / 10.0).roundToInt().coerceIn(0, max)

    /** The level whose volume is nearest, so what [volumeForSidetone] writes always reads back as the same level. */
    private fun sidetoneLevel(enabled: Boolean, volume: Int): Sidetone {
        if (!enabled) return Sidetone.OFF
        return listOf(Sidetone.LOW, Sidetone.MEDIUM, Sidetone.HIGH).minByOrNull { abs(volumeForSidetone(it) - volume) } ?: Sidetone.MEDIUM
    }

    private fun volumeForSidetone(level: Sidetone): Int = when (level) {
        Sidetone.LOW -> (sidetoneMax / 3.0).roundToInt().coerceAtLeast(1)
        Sidetone.MEDIUM -> (sidetoneMax * 2 / 3.0).roundToInt().coerceAtLeast(1)
        Sidetone.HIGH -> sidetoneMax
        Sidetone.OFF -> 0
    }

    private fun SpatialAudio.toWire() = when (this) { SpatialAudio.OFF -> SpatialMode.OFF; SpatialAudio.ROOM -> SpatialMode.STILL; SpatialAudio.HEAD -> SpatialMode.MOTION }
    private fun SpatialMode.toCore() = when (this) { SpatialMode.STILL -> SpatialAudio.ROOM; SpatialMode.MOTION -> SpatialAudio.HEAD; else -> SpatialAudio.OFF }

    /** One read the Lab may issue: a call with no side effects. [streaming] reads answer with the current state. */
    class LabRead(val address: BragiServices.Address, val name: String, val streaming: Boolean = false, val body: ByteArray = ByteArray(0))

    companion object {
        /**
         * Every read-only call the Lab offers, with its name. Nothing here changes a setting: the
         * streaming reads are issued without the subscribe flag, so they return the current state once.
         */
        val LAB_READS: List<LabRead> = listOf(
            LabRead(BragiServices.DEV_INFO, "device.dev_info", body = WireDeviceInfo.devInfo()),
            LabRead(BragiServices.SYS_INFO, "device.sys_info"),
            LabRead(BragiServices.BATT_INFO, "device.batt_info"),
            LabRead(BragiServices.TIME_GET, "device.time_get"),
            LabRead(BragiServices.MODE_READ, "hearing.mode_read", streaming = true),
            LabRead(BragiServices.HEARING_MODE_INFO, "hearing.mode_info"),
            LabRead(BragiServices.SIDETONE_READ, "hearing.sidetone_read", streaming = true),
            LabRead(BragiServices.SIDETONE_INFO, "hearing.sidetone_info"),
            LabRead(BragiServices.PEQ_READ_STATE, "hearing.peq_read_state", streaming = true),
            LabRead(BragiServices.PEQ_INFO, "hearing.peq_info"),
            LabRead(BragiServices.SPATIAL_INFO_GET, "spatial_audio.info_get", streaming = true),
            LabRead(BragiServices.CRUSHER_CONFIG_GET, "crusher.config_get"),
            LabRead(BragiServices.CRUSHER_STATE_GET, "crusher.state_get", streaming = true),
        )

        /** No ANC switch (Off is a mode), no Wind Block, no editable modes; the level slider is the active mode's gain. */
        val CAPABILITIES = HeadphoneCapabilities(
            spatialAudio = true, ancToggle = false, liveNoiseControl = true, windExcludesCancelling = false,
            noiseCancelling = true, ancAlwaysOn = false, windBlock = false, editableModes = false, favoriteModes = false, verified = false,
        )
    }
}

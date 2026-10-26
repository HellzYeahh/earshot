package dev.earshot.service

import dev.earshot.bt.SonyConnection
import dev.earshot.bt.SonyLog
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.NoiseSettings
import dev.earshot.protocol.sony.AudioType
import dev.earshot.protocol.sony.SonyAlert
import dev.earshot.protocol.sony.SonyAudio
import dev.earshot.protocol.sony.SonyAutoPowerOff
import dev.earshot.protocol.sony.SonyBattery
import dev.earshot.protocol.sony.SonyCapabilityInfo
import dev.earshot.protocol.sony.SonyDeviceInfo
import dev.earshot.protocol.sony.SonyEqCapability
import dev.earshot.protocol.sony.SonyEqSetting
import dev.earshot.protocol.sony.SonyFunction
import dev.earshot.protocol.sony.SonyGeneralSetting
import dev.earshot.protocol.sony.SonyMessage
import dev.earshot.protocol.sony.SonyNcAsm
import dev.earshot.protocol.sony.SonyNoiseMode
import dev.earshot.protocol.sony.SonyProtocolInfo
import dev.earshot.protocol.sony.SonySupportFunctions
import dev.earshot.protocol.sony.SonySystem
import dev.earshot.protocol.sony.SonyTable
import dev.earshot.protocol.sony.SonyVoiceGuidance
import dev.earshot.protocol.sony.SystemType
import kotlin.coroutines.cancellation.CancellationException

/**
 * One connection's worth of Sony protocol: the init sequence, reads, writes and pushes, over a
 * [SonyConnection]. Kept free of Android so the end-to-end tests run it against `VirtualSony`.
 *
 * Init (Sound Connect's order, sony.md §3): protocol info (the link factory already read it) →
 * capability info → device info (model name, firmware, series / colour) → support functions on
 * table 1 and table 2 → one capability read per supported function → the values. Only the
 * protocol info and the table-1 support list are required; any other read that fails is logged
 * and left out. Usage logging (`C4`) is never switched on.
 */
class SonySession(
    private val conn: SonyConnection,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    @Volatile
    var snapshot: SonySnapshot = SonySnapshot(protocol = conn.protocolInfo)
        private set

    /** When the user last changed something: a confirmation prompt soon after is theirs to accept. */
    @Volatile
    private var lastUserWriteAt = Long.MIN_VALUE / 2

    private fun fold(m: SonyMessage): SonySnapshot = synchronized(lock) {
        SonyStateMapping.update(snapshot, m).also { snapshot = it }
    }

    private fun t1(p: ByteArray) = SonyMessage(SonyTable.T1, p)
    private fun t2(p: ByteArray) = SonyMessage(SonyTable.T2, p)

    /** Request [m] and fold the reply in; a failure is logged and swallowed (optional reads). */
    private suspend fun read(m: SonyMessage, what: String): Boolean = try {
        fold(conn.request(m))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: HeadphoneCommandException) {
        SonyLog.w("$what: ${e.message}")
        false
    }

    suspend fun initialize(): SonySnapshot {
        val protocol = conn.protocolInfo ?: SonyProtocolInfo.parse(conn.request(t1(SonyProtocolInfo.GET)).payload)
        if (protocol == null || !protocol.isV2) throw HeadphoneCommandException("These Sony headphones don't speak MDR v2")
        synchronized(lock) { snapshot = snapshot.copy(protocol = protocol) }
        if (!protocol.isKnownVersion) SonyLog.w("unknown MDR version ${protocol.versionText}: read-only")

        read(t1(SonyCapabilityInfo.GET), "capability info")
        for (type in listOf(SonyDeviceInfo.MODEL_NAME, SonyDeviceInfo.FW_VERSION, SonyDeviceInfo.SERIES_AND_COLOR)) {
            read(t1(SonyDeviceInfo.get(type)), "device info $type")
        }

        val t1Functions = (if (protocol.table1) SonySupportFunctions.parse(conn.request(t1(SonySupportFunctions.GET)).payload) else null)
            ?: throw HeadphoneCommandException("The headphones didn't list their functions")
        val t2Functions = if (protocol.table2) {
            runCatching { SonySupportFunctions.parse(conn.request(t2(SonySupportFunctions.GET)).payload) }
                .onFailure { if (it is CancellationException) throw it; SonyLog.w("table-2 functions: ${it.message}") }
                .getOrNull()
        } else null
        synchronized(lock) { snapshot = snapshot.withSupport(t1Functions, t2Functions.orEmpty()) }
        val s = snapshot
        SonyLog.i(
            "${s.modelName} fw ${s.firmware}, MDR ${protocol.versionText}: ${t1Functions.size} + ${t2Functions?.size ?: 0} functions, " +
                "NCASM %s, EQ %s, auto-off %s".format(s.ncAsmType?.let { "%02x".format(it) }, s.eqType?.let { "%02x".format(it) }, s.autoOffType?.let { "%02x".format(it) }),
        )

        // Capabilities.
        s.ncAsmType?.takeIf { dev.earshot.protocol.sony.SonyNcAsmShape.hasLevel(it) }?.let { read(t1(SonyNcAsm.getCapability(it)), "NCASM capability") }
        // With the display-language byte, as mos9527 asks (proven on WH-1000XM5/XM6, WF-C700N, LinkBuds Clip).
        s.eqType?.let { read(t1(SonyEqCapability.get(it) + byteArrayOf(DISPLAY_LANGUAGE_ENGLISH)), "EQ capability") }
        s.autoOffType?.let { read(t1(SonyAutoPowerOff.getCapability(it)), "auto-off capability") }
        for (gs in GENERAL_SETTING_TYPES) {
            if (gs in t1Functions) read(t1(SonyGeneralSetting.getCapability(gs)), "general setting %02x".format(gs))
        }
        if (SonyFunction.UPSCALING_AUTO_OFF in t1Functions) read(t1(SonyAudio.getCapability(AudioType.UPSCALING)), "DSEE capability")
        // Confirmation prompts on, as the app does: some settings only apply once they are answered.
        if (SonyFunction.FIXED_MESSAGE in t1Functions && snapshot.writable) {
            runCatching { conn.send(t1(SonyAlert.ENABLE)) }.onFailure { if (it is CancellationException) throw it; SonyLog.w("alerts: ${it.message}") }
        }

        readValues()
        return snapshot
    }

    /** Every value the headset announced (not the capabilities, which don't change within a connection). */
    suspend fun readValues(): SonySnapshot {
        val s = snapshot
        readBattery()
        s.ncAsmType?.let { read(t1(SonyNcAsm.getParam(it)), "NCASM") }
        s.eqType?.let { read(t1(SonyEqSetting.get(it)), "EQ") }
        s.autoOffType?.let { read(t1(SonyAutoPowerOff.getParam(it)), "auto-off") }
        for (gs in s.generalSettings.keys.sorted()) read(t1(SonyGeneralSetting.getParam(gs)), "general setting %02x".format(gs))
        if (SonyFunction.UPSCALING_AUTO_OFF in s.t1) read(t1(SonyAudio.getParam(AudioType.UPSCALING)), "DSEE")
        s.connectionModeType?.let { read(t1(SonyAudio.getParam(it)), "connection mode") }
        if (SonyFunction.SMART_TALKING_MODE_TYPE2 in s.t1) read(t1(SonySystem.getParam(SystemType.SMART_TALKING_MODE_TYPE2)), "speak-to-chat")
        if (SonyFunction.PLAYBACK_CONTROL_BY_WEARING in s.t1) read(t1(SonySystem.getParam(SystemType.PLAYBACK_CONTROL_BY_WEARING)), "pause when taken off")
        s.voiceGuidanceType?.let { read(t2(SonyVoiceGuidance.getParam(it)), "voice guidance") }
        return checkedSnapshot()
    }

    suspend fun readBattery(): SonySnapshot {
        for (type in SonyBattery.typesFor(snapshot.t1)) read(t1(SonyBattery.get(type)), "battery %02x".format(type))
        return checkedSnapshot()
    }

    /**
     * Each read above is optional (a function may not answer), so a link that died during them
     * would otherwise hand back the old values as a successful refresh.
     */
    private fun checkedSnapshot(): SonySnapshot {
        if (!conn.isOpen) throw HeadphoneCommandException("Headphones not connected")
        return snapshot
    }

    /** A push (or a late reply): fold it in, and answer a confirmation prompt. Returns a line to show, if any. */
    suspend fun onPush(m: SonyMessage): String? {
        val alert = if (m.table == SonyTable.T1) SonyAlert.parse(m.payload) else null
        if (alert == null) {
            fold(m)
            return null
        }
        if (!alert.needsAnswer) return alert.text
        // Only a change the user just made gets a yes; anything else the headset asks on its own is declined.
        val positive = clock() - lastUserWriteAt <= ALERT_WINDOW_MS
        SonyLog.i("confirmation prompt %02x: answering %s".format(alert.messageType, if (positive) "yes" else "no"))
        runCatching { conn.send(t1(alert.answer(positive))) }.onFailure { if (it is CancellationException) throw it; SonyLog.w("alert answer: ${it.message}") }
        return if (positive) alert.text else null
    }

    // ---- Writes ------------------------------------------------------------------------------

    private fun requireWritable() {
        if (!snapshot.writable) {
            throw HeadphoneCommandException("Earshot doesn't know this Sony protocol version (${snapshot.protocol?.versionText}), so it only reads settings")
        }
    }

    /** Send [message] (the user's change), then read back with [readBack] so the state is what the headset did. */
    private suspend fun write(message: SonyMessage, readBack: SonyMessage?): SonySnapshot {
        requireWritable()
        lastUserWriteAt = clock()
        conn.send(message)
        if (readBack != null) {
            // The headset usually pushes the new value too; the read-back covers models that don't.
            fold(conn.request(readBack))
        }
        return snapshot
    }

    suspend fun setMode(index: Int): SonySnapshot {
        val nc = snapshot.ncAsm ?: throw HeadphoneCommandException("These headphones have no noise control")
        val target = SonyStateMapping.modeFor(index) ?: throw HeadphoneCommandException("There is no listening mode in slot $index")
        if (target == SonyNoiseMode.NOISE_CANCELLING && !nc.hasNoiseCancelling) throw HeadphoneCommandException("These headphones have no noise cancelling")
        if (target == SonyNoiseMode.AMBIENT && !nc.hasAmbient) throw HeadphoneCommandException("These headphones have no ambient sound mode")
        return write(t1(nc.withMode(target).encodeSet()), t1(SonyNcAsm.getParam(nc.type)))
    }

    /**
     * Automations and the noise panel speak [NoiseSettings]: `ancEnabled` false = Off; true from Off =
     * noise cancelling (or ambient where there is none); the level only means something in Ambient
     * sound, where it is mapped onto the headset's ambient range. Wind block and spatial audio have
     * no Sony equivalent here and are ignored.
     */
    suspend fun setNoise(settings: NoiseSettings): SonySnapshot {
        val s = snapshot
        var nc = s.ncAsm ?: throw HeadphoneCommandException("These headphones have no noise control")
        nc = when {
            !settings.ancEnabled -> nc.withMode(SonyNoiseMode.OFF)
            nc.mode == SonyNoiseMode.OFF -> nc.withMode(if (nc.hasNoiseCancelling) SonyNoiseMode.NOISE_CANCELLING else SonyNoiseMode.AMBIENT)
            else -> nc
        }
        if (nc.mode == SonyNoiseMode.AMBIENT && nc.hasLevel && settings.cancellingLevel != SonyStateMapping.ambientAsCancelling(s)) {
            nc = nc.copy(ambientLevel = SonyStateMapping.cancellingAsAmbient(s, settings.cancellingLevel))
        }
        if (nc == s.ncAsm) return s
        return write(t1(nc.encodeSet()), t1(SonyNcAsm.getParam(nc.type)))
    }

    suspend fun setVendorControl(id: String, value: Int): SonySnapshot {
        val s = snapshot
        val message = SonyStateMapping.write(s, id, value) ?: throw HeadphoneCommandException("These Sony headphones have no setting \"$id\"")
        return write(message, SonyStateMapping.readBack(s, id))
    }

    /** `POWER_SET_STATUS [24, 03, 01]` USER_POWER_OFF; the link drops right after. */
    suspend fun powerOff() {
        requireWritable()
        if (SonyFunction.POWER_OFF !in snapshot.t1) throw HeadphoneCommandException("These headphones can't be switched off from the app")
        lastUserWriteAt = clock()
        conn.send(t1(SonyBattery.USER_POWER_OFF))
    }

    companion object {
        /** A prompt within this long of the user's own change is answered yes. */
        const val ALERT_WINDOW_MS = 5_000L

        private val GENERAL_SETTING_TYPES = SonyFunction.GENERAL_SETTING_1..SonyFunction.GENERAL_SETTING_4

        /** `DisplayLanguage.ENGLISH`. */
        private const val DISPLAY_LANGUAGE_ENGLISH: Byte = 0x01
    }
}

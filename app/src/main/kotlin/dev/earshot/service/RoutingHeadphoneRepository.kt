package dev.earshot.service

import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneCandidate
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.LabReading
import dev.earshot.core.NoiseSettings
import dev.earshot.core.RawFrame
import dev.earshot.core.Sidetone
import dev.earshot.core.TransportControl
import dev.earshot.core.VoicePrompts
import dev.earshot.protocol.bragi.BragiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which stack drives a pair of headphones. */
enum class DeviceFamily {
    /** Bose BMAP: QC Ultra, QC Headphones, QC45 and the other Bose models. */
    BOSE,

    /** Bragi BOS-RPC: the Skullcandy Crusher 1080 ANC ("Sound by Bose"). */
    BRAGI,
    ;

    companion object {
        /** By Bluetooth name; a name Earshot does not recognise is treated as Bose, as before. */
        fun forName(name: String?): DeviceFamily = if (BragiModel.isBragiName(name)) BRAGI else BOSE

        fun fromKey(key: String?): DeviceFamily? = entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }
}

/**
 * The [HeadphoneRepository] the rest of the app sees. It owns one repository per [DeviceFamily] and
 * forwards every call to the one that drives the paired headphones, so Home, the widget, the tile,
 * the notification and the automations never know which protocol is underneath.
 *
 * The family comes from, in order: the user's "Headphone type" setting, the demo device, the saved
 * device's Bluetooth name, the first Bose- or Skullcandy-looking paired device. The repository for
 * the other family stays idle: its connection manager stands down for devices it does not handle.
 */
class RoutingHeadphoneRepository(
    private val bose: ServiceHeadphoneRepository,
    private val crusher: CrusherHeadphoneRepository,
    private val prefs: DevicePrefs,
    private val finder: DeviceFinder,
    private val scope: CoroutineScope,
    /** Demo mode's device: null when demo mode is off. Read at every connect. */
    private val demoFamily: () -> DeviceFamily? = { null },
) : HeadphoneRepository {

    private val active = MutableStateFlow(DeviceFamily.BOSE)

    private fun repo(family: DeviceFamily): HeadphoneRepository = if (family == DeviceFamily.BRAGI) crusher else bose
    private val current: HeadphoneRepository get() = repo(active.value)

    override val state: StateFlow<HeadphoneState> =
        combine(active, bose.state, crusher.state) { family, b, c -> if (family == DeviceFamily.BRAGI) c else b }
            .stateIn(scope, SharingStarted.Eagerly, bose.state.value)

    override val events: SharedFlow<HeadphoneEvent> =
        merge(bose.events, crusher.events).shareIn(scope, SharingStarted.Eagerly)

    /** The family currently in charge (for the Lab and the diagnostics screen). */
    val family: StateFlow<DeviceFamily> get() = active

    // ---- Selection -------------------------------------------------------------------------

    /** The family for the paired headphones, see the class comment. */
    suspend fun resolveFamily(): DeviceFamily {
        demoFamily()?.let { return it }
        DeviceFamily.fromKey(prefs.familyOverride())?.let { return it }
        prefs.savedDevice()?.let { return DeviceFamily.forName(it.name) }
        return DeviceFamily.forName(finder.candidates().firstOrNull { it.looksLikeBose }?.name)
    }

    private suspend fun select(family: DeviceFamily) {
        if (active.value == family) return
        // Free the other family's connection (not the foreground service) before the new one starts.
        when (active.value) {
            DeviceFamily.BOSE -> bose.releaseConnection()
            DeviceFamily.BRAGI -> crusher.releaseConnection()
        }
        active.value = family
    }

    // ---- Connection ------------------------------------------------------------------------

    override fun connect() {
        scope.launch {
            select(resolveFamily())
            current.connect()
        }
    }

    override fun disconnect() = current.disconnect()

    override suspend fun deviceCandidates(): List<HeadphoneCandidate> = current.deviceCandidates()

    override suspend fun chooseDevice(address: String, name: String?) {
        // An explicit "Headphone type" setting still wins over the name of the device just picked.
        select(demoFamily() ?: DeviceFamily.fromKey(prefs.familyOverride()) ?: DeviceFamily.forName(name))
        current.chooseDevice(address, name)
    }

    /** True when [address] is the saved (or, before any choice, a Bose- or Skullcandy-looking) device. Used by the link-up receiver. */
    suspend fun isOurDevice(address: String): Boolean {
        val saved = prefs.savedDevice()?.address
        if (saved != null) return saved.equals(address, ignoreCase = true)
        return finder.candidates().any { it.address.equals(address, ignoreCase = true) && it.looksLikeBose }
    }

    // ---- Everything else forwards ----------------------------------------------------------

    override suspend fun refresh() = current.refresh()
    override suspend fun setMode(index: Int) = current.setMode(index)
    override suspend fun saveCustomMode(index: Int, name: String, settings: NoiseSettings) = current.saveCustomMode(index, name, settings)
    override suspend fun setFavorites(indices: Set<Int>) = current.setFavorites(indices)
    override suspend fun setNoise(settings: NoiseSettings) = current.setNoise(settings)
    override suspend fun setEq(eq: HeadphoneEq) = current.setEq(eq)
    override suspend fun setSidetone(level: Sidetone, persistent: Boolean) = current.setSidetone(level, persistent)
    override suspend fun setAutoPlayPause(enabled: Boolean) = current.setAutoPlayPause(enabled)
    override suspend fun setAutoAnswer(enabled: Boolean) = current.setAutoAnswer(enabled)
    override suspend fun setMultipoint(enabled: Boolean) = current.setMultipoint(enabled)
    override suspend fun setVoicePrompts(prompts: VoicePrompts) = current.setVoicePrompts(prompts)
    override suspend fun setShortcutButton(action: ButtonAction) = current.setShortcutButton(action)
    override suspend fun setDeviceName(name: String) = current.setDeviceName(name)
    override suspend fun switchToDevice(address: String) = current.switchToDevice(address)
    override suspend fun enterPairingMode() = current.enterPairingMode()
    override suspend fun powerOff() = current.powerOff()
    override suspend fun resetCustomMode(index: Int) = current.resetCustomMode(index)
    override suspend fun setLeAudioEnabled(enabled: Boolean) = current.setLeAudioEnabled(enabled)
    override suspend fun setLatencyMs(ms: Int) = current.setLatencyMs(ms)
    override suspend fun setTouchControls(enabled: Boolean) = current.setTouchControls(enabled)
    override suspend fun setVolume(level: Int) = current.setVolume(level)
    override suspend fun transport(control: TransportControl) = current.transport(control)
    override suspend fun setCrusherBass(active: Boolean?, intensity: Int?, mode: Int?) = current.setCrusherBass(active, intensity, mode)
    override suspend fun answerPairing(accept: Boolean) = current.answerPairing(accept)
    override suspend fun forgetPairing() = current.forgetPairing()
    override suspend fun printConsole(command: String): String = current.printConsole(command)
    override suspend fun readRaw(block: Int, function: Int): List<RawFrame> = current.readRaw(block, function)
    override fun scanFunctions(blocks: List<Int>, maxFunction: Int): Flow<LabReading> =
        flow { emitAll(current.scanFunctions(blocks, maxFunction)) }
}

package dev.earshot.service

import dev.earshot.bt.AudioLinkMonitor
import dev.earshot.bt.BtLog
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
import dev.earshot.bt.DeviceSelection
import dev.earshot.bt.FindResult
import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneCandidate
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.NoiseSettings
import dev.earshot.core.RawFrame
import dev.earshot.core.Sidetone
import dev.earshot.core.TransportControl
import dev.earshot.core.VoicePrompts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The [HeadphoneRepository] the rest of the app sees. It owns one repository per [DeviceFamily] and
 * forwards every call to the one that drives the headset, so Home, the widget, the tile, the
 * notification and the automations never know which protocol is underneath.
 *
 * Which headset: [DeviceFinder.find] (the one connected right now, else the saved one; an explicit
 * pick wins for the rest of the run). Which family: [DevicePrefs.familyFor] for that device (its
 * "Headphone type" setting, then the stack that answered on it before, then its name), or the
 * demo device. Anything Earshot has no stack for goes to [GenericHeadphoneRepository], which never
 * sends it anything. The other repositories stay idle: their connection managers stand down for
 * devices they do not handle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingHeadphoneRepository(
    /** One per vendor stack; must include [DeviceFamily.GENERIC]. See [VendorDriver]. */
    drivers: List<VendorDriver>,
    private val prefs: DevicePrefs,
    private val finder: DeviceFinder,
    private val scope: CoroutineScope,
    /** Demo mode's device: null when demo mode is off. Read at every connect. */
    private val demoFamily: () -> DeviceFamily? = { null },
    /** Fills [ConnectionState.audioLinked] for the shown headset; null in tests and previews. */
    private val audioLink: AudioLinkMonitor? = null,
) : HeadphoneRepository {

    private val active = MutableStateFlow(DeviceFamily.BOSE)

    private val byFamily: Map<DeviceFamily, VendorDriver> = drivers.associateBy { it.family }

    init {
        require(DeviceFamily.GENERIC in byFamily) { "the generic driver is the fallback for every unclaimed device" }
    }

    /** The driver for [family]; a family without one (not wired in this build) falls back to generic. */
    private fun driver(family: DeviceFamily): VendorDriver = byFamily[family] ?: byFamily.getValue(DeviceFamily.GENERIC)
    private fun repo(family: DeviceFamily): HeadphoneRepository = driver(family).repository
    private val current: HeadphoneRepository get() = repo(active.value)

    private val activeState = active.flatMapLatest { repo(it).state }

    override val state: StateFlow<HeadphoneState> =
        combine(activeState, audioLink?.linked ?: flowOf(null)) { s, linked ->
            if (s.connection.audioLinked == linked) s else s.copy(connection = s.connection.copy(audioLinked = linked))
        }.stateIn(scope, SharingStarted.Eagerly, repo(active.value).state.value)

    init {
        // The monitor follows whichever headset the active stack shows.
        audioLink?.let { monitor ->
            scope.launch { activeState.map { it.connection.deviceAddress }.distinctUntilChanged().collect { monitor.follow(it) } }
        }
    }

    override val events: SharedFlow<HeadphoneEvent> =
        merge(*drivers.map { it.repository.events }.toTypedArray()).shareIn(scope, SharingStarted.Eagerly)

    /** The family currently in charge (for the Lab and the diagnostics screen). */
    val family: StateFlow<DeviceFamily> get() = active

    // ---- Selection -------------------------------------------------------------------------

    /**
     * The family for the headset [DeviceFinder.find] picks, see the class comment; the managers
     * pick with the same call and stand down unless [DevicePrefs.familyFor] names their family.
     * With nothing to pick (no permission, Bluetooth off, nothing paired) the saved device's
     * family reports it, or the generic repository when there is none.
     */
    suspend fun resolveFamily(): DeviceFamily {
        demoFamily()?.let { return it }
        (finder.find() as? FindResult.Found)?.let { return prefs.familyFor(it.candidate.address, it.candidate.name) }
        prefs.savedDevice()?.let { return prefs.familyFor(it.address, it.name) }
        return DeviceFamily.GENERIC
    }

    private val selectLock = Mutex()

    private suspend fun select(family: DeviceFamily) = selectLock.withLock {
        if (active.value == family) return@withLock
        // Free the other family's connection (not the foreground service) before the new one starts.
        driver(active.value).release()
        active.value = family
    }

    // ---- Connection ------------------------------------------------------------------------

    @Volatile
    private var connectJob: Job? = null

    override fun connect() {
        connectJob?.cancel()
        connectJob = scope.launch { connectNow() }
    }

    /**
     * Pick the family and start connecting, completing only once that has started. The link-up
     * receiver awaits this so the foreground service starts and the manager launches before the
     * receiver's short lifetime ends.
     *
     * Picking the family reads DataStore (the saved headset, its "Headphone type"), which throws
     * IOException on an unreadable file or a full disk. Every caller runs this in a scope without
     * an exception handler ([connect] in the application scope, [HeadsetPresenceService], the
     * link-up receiver, the service's sticky restart), so it is caught here, once: the stack
     * already in charge connects instead, and its own read of the same store fails the same way
     * and shows the storage-failed message (ConnectionManager, [GenericHeadphoneRepository]),
     * rather than the process crashing on every link-up. Cancellation is not an IOException.
     */
    suspend fun connectNow() {
        val family = try {
            resolveFamily()
        } catch (e: IOException) {
            BtLog.w("router: the saved headphones can't be read (${e.javaClass.simpleName}); keeping the ${active.value.name.lowercase()} stack")
            null
        }
        family?.let { select(it) }
        current.connect()
    }

    override fun disconnect() {
        connectJob?.cancel() // a connect that has not resolved its family yet must not undo this
        current.disconnect()
    }

    override suspend fun deviceCandidates(): List<HeadphoneCandidate> = current.deviceCandidates()

    override suspend fun chooseDevice(address: String, name: String?) {
        // The user's pick beats "whatever is connected" for the rest of this run.
        DeviceSelection.pinned = address
        select(demoFamily() ?: prefs.familyFor(address, name))
        current.chooseDevice(address, name)
    }

    /**
     * True when the link-up receiver should follow [address]: the saved device (or its LE Audio /
     * classic twin), or since 0.6 any other headset, so the one just turned on is the one shown.
     * A car stereo or a watch is not a headset: only audio-class devices and products Earshot has
     * a stack for count.
     */
    suspend fun isOurDevice(address: String): Boolean {
        val saved = prefs.savedDevice()?.address
        if (saved != null && (saved.equals(address, ignoreCase = true) || finder.twinsOf(saved).any { it.address.equals(address, ignoreCase = true) })) {
            return true
        }
        return finder.candidates().any { it.address.equals(address, ignoreCase = true) && (it.isAudioDevice || it.looksLikeBose) }
    }

    /** A headset other than the pinned one linked up: the user just turned it on, so it wins again. */
    fun followLinkUp(address: String) {
        val pinned = DeviceSelection.pinned ?: return
        if (!pinned.equals(address, ignoreCase = true)) DeviceSelection.pinned = null
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
    override suspend fun setShortcutButton(buttonId: Int, eventType: Int, action: ButtonAction) = current.setShortcutButton(buttonId, eventType, action)
    override suspend fun setDeviceName(name: String) = current.setDeviceName(name)
    override suspend fun switchToDevice(address: String) = current.switchToDevice(address)
    override suspend fun enterPairingMode() = current.enterPairingMode()
    override suspend fun powerOff() = current.powerOff()
    override suspend fun resetCustomMode(index: Int) = current.resetCustomMode(index)
    override suspend fun setLeAudioEnabled(enabled: Boolean) = current.setLeAudioEnabled(enabled)
    override suspend fun setLatencyMs(ms: Int) = current.setLatencyMs(ms)
    override suspend fun setTouchControls(enabled: Boolean) = current.setTouchControls(enabled)
    /** The Crusher has no auto-off setting: its repository keeps the interface's "not supported". */
    override suspend fun setAutoOff(minutes: Int) = current.setAutoOff(minutes)
    override suspend fun setVolume(level: Int) = current.setVolume(level)
    override suspend fun transport(control: TransportControl) = current.transport(control)
    override suspend fun setCrusherBass(active: Boolean?, intensity: Int?, mode: Int?) = current.setCrusherBass(active, intensity, mode)
    override suspend fun setVendorControl(id: String, value: Int) = current.setVendorControl(id, value)
    override suspend fun answerPairing(accept: Boolean) = current.answerPairing(accept)
    override suspend fun forgetPairing() = current.forgetPairing()
    override suspend fun printConsole(command: String): String = current.printConsole(command)
    override suspend fun readRaw(block: Int, function: Int): List<RawFrame> = current.readRaw(block, function)

    /** The Lab follows the stack in charge; null for the generic driver (nothing to scan). */
    override fun labProfile(): LabProfile? = current.labProfile()

    /** Resolved when collected, so a scan always runs on the stack in charge at that moment. */
    override fun scanLab(groupIds: List<Int>, deep: Boolean): Flow<LabReading> =
        flow { emitAll(current.scanLab(groupIds, deep)) }
}

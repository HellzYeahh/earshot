package dev.earshot.service

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import dev.earshot.bt.AppleAdvertScanner
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.DeviceFinder
import dev.earshot.bt.DevicePrefs
import dev.earshot.bt.FindResult
import dev.earshot.core.BatteryDetail
import dev.earshot.core.BatteryLevel
import dev.earshot.core.ButtonAction
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCandidate
import dev.earshot.core.HeadphoneCapabilities
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.core.LabProfile
import dev.earshot.core.RawFrame
import dev.earshot.core.Sidetone
import dev.earshot.core.VoicePrompts
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.protocol.apple.ContinuityAdvert
import dev.earshot.service.session.toHeadphoneCandidate
import dev.earshot.virtual.DemoHeadset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Any headset Earshot has no stack for ([DeviceFamily.GENERIC]): AirPods, Sony, Soundcore,
 * no-name earbuds. Nothing is ever sent to the headset. What it shows comes from Android alone:
 *  - **connected** = an audio profile (A2DP, HFP or LE Audio) is up, not a control channel;
 *  - **name** = its Bluetooth name;
 *  - **battery** = Android's `BATTERY_LEVEL_CHANGED` broadcast, which the Bluetooth stack sends
 *    for the HFP battery indicator, Apple's `+IPHONEACCEV`, Plantronics' `+XEVENT` and BLE
 *    Battery Service, to any app holding BLUETOOTH_CONNECT. There is no public way to read the
 *    current value (`getBatteryLevel()` is a SystemApi since Android 13), so it shows up with
 *    the headset's next report.
 * Its own controls throw [UNSUPPORTED]; the capabilities say so, so the UI never offers them.
 * Pro EQ, codec, call audio, Controls and automations work through Android APIs regardless.
 */
class GenericHeadphoneRepository(
    context: Context,
    private val prefs: DevicePrefs,
    private val finder: DeviceFinder,
    private val scope: CoroutineScope,
    /**
     * The battery the headset last reported, read once when it connects (Shizuku dumpsys where
     * available), so Home has a figure before the next change broadcast. Null = unknown.
     */
    private val initialBattery: suspend (address: String) -> Int? = { null },
    /** Per-bud and case battery of AirPods / Beats from their BLE adverts; null = off. */
    private val appleAdverts: AppleAdvertScanner? = null,
    /** Demo mode's headset, read at every connect; null = real Bluetooth. */
    private val demo: () -> DemoHeadset? = { null },
) : HeadphoneRepository {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(HeadphoneState(capabilities = capabilitiesFor(null)))
    override val state: StateFlow<HeadphoneState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HeadphoneEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<HeadphoneEvent> = _events.asSharedFlow()

    /** True between [connect] and [disconnect] / [releaseConnection]: only then is the headset followed. */
    @Volatile
    private var following = false

    @Volatile
    private var address: String? = null

    /** Serialises link updates (several broadcasts arrive per link-up). */
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private var locateJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = onBluetoothEvent(intent)
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(DeviceFinder.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        // Protected system broadcasts, as in ConnectionManager.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    // ---- Connection ------------------------------------------------------------------------

    override fun connect() {
        demo()?.let { showDemo(it); return }
        following = true
        // The foreground service keeps automations and the notification alive, as for Bose.
        if (finder.hasConnectPermission()) HeadphoneService.start(appContext)
        locateJob?.cancel()
        locateJob = scope.launch(serial) { locate() }
    }

    override fun disconnect() {
        following = false
        locateJob?.cancel()
        appleAdverts?.stop()
        // The audio link is Android's: "disconnect" only stops following it.
        setConnection(ConnectionStatus.DISCONNECTED, message = null)
        HeadphoneService.stop(appContext)
    }

    /** Stop following without stopping the foreground service: another repository is taking over. */
    fun releaseConnection() {
        following = false
        locateJob?.cancel()
        appleAdverts?.stop()
        address = null
        _state.value = HeadphoneState(capabilities = capabilitiesFor(null))
    }

    override suspend fun deviceCandidates(): List<HeadphoneCandidate> =
        finder.candidates().map { it.toHeadphoneCandidate() }

    override suspend fun chooseDevice(address: String, name: String?) {
        prefs.save(address, name)
        connect()
    }

    override suspend fun refresh() {
        if (following) updateLink()
    }

    /** Find the headset the same way the connection managers do, then follow its audio link. */
    private suspend fun locate() {
        when (val found = finder.find()) {
            FindResult.NoPermission -> setConnection(ConnectionStatus.NO_PERMISSION, message = "Allow Earshot to connect to nearby devices")
            FindResult.BluetoothOff -> setConnection(ConnectionStatus.DISCONNECTED, message = "Bluetooth is off")
            is FindResult.NoDevice -> setConnection(
                ConnectionStatus.NO_PAIRED_DEVICE,
                message = if (found.candidates.isEmpty()) "Pair your headset in Bluetooth settings first" else "Choose your headset",
            )
            is FindResult.Found -> {
                val pick = found.candidate
                // Same rule as the router and the managers: a device another stack drives isn't ours.
                if (prefs.familyFor(pick.address, pick.name) != DeviceFamily.GENERIC) {
                    Log.d(TAG, "\"${pick.name}\" has its own stack; standing down")
                    return
                }
                if (!pick.isSaved || prefs.savedDevice()?.name != pick.name) prefs.save(pick.address, pick.name)
                address = pick.address
                _state.update { s ->
                    // A different headset than the one shown: start from a clean slate.
                    val fresh = if (s.connection.deviceAddress.equals(pick.address, ignoreCase = true)) s else HeadphoneState()
                    fresh.copy(
                        connection = fresh.connection.copy(deviceName = pick.name, deviceAddress = pick.address),
                        capabilities = capabilitiesFor(pick.name),
                    )
                }
                updateLink()
            }
        }
    }

    /** Re-read whether the headset's audio link is up and publish it. */
    private suspend fun updateLink() {
        val a = address ?: return
        val linked = finder.isAudioConnected(a)
        val was = _state.value.isConnected
        when (linked) {
            true -> setConnection(ConnectionStatus.CONNECTED, message = null)
            // Unknown (no profile proxy answered in time): keep what we had.
            null -> if (_state.value.connection.status == ConnectionStatus.CONNECTING) setConnection(ConnectionStatus.DISCONNECTED, message = null)
            false -> setConnection(ConnectionStatus.DISCONNECTED, message = null)
        }
        val now = _state.value.isConnected
        if (now && !was) {
            Log.i(TAG, "headset connected for audio: $a")
            if (_state.value.batteryPercent == null) {
                runCatching { initialBattery(a) }.getOrNull()?.let { level ->
                    Log.d(TAG, "battery from the last HFP report: $level")
                    _state.update { it.copy(batteryPercent = level) }
                }
            }
            startAppleAdverts(_state.value.connection.deviceName)
            _events.tryEmit(HeadphoneEvent.Connected)
        } else if (!now && was) {
            appleAdverts?.stop()
            Log.i(TAG, "headset no longer connected: $a")
            // Yesterday's battery is not today's.
            _state.update { it.copy(batteryPercent = null, isCharging = null, battery = null) }
            _events.tryEmit(HeadphoneEvent.Disconnected)
        }
    }

    /** AirPods and Beats advertise each bud's battery: listen while they are the connected headset. */
    private fun startAppleAdverts(name: String?) {
        val scanner = appleAdverts ?: return
        if (!looksApple(name)) return
        scanner.start(name) { advert -> scope.launch(serial) { applyAdvert(advert) } }
    }

    private fun applyAdvert(advert: ContinuityAdvert) {
        if (!_state.value.isConnected) return
        _state.update { s ->
            s.copy(
                batteryPercent = advert.headlinePercent ?: s.batteryPercent,
                isCharging = advert.leftCharging || advert.rightCharging || (advert.isHeadphones && advert.caseCharging),
                battery = if (advert.isHeadphones) {
                    BatteryDetail(overall = BatteryLevel(advert.headlinePercent))
                } else {
                    BatteryDetail(
                        left = BatteryLevel(advert.leftPercent),
                        right = BatteryLevel(advert.rightPercent),
                        case = advert.casePercent?.let { BatteryLevel(it) },
                    )
                },
                lastUpdatedMillis = System.currentTimeMillis(),
            )
        }
    }

    /** Demo mode: a virtual AirPods Pro, its battery from a canned advert through the real parser. */
    private fun showDemo(headset: DemoHeadset) {
        following = false
        address = DEMO_ADDRESS
        _state.value = HeadphoneState(
            connection = ConnectionState(ConnectionStatus.CONNECTED, headset.name, DEMO_ADDRESS, audioLinked = true),
            capabilities = capabilitiesFor(headset.name),
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        ContinuityAdvert.parse(headset.advert)?.let(::applyAdvert)
        _events.tryEmit(HeadphoneEvent.Connected)
    }

    private fun setConnection(status: ConnectionStatus, message: String?) {
        _state.update { it.copy(connection = it.connection.copy(status = status, message = message), lastUpdatedMillis = System.currentTimeMillis()) }
    }

    private fun onBluetoothEvent(intent: Intent) {
        if (!following) return
        if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF ->
                    scope.launch(serial) { setConnection(ConnectionStatus.DISCONNECTED, message = "Bluetooth is off"); updateLink() }
                BluetoothAdapter.STATE_ON -> scope.launch(serial) { locate() }
            }
            return
        }
        val from = DeviceFinder.deviceAddressFrom(intent) ?: return
        val ours = address ?: return
        val twins = finder.twinsOf(ours).map { it.address }
        if (!from.equals(ours, ignoreCase = true) && twins.none { it.equals(from, ignoreCase = true) }) return
        if (intent.action == ACTION_BATTERY_LEVEL_CHANGED) {
            val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
            Log.d(TAG, "battery report from $from: $level")
            _state.update { it.copy(batteryPercent = level.takeIf { l -> l in 0..100 }, lastUpdatedMillis = System.currentTimeMillis()) }
            return
        }
        scope.launch(serial) { updateLink() }
    }

    // ---- The headset's own controls: not available without its protocol ---------------------

    override suspend fun setMode(index: Int) = unsupported()
    override suspend fun saveCustomMode(index: Int, name: String, settings: NoiseSettings) = unsupported()
    override suspend fun setFavorites(indices: Set<Int>) = unsupported()
    override suspend fun setNoise(settings: NoiseSettings) = unsupported()
    override suspend fun setEq(eq: HeadphoneEq) = unsupported()
    override suspend fun setSidetone(level: Sidetone, persistent: Boolean) = unsupported()
    override suspend fun setAutoPlayPause(enabled: Boolean) = unsupported()
    override suspend fun setAutoAnswer(enabled: Boolean) = unsupported()
    override suspend fun setMultipoint(enabled: Boolean) = unsupported()
    override suspend fun setVoicePrompts(prompts: VoicePrompts) = unsupported()
    override suspend fun setShortcutButton(action: ButtonAction) = unsupported()
    override suspend fun setDeviceName(name: String) = unsupported()
    override suspend fun switchToDevice(address: String) = unsupported()
    override suspend fun enterPairingMode() = unsupported()
    override suspend fun powerOff() = unsupported()
    override suspend fun readRaw(block: Int, function: Int): List<RawFrame> = unsupported()

    /** No driver, so no documented reads: the Lab says nothing can be scanned and points to the connection report. */
    override fun labProfile(): LabProfile? = null

    private fun unsupported(): Nothing = throw HeadphoneCommandException(UNSUPPORTED)

    companion object {
        private const val TAG = "Earshot/Generic"

        /** `BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED` / `EXTRA_BATTERY_LEVEL`: hidden constants, public broadcast. */
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        const val UNSUPPORTED = "Earshot has no driver for this headset yet, so nothing was sent. If it is a supported brand under a custom name, " +
            "choose that brand in More → App preferences → Headphone type."
        const val DEMO_ADDRESS = "00:00:00:DE:A1:01"

        fun looksApple(name: String?): Boolean = name != null && Regex("""airpods|beats""", RegexOption.IGNORE_CASE).containsMatchIn(name)

        /** Shown on Home: what works, so the screen is not just a name. */
        const val SUPPORT_NOTE =
            "Earshot doesn't know this headset's own controls yet. Pro EQ, the codec panel, call audio, Controls and automations work with it."

        /** Nothing of the headset's own; worded by the form factor its name suggests ("… Buds"). */
        fun capabilitiesFor(name: String?) = HeadphoneCapabilities(
            spatialAudio = false,
            ancToggle = false,
            liveNoiseControl = false,
            noiseCancelling = false,
            windBlock = false,
            editableModes = false,
            favoriteModes = false,
            listeningModes = false,
            ownSettings = false,
            formFactor = HeadphoneLabels.formFactorFromName(name),
            supportNote = SUPPORT_NOTE,
        )
    }
}

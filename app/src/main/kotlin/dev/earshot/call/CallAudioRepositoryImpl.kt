package dev.earshot.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.earshot.core.CallAudioPreferences
import dev.earshot.core.CallAudioRepository
import dev.earshot.core.CallAudioState
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Real [CallAudioRepository].
 *
 * How a call is noticed, without READ_PHONE_STATE: Android 12+ reports every audio-mode change
 * through [AudioManager.addOnModeChangedListener] (MODE_IN_CALL for telephony, MODE_IN_COMMUNICATION
 * for VoIP) and every call-route change through [AudioManager.addOnCommunicationDeviceChangedListener].
 * Android 9-11 have neither, so there the SCO-state broadcast (sticky, permission-free) triggers a
 * re-read; that covers exactly the calls we care about, the Bluetooth ones.
 *
 * There is no public volume listener, so while a call is up the volume and route are polled every
 * [POLL_MS] and the codec (a `dumpsys bluetooth_manager` through Shizuku, a few hundred KB) every
 * [CODEC_EVERY_TICKS] polls. Nothing is polled outside a call.
 *
 * Everything that touches [_state] runs on one serial dispatcher ([work]), so listener callbacks,
 * polls, scheduled boosts and UI commands never interleave.
 *
 * The process must be alive for any of this to happen: the integrator creates this eagerly (the
 * foreground HeadphoneService keeps the process up while the headphones are connected).
 */
class CallAudioRepositoryImpl(
    context: Context,
    scope: CoroutineScope,
    private val headphones: HeadphoneRepository,
    private val shell: PrivilegedShell = ShizukuPrivilegedShell(context),
    private val headsetVolume: HeadsetVolumeControl = HeadsetVolumeControl.None,
) : CallAudioRepository {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager = appContext.getSystemService(AudioManager::class.java)
    private val prefsStore = CallAudioPrefsStore(appContext)

    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val work = CoroutineScope(scope.coroutineContext + serial)

    private val _state = MutableStateFlow(CallAudioState())
    override val state: StateFlow<CallAudioState> = _state

    private val _preferences = MutableStateFlow(CallAudioPreferences())
    override val preferences: StateFlow<CallAudioPreferences> = _preferences

    private val scheduler = CallStartBoostScheduler(work) { attempt -> autoBoost(attempt) }

    private data class RouteInfo(val route: CallRoute, val address: String?, val name: String?)

    private var route = RouteInfo(CallRoute.UNKNOWN, null, null)
    private var inCallJob: Job? = null
    private var codecReadInFlight = false
    private var diagnosticsLoggedThisCall = false
    private var noShellLogged = false

    /** Absolute-volume value Android is actually using: the one read when the headphones last connected. */
    private var absoluteVolumeInEffect: Boolean? = null

    init {
        work.launch {
            prefsStore.preferences.collect { _preferences.value = it }
        }
        registerCallListeners()
        work.launch {
            updateSignals()
            refreshAbsoluteVolume(atConnect = true)
        }
        shell.setOnAvailableListener { work.launch { refreshAbsoluteVolume(atConnect = absoluteVolumeInEffect == null) } }
        // Android applies the absolute-volume switch on connection, so a (re)connect is when the
        // pending flag can clear. The BMAP link is a proxy for the Bluetooth link here: it almost
        // always drops and returns with it.
        work.launch {
            headphones.state.map { it.isConnected }.distinctUntilChanged().collect { connected ->
                if (connected) refreshAbsoluteVolume(atConnect = true)
            }
        }
    }

    // ---- Commands ------------------------------------------------------------------------------

    override suspend fun setPreferences(p: CallAudioPreferences) {
        val clean = p.copy(boostPercent = p.boostPercent.coerceIn(CallAudioPreferences.MIN_BOOST_PERCENT, 100))
        _preferences.value = clean
        try {
            prefsStore.save(clean)
        } catch (e: IOException) {
            throw HeadphoneCommandException("Couldn't save the call settings.", e)
        }
        Log.i(TAG, "preferences: boost=${clean.boostEnabled} ${clean.boostPercent}% headset=${clean.alsoRaiseHeadset}")
    }

    override suspend fun boostNow() = withContext(serial) {
        updateSignals()
        if (!_state.value.inCall) {
            throw HeadphoneCommandException("Start a call first: Android keeps a separate call volume for each output.")
        }
        applyBoost(_preferences.value, onlyRaise = false, reason = "manual boost")
    }

    override suspend fun setAbsoluteVolumeDisabled(disabled: Boolean) = withContext(serial) {
        if (!shell.isAvailable()) fail(SHIZUKU_NEEDED)
        val out = shell.exec(AbsoluteVolumeSetting.writeCommand(disabled)).getOrElse {
            if (it is CancellationException) throw it
            fail("Android refused the absolute-volume change: ${it.message ?: it.javaClass.simpleName}")
        }
        val propWritten = out.contains(AbsoluteVolumeSetting.PROP_OK)
        Log.i(TAG, "absolute volume ${if (disabled) "disabled" else "enabled"}: global setting written, property ${if (propWritten) "written" else "not writable by shell"}; applies on next headset connection")
        val read = readAbsoluteVolume() ?: disabled
        _state.update {
            it.copy(absoluteVolumeDisabled = read, absoluteVolumeNeedsReconnect = needsReconnect(read), lastError = null)
        }
    }

    override suspend fun refresh() = withContext(serial) {
        updateSignals()
        refreshAbsoluteVolume(atConnect = false)
        if (_state.value.inCall) refreshCodec()
    }

    // ---- Call detection ------------------------------------------------------------------------

    private fun registerCallListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerApi31Listeners()
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { work.launch { updateSignals() } }
            }
            ContextCompat.registerReceiver(
                appContext, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    // Lives in its own method so the API 31 listener interfaces are never touched on older releases.
    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31Listeners() {
        // The callbacks only hop onto [work]; running them inline on the binder thread is fine.
        val direct = Executor { it.run() }
        runCatching {
            audioManager.addOnModeChangedListener(direct) { mode ->
                Log.d(TAG, "audio mode → ${modeName(mode)}")
                work.launch { updateSignals() }
            }
            audioManager.addOnCommunicationDeviceChangedListener(direct) { device ->
                Log.d(TAG, "communication device → ${device?.let { "${typeName(it.type)} ${it.productName}" } ?: "none"}")
                work.launch { updateSignals() }
            }
        }.onFailure { Log.w(TAG, "audio listeners unavailable: $it") }
    }

    /** Re-reads mode, route and call volume, then lets the scheduler and the in-call loop react. */
    private fun updateSignals() {
        val mode = audioManager.mode
        val inCall = CallSignals.isCallMode(mode)
        val newRoute = if (inCall) readRoute() else RouteInfo(CallRoute.UNKNOWN, null, null)
        val onHeadphones = inCall && CallSignals.isHeadsetRoute(newRoute.route)
        val prev = _state.value
        route = newRoute
        _state.update {
            it.copy(
                inCall = inCall,
                onHeadphones = onHeadphones,
                routedDeviceName = if (inCall) newRoute.name else null,
                callVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL),
                callVolumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
            )
        }
        if (inCall != prev.inCall) {
            if (inCall) {
                Log.i(TAG, "call started (${modeName(mode)}), route ${newRoute.route}${newRoute.name?.let { " \"$it\"" } ?: ""}, volume ${_state.value.callVolume}/${_state.value.callVolumeMax}")
                diagnosticsLoggedThisCall = false
                startInCallLoop()
            } else {
                Log.i(TAG, "call ended, last codec ${prev.callCodec ?: "unknown"}")
                inCallJob?.cancel()
                inCallJob = null
            }
        } else if (inCall && onHeadphones != prev.onHeadphones) {
            Log.i(TAG, "call route → ${newRoute.route}${newRoute.name?.let { " \"$it\"" } ?: ""}")
        }
        scheduler.update(inCall, onHeadphones)
    }

    private fun readRoute(): RouteInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = runCatching { audioManager.communicationDevice }.getOrNull()
            if (device != null) {
                return RouteInfo(CallSignals.routeFor(device.type), device.address.takeIf { it.isNotBlank() }, device.productName?.toString())
            }
        }
        // Android 9-11, or no communication device reported: the legacy SCO flag is the best signal.
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothScoOn) {
            val sco = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            return RouteInfo(CallRoute.BLUETOOTH_SCO, sco?.address?.takeIf { it.isNotBlank() }, sco?.productName?.toString())
        }
        return RouteInfo(CallRoute.UNKNOWN, null, null)
    }

    private fun startInCallLoop() {
        inCallJob?.cancel()
        inCallJob = work.launch {
            var tick = 0
            while (isActive) {
                delay(POLL_MS)
                updateSignals()
                if (!_state.value.inCall) break
                if (tick % CODEC_EVERY_TICKS == 0) refreshCodec()
                tick++
            }
        }
    }

    // ---- Boost ---------------------------------------------------------------------------------

    private suspend fun autoBoost(attempt: Int) {
        val prefs = _preferences.value
        if (!prefs.boostEnabled) {
            if (attempt == 0) Log.i(TAG, "call on headphones; auto-boost is off")
            return
        }
        updateSignals() // the route may have moved in the last second
        currentCoroutineContext().ensureActive() // the scheduler cancels us if the call just left the headphones
        val s = _state.value
        if (!s.inCall || !s.onHeadphones) return
        try {
            applyBoost(prefs, onlyRaise = true, reason = "auto boost #${attempt + 1}")
        } catch (e: HeadphoneCommandException) {
            Log.w(TAG, "auto boost failed: ${e.message}")
        }
    }

    /** Throws [HeadphoneCommandException] (and records [CallAudioState.lastError]) on failure. */
    private suspend fun applyBoost(prefs: CallAudioPreferences, onlyRaise: Boolean, reason: String) {
        val stream = AudioManager.STREAM_VOICE_CALL
        val max = audioManager.getStreamMaxVolume(stream)
        val min = audioManager.getStreamMinVolume(stream)
        val before = audioManager.getStreamVolume(stream)
        val target = CallVolumeBoost.target(max, prefs.boostPercent, min)
            ?: fail("Android reports no call volume range right now.")

        var changed = false
        if (!onlyRaise || CallVolumeBoost.shouldRaise(before, target)) {
            try {
                audioManager.setStreamVolume(stream, target, 0)
                changed = true
            } catch (e: SecurityException) {
                fail("Android blocked the call-volume change (${e.message ?: "Do Not Disturb?"}).")
            }
        }
        val after = audioManager.getStreamVolume(stream)
        Log.i(TAG, "$reason: call volume $before → $after (target $target, range $min..$max)${if (!changed) ", already loud enough" else ""}")
        if (changed && after < target) Log.w(TAG, "Android kept the call volume at $after < $target; One UI or another app may be capping it")

        val headsetSent = maybeRaiseHeadset(prefs, reason)
        _state.update {
            it.copy(
                callVolume = after,
                callVolumeMax = max,
                lastBoostAppliedAt = if (changed || headsetSent) System.currentTimeMillis() else it.lastBoostAppliedAt,
                lastError = null,
            )
        }
    }

    private suspend fun maybeRaiseHeadset(prefs: CallAudioPreferences, reason: String): Boolean {
        if (!prefs.alsoRaiseHeadset) return false
        val hp = headphones.state.value
        if (!hp.isConnected) return false
        // With multipoint the call may be on another headset; only raise the Bose when it is the call device.
        if (!CallSignals.sameAddress(route.address, hp.connection.deviceAddress)) {
            Log.i(TAG, "$reason: call is on another Bluetooth device, headphones volume left alone")
            return false
        }
        return try {
            headsetVolume.setVolumePercent(prefs.boostPercent).also { sent ->
                Log.i(TAG, "$reason: headphones volume → ${prefs.boostPercent}% ${if (sent) "sent" else "not supported yet"}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "$reason: headphones volume failed: ${t.message}")
            false
        }
    }

    // ---- Codec ---------------------------------------------------------------------------------

    private suspend fun refreshCodec() {
        if (codecReadInFlight) return
        if (!shell.isAvailable()) {
            if (!noShellLogged) Log.i(TAG, "call codec unknown: Shizuku not ready")
            noShellLogged = true
            return
        }
        noShellLogged = false
        codecReadInFlight = true
        try {
            val text = shell.exec(DUMPSYS).getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "dumpsys bluetooth_manager failed: ${it.message}")
                return
            }
            val dump = CallCodecParser.parse(text)
            val reading = CallCodecParser.resolve(dump, route.route, headphones.state.value.connection.deviceAddress)
            if (reading == null) {
                if (!diagnosticsLoggedThisCall) {
                    diagnosticsLoggedThisCall = true
                    Log.w(TAG, "call codec not found in dumpsys (${text.length} chars, hfp section=${dump.hasHfpSection}, hfp devices=${dump.hfpDevices.size}, le active=${dump.leAudioActive}); candidate lines:")
                    CallCodecParser.diagnosticLines(text).forEach { Log.i(TAG, "  | $it") }
                }
                return
            }
            val prev = _state.value.callCodec
            _state.update {
                it.copy(
                    callCodec = reading.codec,
                    wideband = reading.wideband,
                    callSampleRateHz = reading.sampleRateHz,
                    codecReadAt = System.currentTimeMillis(),
                )
            }
            if (reading.codec != prev || !diagnosticsLoggedThisCall) {
                diagnosticsLoggedThisCall = true
                Log.i(TAG, "call codec ${reading.codec} (${if (reading.wideband) "wideband" else "narrowband"}${reading.sampleRateHz?.let { ", ${it / 1000} kHz" } ?: ""}), route ${route.route}")
            }
        } finally {
            codecReadInFlight = false
        }
    }

    // ---- Absolute volume -----------------------------------------------------------------------

    private suspend fun readAbsoluteVolume(): Boolean? {
        if (!shell.isAvailable()) return null
        return shell.exec(AbsoluteVolumeSetting.READ_COMMAND).fold(
            onSuccess = { AbsoluteVolumeSetting.parse(it) },
            onFailure = {
                if (it is CancellationException) throw it
                Log.w(TAG, "absolute-volume read failed: ${it.message}")
                null
            },
        )
    }

    /** @param atConnect true when the value read is what Android is using now (fresh connection / first read). */
    private suspend fun refreshAbsoluteVolume(atConnect: Boolean) {
        val value = readAbsoluteVolume()
        if (atConnect && value != null) absoluteVolumeInEffect = value
        _state.update { it.copy(absoluteVolumeDisabled = value, absoluteVolumeNeedsReconnect = value != null && needsReconnect(value)) }
    }

    private fun needsReconnect(value: Boolean): Boolean {
        val inEffect = absoluteVolumeInEffect ?: return false
        return value != inEffect
    }

    private fun fail(message: String): Nothing {
        _state.update { it.copy(lastError = message) }
        throw HeadphoneCommandException(message)
    }

    private companion object {
        const val TAG = "Earshot/CallAudio"
        const val POLL_MS = 2_000L
        /** Codec every 5th poll = every 10 s. */
        const val CODEC_EVERY_TICKS = 5
        const val DUMPSYS = "dumpsys bluetooth_manager"
        const val SHIZUKU_NEEDED = "Changing absolute volume needs Shizuku: start it and grant Earshot (Tools → Codec → Grant)."

        fun modeName(mode: Int) = when (mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            4 -> "CALL_SCREENING"
            5 -> "CALL_REDIRECT"
            6 -> "COMMUNICATION_REDIRECT"
            else -> "mode $mode"
        }

        fun typeName(type: Int) = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
            26 -> "BLE_HEADSET"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED"
            else -> "type $type"
        }
    }
}

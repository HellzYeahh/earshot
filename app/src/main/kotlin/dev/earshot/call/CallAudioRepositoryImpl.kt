package dev.earshot.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.earshot.R
import dev.earshot.core.CallAudioPreferences
import dev.earshot.core.CallAudioRepository
import dev.earshot.core.CallAudioState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HFP_GAIN_MAX
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneRepository
import dev.earshot.sleep.BackgroundAudio
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
 *
 * Android 17 ignores the volume call while Earshot is closed and the headset service has no
 * while-in-use status, which at target 37 is the usual case: that service starts from a Bluetooth
 * connect or companion presence, in the background ([BackgroundAudio]). Below target 37 the
 * service being in the foreground is enough, so only a call without it counts. The read-back catches it
 * ([CallVolumeBoost.refusedInBackground]): the boost is marked in [CallAudioState.lastError], a
 * "Tap to boost" notification goes up once per call, and the boost runs as soon as an Earshot
 * screen starts. The headset-side boost ([maybeRaiseHeadset]) is a Bluetooth command, not an
 * audio call, and still goes out.
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
    private val boostNotification = CallBoostNotification(appContext)

    private data class RouteInfo(val route: CallRoute, val address: String?, val name: String?)

    private var route = RouteInfo(CallRoute.UNKNOWN, null, null)
    private var inCallJob: Job? = null
    private var codecReadInFlight = false
    private var diagnosticsLoggedThisCall = false
    private var noShellLogged = false
    /** "Waiting for the route" is logged once per call, not on every check. */
    private var routeWaitLogged = false
    private var lastLoggedGain: Int? = null
    private var lastLoggedNrec: Boolean? = null
    private var lastVolumeReports: Int? = null
    /** This call's automatic boost was ignored by Android 17 (Earshot closed): boost once a screen starts. */
    private var boostBlocked = false
    /** The "Tap to boost" notification went up for this call. Once per call, not per scheduled fire. */
    private var boostNotified = false

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
        // An Earshot screen started (the "Tap to boost" notification, or the user opened the app):
        // a visible app may change volumes, so the boost Android ignored can run now.
        work.launch {
            BackgroundAudio.visibleScreens.map { it > 0 }.distinctUntilChanged().collect { visible ->
                if (visible && boostBlocked) boostAfterOpen()
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
                // The type only: the product name may be a car's or someone else's headset name, and
                // next to call times that is personal data (no PII in logs beyond the headphones' address).
                Log.d(TAG, "communication device → ${device?.let { typeName(it.type) } ?: "none"}")
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
                // Route type only, never the device's name (see the communication-device listener).
                Log.i(TAG, "call started (${modeName(mode)}), route ${newRoute.route}, volume ${_state.value.callVolume}/${_state.value.callVolumeMax}")
                diagnosticsLoggedThisCall = false
                routeWaitLogged = false
                lastLoggedGain = null
                lastLoggedNrec = null
                lastVolumeReports = null
                boostBlocked = false
                boostNotified = false
                startInCallLoop()
            } else {
                Log.i(TAG, "call ended, last codec ${prev.callCodec ?: "unknown"}")
                inCallJob?.cancel()
                inCallJob = null
                // lastError stays: it tells the call screen afterwards why this call wasn't boosted.
                boostBlocked = false
                boostNotification.cancel()
            }
        } else if (inCall && onHeadphones != prev.onHeadphones) {
            Log.i(TAG, "call route → ${newRoute.route}")
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
        // Android keeps one call volume per output and changes the one the call is routed to *now*.
        // Until the Bluetooth call link is up that is the earpiece: boosting then raised the
        // earpiece and left the headset where it was. Wait for the route; a later fire catches it.
        if (voiceRoutedToHeadset() == false) {
            if (!routeWaitLogged) Log.i(TAG, "auto boost #${attempt + 1}: call audio not on the headset yet, waiting")
            routeWaitLogged = true
            return
        }
        try {
            applyBoost(prefs, onlyRaise = true, reason = "auto boost #${attempt + 1}")
        } catch (e: HeadphoneCommandException) {
            Log.w(TAG, "auto boost failed: ${e.message}")
        }
    }

    /** The boost Android 17 ignored while Earshot was closed, now that a screen is visible. */
    private suspend fun boostAfterOpen() {
        val prefs = _preferences.value
        updateSignals()
        val s = _state.value
        if (!prefs.boostEnabled || !s.inCall || !s.onHeadphones) {
            boostBlocked = false
            boostNotification.cancel()
            return
        }
        if (voiceRoutedToHeadset() == false) return
        try {
            applyBoost(prefs, onlyRaise = true, reason = "boost once Earshot was opened")
        } catch (e: HeadphoneCommandException) {
            Log.w(TAG, "boost after opening Earshot failed: ${e.message}")
        }
    }

    /**
     * Where Android sends call audio right now (API 33+: the audio policy's own answer for voice
     * communication). True for Bluetooth SCO or an LE Audio headset, false for anything else, null
     * when it can't tell (older Android: then the communication device decided).
     */
    private fun voiceRoutedToHeadset(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val devices = runCatching { audioManager.getAudioDevicesForAttributes(attributes) }.getOrNull() ?: return null
        return devices.any { CallSignals.isHeadsetRoute(CallSignals.routeFor(it.type)) }
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
        // Android 17 says nothing when it ignores the call; the read-back is the only sign. Counted
        // as a refusal only where Android really requires more than Earshot has: below target 37
        // the headset service (foreground while a headset is linked) is enough, so a volume that
        // didn't move there is a cap and gets the "kept the call volume" warning below instead.
        val targetSdk = appContext.applicationInfo.targetSdkVersion
        val refused = changed && CallVolumeBoost.refusedInBackground(
            before, after, target,
            sdk = Build.VERSION.SDK_INT,
            targetSdk = targetSdk,
            appVisible = BackgroundAudio.isAppVisible,
            foregroundService = headsetServiceForeground(),
        )
        if (refused) changed = false
        // Six checks per call: only a change (or a manual boost) is worth an info line.
        val line = "$reason: call volume $before → $after (target $target, range $min..$max), output ${voiceOutputName()}${if (!changed && !refused) ", already loud enough" else ""}"
        if (changed || refused || !onlyRaise) Log.i(TAG, line) else Log.d(TAG, line)
        if (refused) {
            Log.w(TAG, "Android ignored the call-volume change: Earshot isn't visible and has no foreground service with the status Android ${Build.VERSION.SDK_INT} needs at target $targetSdk")
        } else if (changed && after < target) {
            Log.w(TAG, "Android kept the call volume at $after < $target; One UI or another app may be capping it")
        }

        val headsetSent = maybeRaiseHeadset(prefs, reason)
        boostBlocked = refused
        if (refused) {
            if (!boostNotified) {
                boostNotified = true
                boostNotification.show()
            }
        } else {
            boostNotification.cancel()
        }
        _state.update {
            it.copy(
                callVolume = after,
                callVolumeMax = max,
                lastBoostAppliedAt = if (changed || headsetSent) System.currentTimeMillis() else it.lastBoostAppliedAt,
                lastError = if (refused) appContext.getString(R.string.a17_call_boost_blocked) else null,
            )
        }
        // Read back the HFP gain the headset now has (Android sends +VGS when the index changes), so
        // the log shows whether the boost reached the headphones. Separate job: the serial
        // dispatcher stays free meanwhile.
        if (changed) work.launch {
            delay(GAIN_CHECK_DELAY_MS)
            if (_state.value.inCall) refreshCodec(gainCause = reason)
        }
    }

    /**
     * Is the headset service in the foreground? It mirrors HeadphoneService's own rule: it stays up
     * while the control link is up or connecting, or the headset is linked for audio. Erring
     * towards true only turns a refusal at target 36 into the milder "kept the call volume" log.
     */
    private fun headsetServiceForeground(): Boolean {
        val hp = headphones.state.value
        return hp.isAudioActive || hp.connection.status == ConnectionStatus.CONNECTING
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

    /** The output call audio goes to now, for the log ("BT_SCO", "EARPIECE"…); "?" before API 33. */
    private fun voiceOutputName(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "?"
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build()
        return runCatching { audioManager.getAudioDevicesForAttributes(attributes).joinToString { typeName(it.type) } }.getOrDefault("?").ifEmpty { "none" }
    }

    // ---- Codec ---------------------------------------------------------------------------------

    /** @param gainCause what changed the gain, when this read follows our own boost; null for a poll. */
    private suspend fun refreshCodec(gainCause: String? = null) {
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
            recordHfpLink(CallCodecParser.hfpLink(dump, route.route, headphones.state.value.connection.deviceAddress), gainCause)
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

    /** Puts the HFP gains and NREC flag into the state and logs every change once. */
    private fun recordHfpLink(link: HfpDeviceDump?, gainCause: String?) {
        val gain = link?.speakerGain
        val nrec = link?.phoneNrec
        _state.update { it.copy(headsetCallGain = gain, headsetMicGain = link?.micGain, phoneNoiseReduction = nrec) }
        val reports = link?.headsetVolumeReports
        val headsetReported = reports != null && lastVolumeReports != null && reports != lastVolumeReports
        if (reports != null) lastVolumeReports = reports
        if (gain != null && gain != lastLoggedGain) {
            val why = when {
                gainCause != null -> gainCause
                headsetReported -> "the headset reported a new volume (AT+VGS)"
                lastLoggedGain == null -> "first read this call"
                else -> "changed outside Earshot"
            }
            Log.i(TAG, "headset call gain ${lastLoggedGain ?: "?"} → $gain/$HFP_GAIN_MAX ($why), mic gain ${link.micGain?.let { "$it/$HFP_GAIN_MAX" } ?: "?"}")
            lastLoggedGain = gain
        }
        if (nrec != null && nrec != lastLoggedNrec) {
            Log.i(TAG, if (nrec) "phone echo cancelling / noise reduction is ON for this call (no AT+NREC=0 from the headset)" else "phone echo cancelling / noise reduction is off: the headset sent AT+NREC=0")
            lastLoggedNrec = nrec
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
        /** After a boost, how long before reading the HFP gain back (the +VGS goes out asynchronously). */
        const val GAIN_CHECK_DELAY_MS = 500L
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
